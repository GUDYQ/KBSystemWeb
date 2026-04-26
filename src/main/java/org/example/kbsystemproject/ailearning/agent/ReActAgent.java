package org.example.kbsystemproject.ailearning.agent;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.*;
import org.example.kbsystemproject.ailearning.domain.session.ToolExecutionStatus;
import org.example.kbsystemproject.ailearning.domain.session.ToolExecutionTrace;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveTool;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveToolRegistry;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveToolRegistry.ToolRegistration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
public class ReActAgent extends AbstractChatAgent {

    private static final String FINISH_TOOL_NAME = "FinishTaskTool"; // 终止工具的名称
    private static final int MAX_TRACE_TEXT_LENGTH = 4096;
    private static final int MAX_TRACE_SUMMARY_LENGTH = 240;
    private final ChatClient chatClient;
    private final ReactiveToolRegistry toolRegistry; // 你的工具执行器
    private final int maxSteps; // 最大步骤数

    public ReActAgent(ChatClient chatClient, List<ReactiveTool> tools, int maxSteps, Scheduler agentScheduler) {
        super(agentScheduler);
        this.chatClient = chatClient;
        this.toolRegistry = new ReactiveToolRegistry();
        tools.forEach(toolRegistry::register);
        this.maxSteps = maxSteps;
    }

    public ReActAgent(ChatClient chatClient, List<ReactiveTool> tools, List<ToolCallback> mcpTools, int maxSteps, Scheduler agentScheduler) {
        super(agentScheduler);
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

                Flux<ToolExecutionOutcome> outcomeFlux = Flux.fromIterable(callTool.calls())
                        .concatMap(call -> executeToolCall(context, currentContext.currentStep(), call))
                        .cache();

                Flux<AgentSignal> toolEventSignals = outcomeFlux.concatMap(outcome -> Flux.just(
                        AgentSignal.event(outcome.startEvent()),
                        AgentSignal.event(outcome.resultEvent())
                ));

                Flux<AgentSignal> continuationSignals = outcomeFlux.collectList()
                        .flatMapMany(responses -> {
                            List<ToolResponse> toolResponses = responses.stream()
                                    .map(ToolExecutionOutcome::toolResponse)
                                    .toList();
                            // 3. 统一追加 ToolResponseMessage
                            AgentContext nextContext = currentContext.appendToolResponses(toolResponses);

                            if (isTerminalTool) {
                                // 提取最终结果 (这里简单取最后一个结果，或根据业务逻辑处理)
                                String lastResult = toolResponses.isEmpty() ? "" : toolResponses.getLast().responseData();
                                return Flux.just(AgentSignal.event(new AgentEvent(AgentState.FINISHED, lastResult, Map.of())));
                            } else {
                                // 5. 生成下一步信号
                                return Flux.just(
                                        AgentSignal.event(new AgentEvent(AgentState.TOOL_RESULT, "Tools executed: " + responses.size(), Map.of("toolBatchSize", responses.size()))),
                                        AgentSignal.next(nextContext.nextStep())
                                );
                            }
                        });
                yield Flux.concat(toolEventSignals, continuationSignals);
            }

            case Decision.Continue continueDecision -> {
                log.info("test_continue_decision: {}", message.getText());
                // LLM 没调用工具，只说了话，可能是中间思考，推回去继续想
                AgentContext nextContext = context.appendHistory(message).nextStep();
                yield Flux.just(
                        AgentSignal.event(new AgentEvent(AgentState.ITERATION_COMPLETE, message.getText(), Map.of())),
                        AgentSignal.next(nextContext)
                );
            }

            case Decision.Stop stop ->
                    Flux.just(AgentSignal.event(new AgentEvent(AgentState.TERMINAL, stop.reason(), Map.of())));

            default -> throw new IllegalStateException("Unexpected value: " + decision);
        };
    }

    private Decision analyzeStepOutput(AssistantMessage message) {
        // 优先检查工具调用
        if (message.hasToolCalls()) {
            // 不管是 finish_task 还是 search_web，统统视为 CallTool
            return new Decision.CallTool(message.getToolCalls());
        }

        // 没有工具调用
        return new Decision.Continue();
    }

    private Mono<ToolExecutionOutcome> executeToolCall(AgentContext context, int stepIndex, ToolCall call) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        ToolRegistration registration = toolRegistry.resolve(call.name());
        ToolExecutionTrace startTrace = new ToolExecutionTrace(
                stepIndex,
                call.id(),
                call.name(),
                resolveToolType(call, registration),
                ToolExecutionStatus.STARTED,
                sanitizeArguments(call.arguments()),
                null,
                null,
                null,
                null,
                startedAt,
                null,
                buildTraceMetadata(call, registration)
        );
        return toolRegistry.execute(call.name(), call.arguments(), context.toToolContext())
                .subscribeOn(this.getScheduler())
                .map(result -> buildSuccessfulOutcome(call, startTrace, result))
                .onErrorResume(error -> Mono.just(buildFailedOutcome(call, startTrace, error)));
    }

    private ToolExecutionOutcome buildSuccessfulOutcome(ToolCall call, ToolExecutionTrace startTrace, String result) {
        OffsetDateTime finishedAt = OffsetDateTime.now();
        String normalizedResult = limitText(result, MAX_TRACE_TEXT_LENGTH);
        ToolExecutionTrace resultTrace = new ToolExecutionTrace(
                startTrace.stepIndex(),
                startTrace.toolCallId(),
                startTrace.toolName(),
                startTrace.toolType(),
                ToolExecutionStatus.SUCCEEDED,
                startTrace.argumentsJson(),
                normalizedResult,
                summarizeResult(call.name(), normalizedResult),
                null,
                durationMillis(startTrace.startedAt(), finishedAt),
                startTrace.startedAt(),
                finishedAt,
                Map.of()
        );
        return new ToolExecutionOutcome(
                new ToolResponse(call.id(), call.name(), normalizedResult == null ? "" : normalizedResult),
                startTrace.toAgentEvent(AgentState.TOOL_START),
                resultTrace.toAgentEvent(AgentState.TOOL_RESULT)
        );
    }

    private ToolExecutionOutcome buildFailedOutcome(ToolCall call, ToolExecutionTrace startTrace, Throwable error) {
        OffsetDateTime finishedAt = OffsetDateTime.now();
        String errorMessage = error == null ? "Tool execution failed" : limitText(error.getMessage(), MAX_TRACE_TEXT_LENGTH);
        ToolExecutionTrace resultTrace = new ToolExecutionTrace(
                startTrace.stepIndex(),
                startTrace.toolCallId(),
                startTrace.toolName(),
                startTrace.toolType(),
                ToolExecutionStatus.FAILED,
                startTrace.argumentsJson(),
                null,
                summarizeFailure(call.name(), errorMessage),
                errorMessage,
                durationMillis(startTrace.startedAt(), finishedAt),
                startTrace.startedAt(),
                finishedAt,
                Map.of()
        );
        String responseData = errorMessage == null ? "" : "Tool execution failed: " + errorMessage;
        return new ToolExecutionOutcome(
                new ToolResponse(call.id(), call.name(), responseData),
                startTrace.toAgentEvent(AgentState.TOOL_START),
                resultTrace.toAgentEvent(AgentState.TOOL_RESULT)
        );
    }

    private String sanitizeArguments(String arguments) {
        return limitText(arguments, MAX_TRACE_TEXT_LENGTH);
    }

    private String summarizeResult(String toolName, String result) {
        if (result == null || result.isBlank()) {
            return toolName + " returned no content";
        }
        return limitText(toolName + ": " + result.replaceAll("\\s+", " ").trim(), MAX_TRACE_SUMMARY_LENGTH);
    }

    private String summarizeFailure(String toolName, String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return toolName + " failed";
        }
        return limitText(toolName + " failed: " + errorMessage.replaceAll("\\s+", " ").trim(), MAX_TRACE_SUMMARY_LENGTH);
    }

    private Long durationMillis(OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, finishedAt).toMillis();
    }

    private String limitText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private String resolveToolType(ToolCall call, ToolRegistration registration) {
        if (registration != null && registration.sourceType() != null && !"unknown".equals(registration.sourceType())) {
            return registration.sourceType();
        }
        return call.type();
    }

    private Map<String, Object> buildTraceMetadata(ToolCall call, ToolRegistration registration) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (!call.type().isBlank()) {
            metadata.put("callType", call.type());
        }
        if (registration != null) {
            metadata.put("toolSource", registration.sourceType());
            if (!registration.metadata().isEmpty()) {
                metadata.putAll(registration.metadata());
            }
        }
        return metadata;
    }

    private record ToolExecutionOutcome(ToolResponse toolResponse, AgentEvent startEvent, AgentEvent resultEvent) {
    }
}
