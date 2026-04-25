package org.example.kbsystemproject.ailearning.application.chat;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.AgentEvent;
import org.example.kbsystemproject.ailearning.domain.AgentState;
import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileContext;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionRequestDecision;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.example.kbsystemproject.ailearning.application.profile.ProfileContextService;
import org.example.kbsystemproject.ailearning.application.session.AsyncTurnPersistenceService;
import org.example.kbsystemproject.ailearning.application.session.SessionRequestConflictException;
import org.example.kbsystemproject.ailearning.application.session.SessionRequestService;
import org.example.kbsystemproject.ailearning.application.session.SessionStorageService;
import org.example.kbsystemproject.ailearning.application.session.SessionStreamService;
import org.example.kbsystemproject.ailearning.infrastructure.ai.orchestration.LearningChatOrchestrator;
import org.example.kbsystemproject.ailearning.infrastructure.ai.prompt.LearningPromptService;
import org.example.kbsystemproject.ailearning.infrastructure.observability.LearningPipelineMonitor;
import org.example.kbsystemproject.ailearning.infrastructure.rag.LearningKnowledgeRetrievalService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import reactor.core.scheduler.Scheduler;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class AiLearningApplicationService {

    private static final int AGENT_VISIBLE_CHUNK_MAX_CHARS = 24;
    private static final String CONTEXT_CONVERSATION_ID = "ai.conversationId";
    private static final String CONTEXT_REQUEST_ID = "ai.requestId";
    private static final String CONTEXT_EXECUTION_MODE = "ai.executionMode";
    private static final Duration AGENT_VISIBLE_CHUNK_MAX_WAIT = Duration.ofMillis(80);
    private final ChatClient chatClient;
    private final VectorStore pgVectorStore;
    private final LearningChatOrchestrator learningChatOrchestrator;
    private final LearningChatRoutingService learningChatRoutingService;
    private final LearningPromptService learningPromptService;
    private final LearningKnowledgeRetrievalService learningKnowledgeRetrievalService;
    private final LearningPipelineMonitor learningPipelineMonitor;
    private final Scheduler retrievalBlockingScheduler;
    private final AsyncTurnPersistenceService asyncTurnPersistenceService;
    private final SessionStorageService sessionStorageService;
    private final SessionStreamService sessionStreamService;
    private final SessionRequestService sessionRequestService;
    private final ProfileContextService profileContextService;
    private final TopicInferenceService topicInferenceService;

    public AiLearningApplicationService(@Qualifier("chatClient") ChatClient chatClient,
                                        VectorStore pgVectorStore,
                                        LearningChatOrchestrator learningChatOrchestrator,
                                        LearningChatRoutingService learningChatRoutingService,
                                        LearningPromptService learningPromptService,
                                        LearningKnowledgeRetrievalService learningKnowledgeRetrievalService,
                                        LearningPipelineMonitor learningPipelineMonitor,
                                        @Qualifier("retrievalBlockingScheduler") Scheduler retrievalBlockingScheduler,
                                        AsyncTurnPersistenceService asyncTurnPersistenceService,
                                        SessionStorageService sessionStorageService,
                                        SessionStreamService sessionStreamService,
                                        SessionRequestService sessionRequestService,
                                        ProfileContextService profileContextService,
                                        TopicInferenceService topicInferenceService) {
        this.chatClient = chatClient;
        this.pgVectorStore = pgVectorStore;
        this.learningChatOrchestrator = learningChatOrchestrator;
        this.learningChatRoutingService = learningChatRoutingService;
        this.learningPromptService = learningPromptService;
        this.learningKnowledgeRetrievalService = learningKnowledgeRetrievalService;
        this.learningPipelineMonitor = learningPipelineMonitor;
        this.retrievalBlockingScheduler = retrievalBlockingScheduler;
        this.asyncTurnPersistenceService = asyncTurnPersistenceService;
        this.sessionStorageService = sessionStorageService;
        this.sessionStreamService = sessionStreamService;
        this.sessionRequestService = sessionRequestService;
        this.profileContextService = profileContextService;
        this.topicInferenceService = topicInferenceService;
    }

    public Flux<String> chat(LearningChatCommand command) {
        return executeCoreChat(command);
    }

    public Mono<Void> startChat(LearningChatCommand command) {
        return Mono.fromRunnable(() -> sessionStreamService.start(command.conversationId(), executeCoreChat(command)));
    }

    public Flux<String> startChatAndStream(LearningChatCommand command) {
        return sessionStreamService.startAndStream(command.conversationId(), executeCoreChat(command));
    }

    public Mono<Void> stopChat(String conversationId) {
        return Mono.fromRunnable(() -> sessionStreamService.stop(conversationId));
    }

    public Flux<String> getSessionLiveStream(String conversationId) {
        return sessionStreamService.stream(conversationId);
    }

    public Flux<String> chatTest(String prompt) {
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
                .subscribeOn(retrievalBlockingScheduler)
                .flatMapMany(Flux::fromIterable);
    }

    public Flux<String> chatWithAgent(LearningChatCommand command) {
        return executeSessionChat(command, false);
    }

    // 默认入口：按当前配置执行会话化问答，当前调试模式下会固定走 DIRECT。
    private Flux<String> executeCoreChat(LearningChatCommand command) {
        return executeSessionChat(command, true);
    }

    // 会话问答总入口：开会话、占请求槽位，并进入上下文加载和回答流程。
    private Flux<String> executeSessionChat(LearningChatCommand command, boolean forceDirectExecution) {
        LearningPipelineMonitor.RequestStageMonitor requestMonitor = learningPipelineMonitor.newRequestMonitor();
        Flux<String> pipeline = learningPipelineMonitor.monitorStageMono(
                "session.open",
                command,
                null,
                requestMonitor,
                sessionStorageService.openSession(command.toSessionOpenCommand())
        ).flatMapMany(ignored -> learningPipelineMonitor.monitorStageMono(
                        "request.acquire",
                        command,
                        null,
                        requestMonitor,
                        sessionRequestService.beginRequest(command.conversationId(), command.requestId())
                ))
                .flatMap(decision -> switch (decision.type()) {
                    case COMPLETED -> Flux.just(decision.record().assistantContent());
                    case PROCESSING -> Flux.error(new SessionRequestConflictException("当前请求正在处理中: " + command.requestId()));
                    case CONVERSATION_BUSY -> Flux.error(new SessionRequestConflictException("当前会话已有进行中的请求，请稍后重试"));
                    case ACQUIRED -> executeAcquiredRequest(command, forceDirectExecution, requestMonitor);
                });
        return learningPipelineMonitor.monitorPipelineExecution(command, requestMonitor, pipeline)
                .contextWrite(buildRequestContext(command));
    }

    // 请求拿到处理权后，先加载短期记忆快照，再路由到具体执行模式。
    private Flux<String> executeAcquiredRequest(LearningChatCommand command,
                                                boolean forceDirectExecution,
                                                LearningPipelineMonitor.RequestStageMonitor requestMonitor) {
        return learningPipelineMonitor.monitorStageMono(
                "snapshot.load",
                command,
                null,
                requestMonitor,
                sessionStorageService.loadSnapshot(command.conversationId(), command.prompt())
        ).flatMapMany(snapshot -> routeWithDecision(command, snapshot, forceDirectExecution, requestMonitor))
                .onErrorResume(error -> sessionRequestService.markFailed(command.conversationId(), command.requestId(), error)
                        .then(Mono.error(error)));
    }

    // 基于快照补全当前主题，并根据显式会话信息生成执行决策。
    private Flux<String> routeWithDecision(LearningChatCommand command,
                                           SessionMemorySnapshot snapshot,
                                           boolean forceDirectExecution,
                                           LearningPipelineMonitor.RequestStageMonitor requestMonitor) {
        LearningChatCommand effectiveCommand = learningPipelineMonitor.monitorSynchronousStage(
                "topic.infer",
                command,
                null,
                requestMonitor,
                () -> command.withCurrentTopic(topicInferenceService.inferTopic(command, snapshot))
        );
        Mono<LearningProfileContext> profileContextMono = learningPipelineMonitor.monitorStageMono(
                "profile.load",
                effectiveCommand,
                null,
                requestMonitor,
                profileContextService.loadContext(snapshot.session())
                        .defaultIfEmpty(LearningProfileContext.EMPTY)
        );
        return learningPipelineMonitor.monitorStageMono(
                "route.resolve",
                effectiveCommand,
                null,
                requestMonitor,
                learningChatRoutingService.route(effectiveCommand, snapshot, forceDirectExecution)
        )
                .doOnNext(requestMonitor::recordDecision)
                .flatMapMany(decision -> profileContextMono.flatMapMany(profileContext ->
                        learningPipelineMonitor.monitorStageMono(
                                "knowledge.retrieve",
                                effectiveCommand,
                                decision,
                                requestMonitor,
                                learningKnowledgeRetrievalService.retrieveKnowledge(effectiveCommand, decision, snapshot)
                        )
                                .flatMapMany(retrievedDocs -> switch (decision.executionMode()) {
                                    case DIRECT -> runDirectWithSession(effectiveCommand, snapshot, decision, retrievedDocs, profileContext, requestMonitor);
                                    case AGENT -> runAgentWithSession(effectiveCommand, snapshot, decision, retrievedDocs, profileContext, requestMonitor);
                                })
                                .contextWrite(context -> context.put(CONTEXT_EXECUTION_MODE, decision.executionMode().name()))));
    }

    private Flux<String> runDirectWithSession(LearningChatCommand command,
                                              SessionMemorySnapshot snapshot,
                                              IntentDecision decision,
                                              List<Document> retrievedDocs,
                                              LearningProfileContext profileContext,
                                              LearningPipelineMonitor.RequestStageMonitor requestMonitor) {
        ConversationTurn userTurn = new ConversationTurn(
                SessionMessageRole.USER,
                command.prompt(),
                OffsetDateTime.now(),
                learningPromptService.buildUserTurnMetadata(command, decision)
        );
        StringBuilder assistantContent = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);

        // 先把内容持续流给前端，流结束后再把完整的一轮对话落库。
        Flux<String> generatedStream = learningPipelineMonitor.monitorVisibleStreamStage(
                        "response.generate",
                        command,
                        decision,
                        requestMonitor,
                        learningChatOrchestrator.streamDirect(
                                learningPromptService.buildDirectMessages(command, snapshot, decision, retrievedDocs, profileContext)
                        )
                .doOnNext(content -> {
                    if (content != null) {
                        assistantContent.append(content);
                    }
                })
                .filter(content -> content != null && !content.isBlank()));

        Flux<String> directStream = generatedStream.concatWith(Mono.defer(() -> persistTurn(
                                command,
                                userTurn,
                                assistantContent.toString(),
                                snapshot,
                                decision,
                                retrievedDocs,
                                requestMonitor
                        ))
                        .doOnSuccess(ignored -> persisted.set(true)))
                .doFinally(signalType -> releaseIfCanceled(command, persisted, signalType));

        return learningPipelineMonitor.monitorResponseStream("direct", command, decision, directStream);
    }

    private Flux<String> runAgentWithSession(LearningChatCommand command,
                                             SessionMemorySnapshot snapshot,
                                             IntentDecision decision,
                                             List<Document> retrievedDocs,
                                             LearningProfileContext profileContext,
                                             LearningPipelineMonitor.RequestStageMonitor requestMonitor) {
        ConversationTurn userTurn = new ConversationTurn(
                SessionMessageRole.USER,
                command.prompt(),
                OffsetDateTime.now(),
                learningPromptService.buildUserTurnMetadata(command, decision)
        );
        AtomicReference<String> finalAssistantContent = new AtomicReference<>("");
        StringBuilder tokenAssistantContent = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);

        Flux<String> generatedStream = learningPipelineMonitor.monitorVisibleStreamStage(
                        "response.generate",
                        command,
                        decision,
                        requestMonitor,
                        learningChatOrchestrator.streamAgent(
                        learningPromptService.buildAgentHistory(command, snapshot, decision, retrievedDocs, profileContext),
                        command.prompt(),
                        learningPromptService.buildAgentBusinessContext(command, snapshot, decision, retrievedDocs, profileContext)
                )
                .doOnNext(event -> collectAssistantContent(event.state(), event.content(), finalAssistantContent, tokenAssistantContent))
                .doOnNext(event -> log.info("Agent Event: {}", event))
                .flatMap(event -> {
                    String content = toUserVisibleAgentContent(event, !tokenAssistantContent.isEmpty());
                    if (content != null && !content.isBlank()) {
                        return Mono.just(content);
                    }
                    return Mono.empty();
                })
                .transform(this::coalesceVisibleAgentChunks));

        Flux<String> agentStream = generatedStream.concatWith(Mono.defer(() -> persistTurn(
                                command,
                                userTurn,
                                resolveAssistantContent(finalAssistantContent.get(), tokenAssistantContent.toString()),
                                snapshot,
                                decision,
                                retrievedDocs,
                                requestMonitor
                        ))
                        .doOnSuccess(ignored -> persisted.set(true)))
                .doFinally(signalType -> releaseIfCanceled(command, persisted, signalType));

        return learningPipelineMonitor.monitorResponseStream("agent", command, decision, agentStream);
    }

    private Flux<String> coalesceVisibleAgentChunks(Flux<String> chunks) {
        return Flux.create(sink -> {
            Object monitor = new Object();
            StringBuilder buffer = new StringBuilder();
            Scheduler.Worker worker = Schedulers.parallel().createWorker();
            AtomicReference<reactor.core.Disposable> scheduledFlush = new AtomicReference<>();

            Runnable cancelScheduledFlush = () -> {
                reactor.core.Disposable disposable = scheduledFlush.getAndSet(null);
                if (disposable != null) {
                    disposable.dispose();
                }
            };

            Runnable flushBuffer = () -> {
                String combined = null;
                synchronized (monitor) {
                    if (buffer.isEmpty()) {
                        return;
                    }
                    combined = buffer.toString();
                    buffer.setLength(0);
                }
                cancelScheduledFlush.run();
                sink.next(combined);
            };

            reactor.core.Disposable upstream = chunks.subscribe(
                    chunk -> {
                        if (chunk == null || chunk.isBlank()) {
                            return;
                        }
                        boolean flushNow = false;
                        synchronized (monitor) {
                            buffer.append(chunk);
                            if (shouldFlushVisibleAgentChunk(buffer, chunk)) {
                                flushNow = true;
                            } else if (scheduledFlush.get() == null) {
                                reactor.core.Disposable scheduled = worker.schedule(
                                        flushBuffer,
                                        AGENT_VISIBLE_CHUNK_MAX_WAIT.toMillis(),
                                        TimeUnit.MILLISECONDS
                                );
                                if (!scheduledFlush.compareAndSet(null, scheduled)) {
                                    scheduled.dispose();
                                }
                            }
                        }
                        if (flushNow) {
                            flushBuffer.run();
                        }
                    },
                    error -> {
                        cancelScheduledFlush.run();
                        sink.error(error);
                    },
                    () -> {
                        flushBuffer.run();
                        sink.complete();
                    }
            );

            sink.onDispose(() -> {
                cancelScheduledFlush.run();
                upstream.dispose();
                worker.dispose();
            });
        });
    }

    private boolean shouldFlushVisibleAgentChunk(StringBuilder buffer, String latestChunk) {
        return buffer.length() >= AGENT_VISIBLE_CHUNK_MAX_CHARS || containsChunkBoundary(latestChunk);
    }

    private boolean containsChunkBoundary(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (switch (ch) {
                case '\n', '。', '！', '？', '；', '：', '，', '.', '!', '?', ';' -> true;
                default -> false;
            }) {
                return true;
            }
        }
        return false;
    }

    private void releaseIfCanceled(LearningChatCommand command, AtomicBoolean persisted, SignalType signalType) {
        if (signalType != SignalType.CANCEL || persisted.get()) {
            return;
        }
        sessionRequestService.markFailed(
                        command.conversationId(),
                        command.requestId(),
                        new CancellationException("client canceled request before persistence")
                )
                .subscribe(
                        ignored -> {
                        },
                        error -> log.warn("Failed to release canceled request. conversationId={}, requestId={}",
                                command.conversationId(),
                                command.requestId(),
                                error)
                );
    }

    // 把本轮 user/assistant 消息交给异步持久化服务，实时流结束后只做入队，不阻塞数据库事务。
    private Mono<String> persistTurn(LearningChatCommand command,
                                     ConversationTurn userTurn,
                                     String assistantContent,
                                     SessionMemorySnapshot snapshot,
                                     IntentDecision decision,
                                     List<Document> retrievedDocs,
                                     LearningPipelineMonitor.RequestStageMonitor requestMonitor) {
        String normalizedAssistantContent = assistantContent == null ? "" : assistantContent.trim();
        if (normalizedAssistantContent.isEmpty()) {
            return Mono.error(new IllegalStateException("Assistant content is empty"));
        }

        ConversationTurn assistantTurn = new ConversationTurn(
                SessionMessageRole.ASSISTANT,
                normalizedAssistantContent,
                OffsetDateTime.now(),
                learningPromptService.buildAssistantTurnMetadata(command, decision)
        );

        return learningPipelineMonitor.monitorStageMono(
                        "turn.persist",
                        command,
                        decision,
                        requestMonitor,
                        asyncTurnPersistenceService.enqueue(
                                command.conversationId(),
                                command.requestId(),
                                new SessionTurnPair(userTurn, assistantTurn),
                                command.currentTopic(),
                                learningPromptService.buildSessionMetadata(command, snapshot, decision, retrievedDocs)
                        )
                )
                .then(Mono.empty());
    }

    private void collectAssistantContent(AgentState state,
                                         String content,
                                         AtomicReference<String> finalAssistantContent,
                                         StringBuilder tokenAssistantContent) {
        if (content == null || content.isBlank()) {
            return;
        }
        switch (state) {
            case FINISHED -> finalAssistantContent.set(content);
            case TOKEN -> tokenAssistantContent.append(content);
            default -> {
            }
        }
    }

    private String toUserVisibleAgentContent(AgentEvent event, boolean tokenAlreadyProduced) {
        if (event == null || event.content() == null || event.content().isBlank()) {
            return null;
        }
        if (event.state() == AgentState.TOKEN) {
            return event.content();
        }
        if (event.state() == AgentState.FINISHED && !tokenAlreadyProduced) {
            return event.content();
        }
        return null;
    }

    private String resolveAssistantContent(String finalAssistantContent, String tokenAssistantContent) {
        if (finalAssistantContent != null && !finalAssistantContent.isBlank()) {
            return finalAssistantContent;
        }
        return tokenAssistantContent == null ? "" : tokenAssistantContent.trim();
    }

    private Context buildRequestContext(LearningChatCommand command) {
        return Context.of(
                CONTEXT_CONVERSATION_ID, command.conversationId(),
                CONTEXT_REQUEST_ID, command.requestId(),
                CONTEXT_EXECUTION_MODE, "pending"
        );
    }
}

