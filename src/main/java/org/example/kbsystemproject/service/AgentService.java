package org.example.kbsystemproject.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.base.ai.agent.ReActAgent;
import org.example.kbsystemproject.service.component.RedisShortTermMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
//import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentService {

    private static final int MAX_CONTEXT_DOCS = 4;

    private final ChatClient chatClient;
    private final VectorStore pgVectorStore;
    private ReActAgent reActAgent;
    private ConversationMemoryService memoryService;
    private final RedisShortTermMemory redisMemory;
    private final ConversationService stateService;
    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    // 使用 Map 管理每个会话的 Sink，避免全局混合和内存泄漏
    private final Map<String, Sinks.Many<String>> sessionSinks = new ConcurrentHashMap<>();

    public AgentService(@Qualifier("chatClient") ChatClient chatClient,
                        VectorStore pgVectorStore, ReActAgent reActAgent, ConversationMemoryService memoryService, RedisShortTermMemory redisMemory, ConversationService stateService, org.springframework.ai.embedding.EmbeddingModel embeddingModel) {
        this.chatClient = chatClient;
        this.pgVectorStore = pgVectorStore;
        this.reActAgent = reActAgent;
        this.memoryService = memoryService;
        this.redisMemory = redisMemory;
        this.stateService = stateService;
        this.embeddingModel = embeddingModel;
    }

    public Flux<String> chat(String prompt) {
        return chatClient.prompt(prompt)
                .system("你是一位严谨的中国资深商业律师，擅长合同审查与法律风险分析。\n" +
                        "\n" +
                        "Task\n" +
                        "请根据用户提出的【问题】，严格基于下方提供的【检索文本】进行解答。\n" +
                        "\n" +
                        "Constraints (核心约束)\n" +
                        "【绝对忠实】：仅使用【检索文本】中的信息。绝不允许编造条款、法条或进行主观推断。\n" +
                        "【未知即拒】：如果文本信息不足以完整回答问题，必须明确指出缺失的是哪个要件，并声明“无法得出确切结论”。\n" +
                        "【强制引征】：结论后必须用括标注来源，格式为：（来源：《文件名》第X条/第X款）。\n" +
                        "【定义优先】：遇到“甲方/乙方/标的物”等简称，必须先还原为其在合同中的全称定义，再进行说理。\n" +
                        "【法言法语】：措辞需专业、准确，避免口语化和情绪化表达。\n" +
                        "Format\n" +
                        "请按以下结构输出：\n" +
                        "一、 结论明确（直接回答是/否/具体责任/风险点）\n" +
                        "二、 条款拆解（列出支撑结论的具体条款原文或转述）\n" +
                        "三、 逻辑推演（将事实代入条款条件进行分析，体现A->B的推导过程）\n" +
                        "四、 风险提示/缺失说明（如有未查明的事实或潜在漏洞）\n" +
                        "\n" +
                        "Context\n" +
                        "【检索文本】：\n" +
                        "{这里插入RAG系统检索出来的、带有元数据头部的分割文本块}\n" +
                        "\n" +
                        "【问题】：\n" +
                        "{用户的具体问题，例如：如果乙方延迟交货超过10天，甲方能否直接解除合同并要求赔偿？}")
                .stream()
                .content();
    }

    public Flux<String> chatWithVectorStore(String prompt) {
        return chatClient.prompt(prompt)
                .stream()
                .content();
    }

    public Flux<Document> searchSimilarity(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(10)
                .build();
        return Mono.fromCallable(() -> pgVectorStore.similaritySearch(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    private void executeAsyncOutbound(String convId, Message userMessage, Message assistantMessage) {
        // 边界提取：仅在与外部 Redis/JDBC 交互时提取 String
        Map<String, String> userMap = Map.of("role", "user", "content", userMessage.getText());
        Map<String, String> assistMap = Map.of("role", "assistant", "content", assistantMessage.getText());

        redisMemory.addTurnAtomically(convId, userMessage, assistantMessage).subscribe();

        stateService.incrementTurn(convId).subscribe();
        stateService.updateLastActiveAt(convId).subscribe();

        // 异步归档
        memoryService.archiveRawMessage(convId, userMessage.getText(), embeddingModel.embed(userMessage.getText()), null).subscribe();
        Mono.fromCallable(() -> embeddingModel.embed(assistantMessage.getText()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(emb -> memoryService.archiveRawMessage(convId, assistantMessage.getText(), emb, null).subscribe());
    }

    /**
     * 异步启动 Agent 任务并多播到指定会话。
     * 因为使用了独立的 subscribe() 并且在独立调度器上运行，
     * 所以完全与外部请求（Watch 连接）的生命周期解耦，不管外部连接是否断开，它都能持续生成并写入 Sink，防止任务被意外中断。
     */
    public void startAgentChatBackground(String sessionId, String prompt) {
        Sinks.Many<String> sink = sessionSinks.computeIfAbsent(sessionId, id ->
                // 使用 replay().limit(100) 可以在有新客户端断线重连（如刷新页面）时，回放最近的 100 个生成的 Token 数据
                Sinks.many().replay().limit(100)
        );

        redisMemory.getRecent(sessionId)
            .defaultIfEmpty(List.of())
            .flatMapMany(history -> reActAgent.run(history, prompt))
            // 确保在专属定制的后台线程池上执行 (下沉到 reActAgent 内聚管理)，防占用默认线程
            .subscribeOn(reActAgent.getScheduler())
            .subscribe(
                    // onNext
                    agentEvent -> {
                        log.info("Agent Event: {}", agentEvent);
                        if (agentEvent.content() != null) {
                            sink.tryEmitNext(agentEvent.content());
                        }
                    },
                    // onError
                    error -> {
                        log.error("Agent execution failed for session: {}", sessionId, error);
                        // 错误时建议关闭流
                        sink.tryEmitComplete();
                        sessionSinks.remove(sessionId, sink);
                    },
                    // onComplete
                    () -> {
                        log.info("Agent execution completed for session: {}", sessionId);
                        sink.tryEmitComplete();
                        sessionSinks.remove(sessionId, sink);
                    }
            );
    }

    public Flux<String> chatWithAgent(String prompt) {
        return reActAgent.run(prompt)
                .map(agentEvent -> {
                    log.info("Agent Event: {}", agentEvent);
                    return agentEvent.content();
                });
    }

    /**
     * 获取指定会话的一对多实时响应流
     * @param sessionId 会话 ID
     */
    public Flux<String> getSessionLiveStream(String sessionId) {
        return Flux.defer(() -> {
            Sinks.Many<String> sink = sessionSinks.computeIfAbsent(sessionId, id ->
                    Sinks.many().replay().limit(100)
            );
            return sink.asFlux()
                    .doFinally(signalType -> {
                        // 当有多处 watch 时，某一个断开(CANCEL信号)不应终止并清除该 Sink
                        // 只有所有监听者都断开(值为0)，或者是主动发出了 Complete 信号时才清理 Map 以防止内存泄漏
                        if (sink.currentSubscriberCount() == 0 || signalType != reactor.core.publisher.SignalType.CANCEL) {
                            log.info("Session stream {} ended (signal: {}), no valid subscribers left. Cleaning up sink.", sessionId, signalType);
                            sessionSinks.remove(sessionId, sink);
                        }
                    });
        });
    }

    /**
     * 向指定会话多播消息
     */
    public void emitToSession(String sessionId, String content) {
        Sinks.Many<String> sink = sessionSinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitNext(content);
        }
    }

    /**
     * 结束指定会话的流
     */
    public void completeSessionStream(String sessionId) {
        Sinks.Many<String> sink = sessionSinks.get(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
            sessionSinks.remove(sessionId);
        }
    }

}