package org.example.kbsystemproject.ailearning.agent;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.*;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveTool;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.SystemMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.Map;

/**
 * PERAgent (Plan -> Execute -> Reflect -> Adjust) 闭环代理封装
 *
 * 【ReActAgent 与 PERAgent 的应用场景区分】：
 * 1. ReActAgent (Reason + Act):
 *    - 应用场景：适用于步骤较少、相对简单直接的单次任务求解。
 *    - 逻辑：边想边做（Thought -> Action -> Observation -> Thought...）。每次执行行动前只有局部的、即时的推理，缺乏全局视野。
 *    - 缺点：遇到需要多步依赖、长程规划的复杂任务时，容易在中间步骤迷失方向，陷入死循环或者忘记最初的目的。
 *
 * 2. PERAgent (Plan -> Execute -> Reflect -> Adjust):
 *    - 应用场景：适用于复杂的宏大目标、长视距任务（Long-horizon tasks）、代码生成与复杂项目构建、或者需要纠错能力的开放域探究。
 *    - 逻辑：
 *      - Plan（规划）：先在脑海中/或输出明确且分步骤的执行计划。
 *      - Execute（执行）：严格按照计划的当前步骤去调用工具或进行处理。
 *      - Reflect（反馈/反思）：在某个步骤执行完后，收集结果并对当前结果进行自我评估（即是否达到预期）。
 *      - Adjust（调整）：如果符合预期则进入主线下一步；如果偏离路线或遇到报错，则修改/调整原有 Plan，重新 Execute。
 *    - 优点：全局把控感强，能够自我纠错（Self-Correction），面对复杂情况的鲁棒性极高。
 */
@Slf4j
public class PERAgent extends AbstractChatAgent {

    public enum Phase {
        PLAN,       // 规划阶段：生成包含步骤的计划
        EXECUTE,    // 执行阶段：按照计划执行当前步骤（可能会调用工具）
        REFLECT,    // 反馈阶段：检查执行结果是否达到了该步骤的预期目标
        ADJUST,     // 调整阶段：如果偏离或失败，在此修改剩余的计划
        FINISHED    // 完成阶段
    }

    private final ChatClient chatClient;
    private final ReactiveToolRegistry toolRegistry;
    private final int maxSteps;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String PHASE_KEY = "CURRENT_PHASE";
    private static final String PLAN_KEY = "CURRENT_PLAN";
    private static final String STEP_INDEX_KEY = "CURRENT_STEP_INDEX";

    public PERAgent(ChatClient chatClient, List<ReactiveTool> tools, int maxSteps, Scheduler agentScheduler) {
        super(agentScheduler);
        this.chatClient = chatClient;
        this.toolRegistry = new ReactiveToolRegistry();
        tools.forEach(this.toolRegistry::register);
        this.maxSteps = maxSteps;
    }

    public PERAgent(ChatClient chatClient, List<ReactiveTool> tools, List<org.springframework.ai.tool.ToolCallback> mcpTools, int maxSteps, Scheduler agentScheduler) {
        super(agentScheduler);
        this.chatClient = chatClient;
        this.toolRegistry = new ReactiveToolRegistry();
        if (tools != null) {
            tools.forEach(this.toolRegistry::register);
        }
        if (mcpTools != null) {
            mcpTools.forEach(this.toolRegistry::register);
        }
        this.maxSteps = maxSteps;
    }

    @Override
    protected ChatClient getChatClient() {
        return chatClient;
    }

    @Override
    protected int getMaxSteps() {
        return maxSteps;
    }

    /**
     * 重写基础的上下文处理，以在每次调用大模型前注入当前 Phase 的系统指导
     */
    @Override
    protected Flux<AgentSignal> executeStep(AgentContext context) {
        Phase phase = getPhase(context);
        log.info("PERAgent is entering phase: {}", phase);

        if (phase == Phase.FINISHED) {
            return Flux.just(AgentSignal.event(new AgentEvent(AgentState.TERMINAL, "任务已完成")));
        }

        // 根据不同阶段，给模型注入对应的强制 prompt 指导
        String phaseInstruction = getPhaseInstruction(phase, context);

        AgentContext injectedContext = context.appendHistory(new SystemMessage(phaseInstruction));

        return super.executeStep(injectedContext);
    }

    @Override
    protected Flux<AgentSignal> handleThinkingResult(AgentContext context, AssistantMessage message) {
        Phase currentPhase = getPhase(context);

        try {
            switch (currentPhase) {
                case PLAN -> {
                    log.info("【PLAN】阶段完成，模型给出了计划:\n{}", message.getText());
                    JsonNode planNode = extractJsonInfo(message.getText());
                    AgentContext nextContext = advanceToPhase(context, message, Phase.EXECUTE);
                    if (planNode != null) {
                        nextContext = updateBusinessContext(nextContext, PLAN_KEY, planNode.toString());
                        nextContext = updateBusinessContext(nextContext, STEP_INDEX_KEY, 0);
                    }
                    return Flux.just(
                            AgentSignal.event(new AgentEvent(AgentState.ITERATION_COMPLETE, "Plan completed.")),
                            AgentSignal.next(nextContext)
                    );
                }
                case EXECUTE -> {
                    log.info("【EXECUTE】阶段，执行当前步骤。");
                    if (message.hasToolCalls()) {
                        var toolCalls = message.getToolCalls();
                        Decision.CallTool callTool = new Decision.CallTool(toolCalls);

                        List<Mono<ToolResponse>> executionMonos = callTool.calls().stream()
                                .map(call -> toolRegistry.execute(call.name(), call.arguments(), context.toToolContext())
                                .subscribeOn(this.getScheduler())
                                .map(result -> new ToolResponse(call.id(), call.name(), result)))
                                .toList();

                        return Flux.merge(executionMonos)
                                .collectList()
                                .flatMapMany(responses -> {
                                    AgentContext nextContext = context.appendHistory(message)
                                            .appendToolResponses(responses);

                                    // 执行完毕后进入 REFLECT 阶段反思
                                    nextContext = setPhase(nextContext, Phase.REFLECT);
                                    return Flux.just(
                                            AgentSignal.event(new AgentEvent(AgentState.TOOL_RESULT, "Executed tool, moving to REFLECT")),
                                            AgentSignal.next(nextContext.nextStep())
                                    );
                                });
                    } else {
                        // 没有需要执行的工具
                        AgentContext nextContext = advanceToPhase(context, message, Phase.REFLECT);
                        return Flux.just(AgentSignal.next(nextContext));
                    }
                }
                case REFLECT -> {
                    log.info("【REFLECT】阶段，自我评估结果:\n{}", message.getText());
                    JsonNode reflectNode = extractJsonInfo(message.getText());
                    String status = reflectNode != null && reflectNode.has("status") ? reflectNode.get("status").asText() : "";

                    Phase nextPhase;
                    if ("ALL_DONE".equals(status)) {
                        nextPhase = Phase.FINISHED;
                    } else if ("SUCCESS".equals(status)) {
                        nextPhase = Phase.EXECUTE;
                        Integer stepIdx = (Integer) context.businessContext().getOrDefault(STEP_INDEX_KEY, 0);
                        AgentContext updatedContext = updateBusinessContext(context, STEP_INDEX_KEY, stepIdx + 1);
                        AgentContext nextContext = advanceToPhase(updatedContext, message, nextPhase);

                        if (nextPhase == Phase.FINISHED) {
                            return Flux.just(
                                    AgentSignal.event(new AgentEvent(AgentState.FINISHED, message.getText())),
                                    AgentSignal.next(nextContext)
                            );
                        } else {
                            return Flux.just(AgentSignal.next(nextContext));
                        }
                    } else {
                        nextPhase = Phase.ADJUST;
                        AgentContext nextContext = advanceToPhase(context, message, nextPhase);
                        return Flux.just(AgentSignal.next(nextContext));
                    }
                }
                case ADJUST -> {
                    log.info("【ADJUST】阶段，调整计划:\n{}", message.getText());
                    JsonNode adjustNode = extractJsonInfo(message.getText());
                    AgentContext nextContext = advanceToPhase(context, message, Phase.EXECUTE);
                    if (adjustNode != null && adjustNode.has("new_plan")) {
                        nextContext = updateBusinessContext(nextContext, PLAN_KEY, adjustNode.get("new_plan").toString());
                        nextContext = updateBusinessContext(nextContext, STEP_INDEX_KEY, 0);
                    }
                    return Flux.just(AgentSignal.next(nextContext));
                }
                default -> {
                    return Flux.just(AgentSignal.event(new AgentEvent(AgentState.TERMINAL, "Unknown Phase")));
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse stage result: ", e);
            // Fallback
            AgentContext nextContext = advanceToPhase(context, message, Phase.ADJUST);
            return Flux.just(AgentSignal.next(nextContext));
        }
        return Flux.empty();
    }

    private JsonNode extractJsonInfo(String text) {
        if (text == null) return null;
        try {
            int start = text.indexOf("{");
            int end = text.lastIndexOf("}");
            if (start != -1 && end != -1 && end > start) {
                return mapper.readTree(text.substring(start, end + 1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private AgentContext updateBusinessContext(AgentContext context, String key, Object value) {
        Map<String, Object> newBusinessContext = new java.util.HashMap<>(context.businessContext());
        newBusinessContext.put(key, value);
        return new AgentContext(context.history(), context.currentStep(), newBusinessContext);
    }

    private Phase getPhase(AgentContext context) {
        String phaseStr = (String) context.businessContext().getOrDefault(PHASE_KEY, Phase.PLAN.name());
        return Phase.valueOf(phaseStr);
    }

    private AgentContext setPhase(AgentContext context, Phase newPhase) {
        return updateBusinessContext(context, PHASE_KEY, newPhase.name());
    }

    private AgentContext advanceToPhase(AgentContext context, AssistantMessage message, Phase nextPhase) {
        AgentContext newCtx = context.appendHistory(message).nextStep();
        return updateBusinessContext(newCtx, PHASE_KEY, nextPhase.name());
    }

    private String getPhaseInstruction(Phase phase, AgentContext context) {
        String planStr = (String) context.businessContext().getOrDefault(PLAN_KEY, "尚未制定计划");
        Integer stepIdx = (Integer) context.businessContext().getOrDefault(STEP_INDEX_KEY, 0);

        return switch (phase) {
            case PLAN -> "你现在处于计划(PLAN)阶段：请作为架构师，为了完成用户的目标，制定一个分步计划。只输出 JSON，格式如:\n" +
                         "{\n" +
                         "  \"goal\": \"用户的最终目标\",\n" +
                         "  \"plan\": [{\"step_id\": 1, \"task\": \"任务1\"}, {\"step_id\": 2, \"task\": \"任务2\"}]\n" +
                         "}";
            case EXECUTE -> "你现在处于执行(EXECUTE)阶段。当前的整个计划是：\n" + planStr + "\n" +
                            "你当前需要执行计划的第 " + stepIdx + " 步。如果在此步骤中需要调用工具，请立刻调用；如果能直接回答，请直接输出该步产出结果。";
            case REFLECT -> "你现在处于反思(REFLECT)阶段。请作为一个严厉的质检员，对照当前步骤的目标和刚刚执行工具拿到的结果，评估该步骤是否成功。\n" +
                            "必须返回以 JSON 结构:\n" +
                            "{\n" +
                            "  \"status\": \"SUCCESS 或 FAILED 或 ALL_DONE\",\n" +
                            "  \"reason\": \"评估的原因理由\",\n" +
                            "  \"extracted_data\": \"抽取出的关键信息(可选)\"\n" +
                            "}";
            case ADJUST -> "你现在处于调整(ADJUST)阶段。由于在上一步骤的执行中遭遇了 FAILED。请分析失败原因，并修改剩余部分的子计划。\n" +
                           "原先的计划是:\n" + planStr + "\n" +
                           "必须输出如下 JSON 格式:\n" +
                           "{\n" +
                           "  \"analysis\": \"失败原因分析\",\n" +
                           "  \"new_plan\": [{\"step_id\": 1, \"task\": \"修改后的任务1\"}, ...]\n" +
                           "}";
            case FINISHED -> "总结并向用户输出最终结果。";
        };
    }
}

