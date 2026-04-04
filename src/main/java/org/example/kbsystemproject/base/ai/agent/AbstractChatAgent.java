package org.example.kbsystemproject.base.ai.agent;

import org.example.kbsystemproject.base.ai.agent.tool.StreamingAssistantMessageAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractChatAgent extends BaseAgent {

    private final Logger log = LoggerFactory.getLogger(AbstractChatAgent.class);

    protected abstract ChatClient getChatClient();

    @Override
    protected Flux<AgentSignal> executeStep(AgentContext context) {
        List<Message> messages = context.history();

        // 辅助变量：用于累积流式数据
        StreamingAssistantMessageAggregator aggregator = new StreamingAssistantMessageAggregator();

        Flux<ChatResponse> rawStream = getChatClient().prompt()
                .messages(context.history().toArray(new Message[0]))
                .stream()
                .chatResponse();
        return rawStream
                .map(chatResponse -> {
                    AssistantMessage deltaMessage = chatResponse.getResult().getOutput();

                    // 1. 喂数据给聚合器 (这是副作用，但不影响主流)
                    aggregator.accumulate(deltaMessage);

                    // 2. 提取增量返回给前端
                    String deltaText = deltaMessage.getText();
                    return deltaText == null || deltaText.isEmpty() ? null :
                            AgentSignal.event(AgentEvent.token(deltaText));
                })
                .filter(Objects::nonNull)

                // --- 分支流：流结束后的最终处理 ---
                .concatWith(Flux.defer(() -> {
                    // 此时 ToolCall 的 JSON 碎片已经完美拼装完毕
                    AssistantMessage finalMessage = aggregator.buildFinalMessage();

                    if (finalMessage.getText() != null) {
                        System.out.println("最终聚合完成，Content长度: " + finalMessage.getText().length());
                    }
                    System.out.println("最终聚合完成，ToolCalls数量: " + finalMessage.getToolCalls().size());
                    // 如果有 ToolCall，你可以直接拿到完整合法的 JSON：finalMessage.getToolCalls().get(0).arguments()

                    return handleThinkingResult(context, finalMessage)
                            .onErrorResume(err -> {
                                // 绝对隔离：后续处理报错不能影响前面的 doOnComplete
                                log.error("后台处理最终消息失败", err);
                                return Mono.empty();
                            });
                }))

                // --- 兜底流：处理客户端提前断开 ---
                .doFinally(signalType -> {
                    if (signalType == SignalType.CANCEL) {
                        AssistantMessage partialMessage = aggregator.buildFinalMessage();
                        log.warn("客户端断开连接，已生成部分内容长度: {}", partialMessage.getText().length());
                        // 可选：处理半截消息的逻辑
                    }
                });
    }

    /**
     * 子类实现：处理思考结果
     * 这里的职责是：根据 LLM 的输出，决定是调用工具、继续还是结束。
     */
    protected abstract Flux<AgentSignal> handleThinkingResult(AgentContext context, AssistantMessage message);

}
