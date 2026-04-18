package org.example.kbsystemproject.ailearning.agent;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.*;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveTool;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveToolRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
public class ReActAgent extends AbstractChatAgent {

    private static final String FINISH_TOOL_NAME = "FinishTaskTool"; // 终止工具的名称
    private final ChatClient chatClient;
    private final ReactiveToolRegistry toolRegistry; // 你的工具执行器
    private final int maxSteps; // 最大步骤数

    public ReActAgent(ChatClient chatClient, List<ReactiveTool> tools, int maxSteps) {
        this.chatClient = chatClient;
        this.toolRegistry = new ReactiveToolRegistry();
        tools.forEach(toolRegistry::register);
        this.maxSteps = maxSteps;
    }

    public ReActAgent(ChatClient chatClient, List<ReactiveTool> tools, List<ToolCallback> mcpTools, int maxSteps) {
        this.chatClient = chatClient;
        this.toolRegistry = new ReactiveToolRegistry();
        if (tools != null) {
            tools.forEach(toolRegistry::register);
        }
        if (mcpTools != null) {
            mcpTools.forEach(toolRegistry::register);
        }
        this.maxSteps = maxSteps;
    }

    @Override
    public ChatClient getChatClient() {
        // 关键：将工具适配器注册到 ChatClient 中
        return chatClient;
    }

    @Override
    protected int getMaxSteps() { return this.maxSteps; }

    /**
     * 核心逻辑：处理思考结果 -> 决策 -> 行动 -> 状态更新
     */
    @Override
    protected Flux<AgentSignal> handleThinkingResult(AgentContext context, AssistantMessage message) {

        // 1. 核心决策逻辑
        Decision decision = analyzeStepOutput(message);

        // 2. 执行决策
        return switch (decision) {
            case Decision.CallTool callTool -> {
                log.info("Tool use");
                AgentContext currentContext = context
                        .appendHistory(message)
                        .nextStep();

                // === 关键点：在这里判断是否是终止工具 ===
                boolean isTerminalTool = callTool.calls().stream()
                        .anyMatch(tc -> FINISH_TOOL_NAME.equals(tc.name()));

                // A. 执行工具调用
                List<Mono<ToolResponse>> executionMonos = callTool.calls().stream()
                        .map(call -> toolRegistry.execute(call.name(), call.arguments(), context.toToolContext())
                                // 规范：无论外部工具实现写得多糟糕（是否不小心写了阻塞），
                                // 统一转交到 BaseAgent 暴露出来的 Agent 专属隔离线程池(ai-agent-pool)执行，
                                // 最大化并发时不会占用 WebFlux 的全局 default pool。
                                .subscribeOn(this.getScheduler())
                                .map(result -> {
                                    // 【关键】构建 Spring AI 的 ToolResponse 对象
                                    // 参数：id, name, responseData
                                    return new ToolResponse(call.id(), call.name(), result);
                                }))
                        .toList();

                yield Flux.merge(executionMonos)
                        .collectList()
                        .flatMapMany(responses -> {
                            // 3. 统一追加 ToolResponseMessage
                            AgentContext nextContext = currentContext.appendToolResponses(responses);

                            if (isTerminalTool) {
                                // 提取最终结果 (这里简单取最后一个结果，或根据业务逻辑处理)
                                String lastResult = responses.isEmpty() ? "" : responses.getLast().responseData();
                                return Flux.just(AgentSignal.event(new AgentEvent(AgentState.FINISHED, lastResult)));
                            } else {
                                // 5. 生成下一步信号
                                return Flux.just(
                                        AgentSignal.event(new AgentEvent(AgentState.TOOL_RESULT, "Tools executed: " + responses.size())),
                                        AgentSignal.next(nextContext.nextStep())
                                );
                            }
                        });
            }

            case Decision.Continue continueDecision -> {
                log.info("test_continue_decision: {}", message.getText());
                // LLM 没调用工具，只说了话，可能是中间思考，推回去继续想
                AgentContext nextContext = context.appendHistory(message).nextStep();
                yield Flux.just(
                        AgentSignal.event(new AgentEvent(AgentState.ITERATION_COMPLETE, message.getText())),
                        AgentSignal.next(nextContext)
                );
            }

            case Decision.Stop stop ->
                    Flux.just(AgentSignal.event(new AgentEvent(AgentState.TERMINAL, stop.reason())));

            default -> throw new IllegalStateException("Unexpected value: " + decision);
        };
    }

    private Decision analyzeStepOutput(AssistantMessage message) {
        // 优先检查工具调用
        if (message.hasToolCalls()) {
            var tc = message.getToolCalls().getFirst();
            // 不管是 finish_task 还是 search_web，统统视为 CallTool
            return new Decision.CallTool(message.getToolCalls());
        }

        // 没有工具调用
        return new Decision.Continue();
    }
}
