package org.example.kbsystemproject.ailearning.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.AgentEvent;
import org.example.kbsystemproject.ailearning.domain.AgentState;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.example.kbsystemproject.ailearning.retrieval.Bm25SearchService;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionStatus;
import org.example.kbsystemproject.ailearning.domain.session.LongTermMemoryEntry;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionRequestDecision;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.util.context.Context;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class AiLearningApplicationService {

    private static final int RAG_RETRIEVAL_TOP_K = 8;
    private static final int RAG_MAX_CONTEXT_DOCS = 4;
    private static final int RAG_MAX_DOC_CONTENT_LENGTH = 500;
    private static final int RAG_RRF_K = 60;
    private static final String CONTEXT_CONVERSATION_ID = "ai.conversationId";
    private static final String CONTEXT_REQUEST_ID = "ai.requestId";
    private static final String CONTEXT_EXECUTION_MODE = "ai.executionMode";
    private static final String LEARNING_ASSISTANT_SYSTEM_PROMPT = """
            你是学习助手。
            回答时优先基于当前会话上下文、历史对话和长期记忆。
            信息不足时直接说明缺失点，不要编造。
            输出应服务于学习场景，尽量给出结论、步骤、易错点和总结。
            """;

    private final ChatClient chatClient;
    private final VectorStore pgVectorStore;
    private final LearningChatOrchestrator learningChatOrchestrator;
    private final IntentRecognitionService intentRecognitionService;
    private final QueryEnhancementService queryEnhancementService;
    private final Bm25SearchService bm25SearchService;
    private final MeterRegistry meterRegistry;
    private final Scheduler retrievalBlockingScheduler;
    private final SessionStorageService sessionStorageService;
    private final SessionStreamService sessionStreamService;
    private final SessionRequestService sessionRequestService;

    public AiLearningApplicationService(@Qualifier("chatClient") ChatClient chatClient,
                                        VectorStore pgVectorStore,
                                        LearningChatOrchestrator learningChatOrchestrator,
                                        IntentRecognitionService intentRecognitionService,
                                        QueryEnhancementService queryEnhancementService,
                                        Bm25SearchService bm25SearchService,
                                        MeterRegistry meterRegistry,
                                        @Qualifier("retrievalBlockingScheduler") Scheduler retrievalBlockingScheduler,
                                        SessionStorageService sessionStorageService,
                                        SessionStreamService sessionStreamService,
                                        SessionRequestService sessionRequestService) {
        this.chatClient = chatClient;
        this.pgVectorStore = pgVectorStore;
        this.learningChatOrchestrator = learningChatOrchestrator;
        this.intentRecognitionService = intentRecognitionService;
        this.queryEnhancementService = queryEnhancementService;
        this.bm25SearchService = bm25SearchService;
        this.meterRegistry = meterRegistry;
        this.retrievalBlockingScheduler = retrievalBlockingScheduler;
        this.sessionStorageService = sessionStorageService;
        this.sessionStreamService = sessionStreamService;
        this.sessionRequestService = sessionRequestService;
    }

    public Flux<String> chat(LearningChatCommand command) {
        return chatWithAgent(command);
    }

    public Mono<Void> startChat(LearningChatCommand command) {
        return Mono.fromRunnable(() -> sessionStreamService.start(command.conversationId(), chatWithAgent(command)));
    }

    public Mono<Void> stopChat(String conversationId) {
        return Mono.fromRunnable(() -> sessionStreamService.stop(conversationId));
    }

    public Flux<String> getSessionLiveStream(String conversationId) {
        return sessionStreamService.stream(conversationId);
    }

    public Flux<String> chat(String prompt) {
        return chatClient.prompt()
                .system(LEARNING_ASSISTANT_SYSTEM_PROMPT)
                .user(prompt)
                .stream()
                .content();
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

    public Flux<String> chatWithAgent(String prompt) {
        return learningChatOrchestrator.streamAgent(List.of(), prompt, Map.of())
                .flatMap(event -> {
                    if (event.state() == AgentState.TOKEN && event.content() != null && !event.content().isBlank()) {
                        return Mono.just(event.content());
                    }
                    return Mono.empty();
                });
    }

    public Flux<String> chatWithAgent(LearningChatCommand command) {
        return sessionStorageService.openSession(command.toSessionOpenCommand())
                .flatMapMany(ignored -> sessionRequestService.beginRequest(command.conversationId(), command.requestId()))
                .flatMap(decision -> switch (decision.type()) {
                    case COMPLETED -> Flux.just(decision.record().assistantContent());
                    case PROCESSING -> Flux.error(new SessionRequestConflictException("当前请求正在处理中: " + command.requestId()));
                    case CONVERSATION_BUSY -> Flux.error(new SessionRequestConflictException("当前会话已有进行中的请求，请稍后重试"));
                    case ACQUIRED -> executeAcquiredRequest(command);
                })
                .contextWrite(buildRequestContext(command));
    }

    private Flux<String> executeAcquiredRequest(LearningChatCommand command) {
        return sessionStorageService.loadSnapshot(command.conversationId(), command.prompt())
                .flatMapMany(snapshot -> routeWithIntent(command, snapshot))
                .onErrorResume(error -> sessionRequestService.markFailed(command.conversationId(), command.requestId(), error)
                        .then(Mono.error(error)));
    }

    private Flux<String> routeWithIntent(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        return intentRecognitionService.recognize(command, snapshot)
                .flatMapMany(decision -> retrieveKnowledge(command, decision, snapshot)
                        .flatMapMany(retrievedDocs -> switch (decision.executionMode()) {
                            case DIRECT -> runDirectWithSession(command, snapshot, decision, retrievedDocs);
                            case AGENT -> runAgentWithSession(command, snapshot, decision, retrievedDocs);
                        })
                        .contextWrite(context -> context.put(CONTEXT_EXECUTION_MODE, decision.executionMode().name())));
    }

    private Flux<String> runDirectWithSession(LearningChatCommand command,
                                              SessionMemorySnapshot snapshot,
                                              IntentDecision decision,
                                              List<Document> retrievedDocs) {
        ConversationTurn userTurn = new ConversationTurn(
                SessionMessageRole.USER,
                command.prompt(),
                OffsetDateTime.now(),
                buildUserTurnMetadata(command, decision)
        );
        StringBuilder assistantContent = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);

        Flux<String> directStream = learningChatOrchestrator.streamDirect(buildDirectMessages(command, snapshot, decision, retrievedDocs))
                .doOnNext(content -> {
                    if (content != null) {
                        assistantContent.append(content);
                    }
                })
                .filter(content -> content != null && !content.isBlank())
                .concatWith(Mono.defer(() -> persistTurn(
                                command,
                                userTurn,
                                assistantContent.toString(),
                                snapshot,
                                decision,
                                retrievedDocs
                        ))
                        .doOnSuccess(ignored -> persisted.set(true)))
                .doFinally(signalType -> releaseIfCanceled(command, persisted, signalType));

        return monitorResponseStream("direct", command, decision, directStream);
    }

    private Flux<String> runAgentWithSession(LearningChatCommand command,
                                             SessionMemorySnapshot snapshot,
                                             IntentDecision decision,
                                             List<Document> retrievedDocs) {
        ConversationTurn userTurn = new ConversationTurn(
                SessionMessageRole.USER,
                command.prompt(),
                OffsetDateTime.now(),
                buildUserTurnMetadata(command, decision)
        );
        AtomicReference<String> finalAssistantContent = new AtomicReference<>("");
        StringBuilder tokenAssistantContent = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);

        Flux<String> agentStream = learningChatOrchestrator.streamAgent(
                        buildAgentHistory(command, snapshot, decision, retrievedDocs),
                        command.prompt(),
                        buildAgentBusinessContext(command, snapshot, decision, retrievedDocs)
                )
                .doOnNext(event -> collectAssistantContent(event.state(), event.content(), finalAssistantContent, tokenAssistantContent))
                .doOnNext(event -> log.info("Agent Event: {}", event))
                .flatMap(event -> {
                    String content = toUserVisibleAgentContent(event, tokenAssistantContent.length() > 0);
                    if (content != null && !content.isBlank()) {
                        return Mono.just(content);
                    }
                    return Mono.empty();
                })
                .concatWith(Mono.defer(() -> persistTurn(
                                command,
                                userTurn,
                                resolveAssistantContent(finalAssistantContent.get(), tokenAssistantContent.toString()),
                                snapshot,
                                decision,
                                retrievedDocs
                        ))
                        .doOnSuccess(ignored -> persisted.set(true)))
                .doFinally(signalType -> releaseIfCanceled(command, persisted, signalType));

        return monitorResponseStream("agent", command, decision, agentStream);
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

    private Mono<String> persistTurn(LearningChatCommand command,
                                     ConversationTurn userTurn,
                                     String assistantContent,
                                     SessionMemorySnapshot snapshot,
                                     IntentDecision decision,
                                     List<Document> retrievedDocs) {
        String normalizedAssistantContent = assistantContent == null ? "" : assistantContent.trim();
        if (normalizedAssistantContent.isEmpty()) {
            return Mono.error(new IllegalStateException("Assistant content is empty"));
        }

        ConversationTurn assistantTurn = new ConversationTurn(
                SessionMessageRole.ASSISTANT,
                normalizedAssistantContent,
                OffsetDateTime.now(),
                buildAssistantTurnMetadata(command, decision)
        );

        return sessionStorageService.appendTurn(
                        command.conversationId(),
                        command.requestId(),
                        new SessionTurnPair(userTurn, assistantTurn),
                        command.currentTopic(),
                        buildSessionMetadata(command, snapshot, decision, retrievedDocs)
                )
                .then(Mono.empty());
    }

    private List<Message> buildAgentHistory(LearningChatCommand command,
                                            SessionMemorySnapshot snapshot,
                                            IntentDecision decision,
                                            List<Document> retrievedDocs) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(buildSystemPrompt(command, snapshot, decision, retrievedDocs)));
        for (ConversationTurn turn : snapshot.shortTermMemory()) {
            history.add(toSpringMessage(turn));
        }
        return history;
    }

    private List<Message> buildDirectMessages(LearningChatCommand command,
                                              SessionMemorySnapshot snapshot,
                                              IntentDecision decision,
                                              List<Document> retrievedDocs) {
        List<Message> messages = new ArrayList<>(buildAgentHistory(command, snapshot, decision, retrievedDocs));
        messages.add(new UserMessage(command.prompt()));
        return messages;
    }

    private Map<String, Object> buildAgentBusinessContext(LearningChatCommand command,
                                                          SessionMemorySnapshot snapshot,
                                                          IntentDecision decision,
                                                          List<Document> retrievedDocs) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("conversationId", command.conversationId());
        context.put("requestId", command.requestId());
        context.put("userId", command.userId());
        context.put("subject", command.subject());
        context.put("sessionType", decision.resolvedSessionType().name());
        context.put("learningGoal", command.learningGoal());
        context.put("currentTopic", command.currentTopic());
        context.put("turnCount", snapshot.session().turnCount());
        context.put("intentType", decision.intentType().name());
        context.put("executionMode", decision.executionMode().name());
        context.put("longTermMemory", snapshot.longTermMemory().stream()
                .map(LongTermMemoryEntry::content)
                .toList());
        context.put("retrievedDocCount", retrievedDocs.size());
        context.put("retrievedKnowledge", retrievedDocs.stream()
                .map(this::formatDocumentForContext)
                .toList());
        return context;
    }

    private Message toSpringMessage(ConversationTurn turn) {
        return switch (turn.role()) {
            case USER -> new UserMessage(turn.content());
            case ASSISTANT -> new AssistantMessage(turn.content());
            case SYSTEM -> new SystemMessage(turn.content());
        };
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

    private String buildSystemPrompt(LearningChatCommand command,
                                     SessionMemorySnapshot snapshot,
                                     IntentDecision decision,
                                     List<Document> retrievedDocs) {
        StringBuilder builder = new StringBuilder(LEARNING_ASSISTANT_SYSTEM_PROMPT);
        builder.append("\n当前会话信息:\n");
        builder.append("- conversationId: ").append(command.conversationId()).append('\n');
        builder.append("- requestId: ").append(command.requestId()).append('\n');
        builder.append("- userId: ").append(defaultText(command.userId())).append('\n');
        builder.append("- subject: ").append(defaultText(command.subject())).append('\n');
        builder.append("- sessionType: ").append(decision.resolvedSessionType().name()).append('\n');
        builder.append("- intentType: ").append(decision.intentType().name()).append('\n');
        builder.append("- executionMode: ").append(decision.executionMode().name()).append('\n');
        builder.append("- learningGoal: ").append(defaultText(command.learningGoal())).append('\n');
        builder.append("- currentTopic: ").append(defaultText(command.currentTopic())).append('\n');
        builder.append("- status: ").append(snapshot.session().status() == null
                ? LearningSessionStatus.ACTIVE.name()
                : snapshot.session().status().name()).append('\n');
        builder.append("\n任务要求:\n");
        builder.append(buildIntentInstruction(decision));
        builder.append("\n近期对话:\n");
        builder.append(formatShortTermMemory(snapshot.shortTermMemory()));
        builder.append("\n长期记忆:\n");
        builder.append(formatLongTermMemory(snapshot.longTermMemory()));
        builder.append("\n检索资料:\n");
        builder.append(formatRetrievedKnowledge(retrievedDocs, decision.needRetrieval()));
        return builder.toString();
    }

    private String buildIntentInstruction(IntentDecision decision) {
        return switch (decision.intentType()) {
            case GENERAL_QA -> "- 当前任务是学习问答，先解释概念，再给结论。\n";
            case QUESTION_EXPLANATION -> "- 当前任务是题目讲解，请输出解题思路、步骤、易错点和简短总结。\n";
            case GENERATE_EXERCISE -> "- 当前任务是生成练习题，请优先围绕当前主题组织题目与答案解析。\n";
            case WRONG_QUESTION_ANALYSIS -> "- 当前任务是错题分析，请指出错误原因、正确思路和改进建议。\n";
            case REVIEW_SUMMARY -> "- 当前任务是复习总结，请提炼重点、易错点和复习顺序。\n";
            case STUDY_PLAN -> "- 当前任务是学习规划，请给出分阶段目标、执行步骤和检查点。\n";
            case FOLLOW_UP -> "- 当前任务是追问补充，请延续上文，不要重复无关内容。\n";
            case OTHER -> "- 当前任务未明确，请保持教学型回答风格。\n";
        };
    }

    private String formatShortTermMemory(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "无\n";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : turns) {
            builder.append("- ")
                    .append(turn.role().name())
                    .append(": ")
                    .append(turn.content())
                    .append('\n');
        }
        return builder.toString();
    }

    private String formatLongTermMemory(List<LongTermMemoryEntry> memories) {
        if (memories == null || memories.isEmpty()) {
            return "无\n";
        }
        StringBuilder builder = new StringBuilder();
        for (LongTermMemoryEntry memory : memories) {
            builder.append("- [")
                    .append(memory.memoryType())
                    .append("] ")
                    .append(memory.content())
                    .append('\n');
        }
        return builder.toString();
    }

    private Map<String, Object> buildSessionMetadata(LearningChatCommand command,
                                                     SessionMemorySnapshot snapshot,
                                                     IntentDecision decision,
                                                     List<Document> retrievedDocs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", command.requestId());
        metadata.put("subject", defaultText(command.subject()));
        metadata.put("sessionType", decision.resolvedSessionType().name());
        metadata.put("currentTopic", defaultText(command.currentTopic()));
        metadata.put("sessionStatus", snapshot.session().status() == null
                ? LearningSessionStatus.ACTIVE.name()
                : snapshot.session().status().name());
        metadata.put("retrievedDocCount", retrievedDocs.size());
        if (!retrievedDocs.isEmpty()) {
            metadata.put("retrievedSources", retrievedDocs.stream()
                    .map(this::formatDocumentSource)
                    .toList());
        }
        metadata.putAll(decision.toMetadata());
        return metadata;
    }

    private Mono<List<Document>> retrieveKnowledge(LearningChatCommand command,
                                                   IntentDecision decision,
                                                   SessionMemorySnapshot snapshot) {
        if (!decision.needRetrieval()) {
            recordRetrievalMetrics(decision, "skipped", 0, 0L);
            return Mono.just(List.of());
        }
        return Mono.defer(() -> {
            long startNanos = System.nanoTime();
            return queryEnhancementService.enhance(command, decision, snapshot)
                    .flatMap(plan -> Mono.zip(
                                    searchVectorQueries(plan.retrievalQueries()),
                                    searchBm25Queries(buildBm25Queries(plan))
                            )
                            .map(tuple -> {
                                List<QuerySearchResult> allResults = new ArrayList<>(tuple.getT1());
                                allResults.addAll(tuple.getT2());
                                return allResults;
                            })
                            .map(queryResults -> selectRetrievedDocuments(queryResults, command))
                            .doOnNext(documents -> {
                                long durationNanos = System.nanoTime() - startNanos;
                                recordRetrievalMetrics(decision, "success", documents.size(), durationNanos);
                                log.info(
                                        "RAG retrieval completed. conversationId={}, requestId={}, rewrittenQuery={}, retrievalQueries={}, bm25Queries={}, docs={}",
                                        command.conversationId(),
                                        command.requestId(),
                                        plan.rewrittenQuery(),
                                        plan.retrievalQueries(),
                                        buildBm25Queries(plan),
                                        documents.size()
                                );
                            }))
                    .onErrorResume(error -> {
                        long durationNanos = System.nanoTime() - startNanos;
                        recordRetrievalMetrics(decision, "fallback", 0, durationNanos);
                        log.warn(
                                "RAG retrieval failed, falling back to prompt-only flow. conversationId={}, requestId={}",
                                command.conversationId(),
                                command.requestId(),
                                error
                        );
                        return Mono.just(List.of());
                    });
        });
    }

    private Mono<List<QuerySearchResult>> searchVectorQueries(List<String> queries) {
        return Flux.fromIterable(queries)
                .flatMap(this::searchVectorDocumentsForQuery)
                .collectList();
    }

    private Mono<List<QuerySearchResult>> searchBm25Queries(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(queries)
                .flatMap(this::searchBm25DocumentsForQuery)
                .collectList();
    }

    private List<String> buildBm25Queries(EnhancedQueryPlan plan) {
        List<String> queries = new ArrayList<>();
        if (plan.originalQuery() != null && !plan.originalQuery().isBlank()) {
            queries.add(plan.originalQuery());
        }
        if (plan.rewrittenQuery() != null && !plan.rewrittenQuery().isBlank()) {
            queries.add(plan.rewrittenQuery());
        }
        return queries.stream().distinct().toList();
    }

    private Mono<QuerySearchResult> searchVectorDocumentsForQuery(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(RAG_RETRIEVAL_TOP_K)
                .build();
        return Mono.fromCallable(() -> pgVectorStore.similaritySearch(request))
                .subscribeOn(retrievalBlockingScheduler)
                .map(documents -> new QuerySearchResult("vector", query, documents))
                .onErrorResume(error -> {
                    log.warn("Vector search failed for query: {}", query, error);
                    return Mono.just(new QuerySearchResult("vector", query, List.of()));
                });
    }

    private Mono<QuerySearchResult> searchBm25DocumentsForQuery(String query) {
        return Mono.fromCallable(() -> bm25SearchService.search(query, RAG_RETRIEVAL_TOP_K))
                .subscribeOn(retrievalBlockingScheduler)
                .map(documents -> new QuerySearchResult("bm25", query, documents))
                .onErrorResume(error -> {
                    log.warn("BM25 search failed for query: {}", query, error);
                    return Mono.just(new QuerySearchResult("bm25", query, List.of()));
                });
    }

    private List<Document> selectRetrievedDocuments(List<QuerySearchResult> queryResults, LearningChatCommand command) {
        if (queryResults == null || queryResults.isEmpty()) {
            return List.of();
        }

        List<Document> sanitizedDocuments = fuseDocumentsByRrf(queryResults);

        if (sanitizedDocuments.isEmpty()) {
            return List.of();
        }

        List<Document> subjectMatchedDocuments = filterByHint(sanitizedDocuments, command.subject());
        List<Document> topicMatchedDocuments = filterByHint(
                subjectMatchedDocuments.isEmpty() ? sanitizedDocuments : subjectMatchedDocuments,
                command.currentTopic()
        );

        List<Document> preferredDocuments = !topicMatchedDocuments.isEmpty()
                ? topicMatchedDocuments
                : (!subjectMatchedDocuments.isEmpty() ? subjectMatchedDocuments : sanitizedDocuments);

        return preferredDocuments.stream()
                .limit(RAG_MAX_CONTEXT_DOCS)
                .toList();
    }

    private List<Document> fuseDocumentsByRrf(List<QuerySearchResult> queryResults) {
        Map<String, RetrievedDocumentCandidate> fused = new LinkedHashMap<>();
        for (QuerySearchResult queryResult : queryResults) {
            List<Document> documents = queryResult.documents();
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                if (document == null || document.getText() == null || document.getText().isBlank()) {
                    continue;
                }
                String key = documentDedupKey(document);
                RetrievedDocumentCandidate existing = fused.get(key);
                double rrfScore = 1.0D / (RAG_RRF_K + i + 1);
                double bestScore = documentScoreOrMaxDistance(document);
                if (existing == null) {
                    fused.put(key, new RetrievedDocumentCandidate(document, rrfScore, 1, bestScore, queryResult.source()));
                    continue;
                }
                existing.rrfScore += rrfScore;
                existing.hitCount++;
                existing.sources.add(queryResult.source());
                if (bestScore < existing.bestScore) {
                    existing.document = document;
                    existing.bestScore = bestScore;
                }
            }
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedDocumentCandidate::finalScore).reversed())
                .map(candidate -> candidate.document)
                .toList();
    }

    private String documentDedupKey(Document document) {
        if (!document.getId().isBlank()) {
            return document.getId();
        }
        return formatDocumentSource(document) + "::" + limitText(document.getText(), 120);
    }

    private List<Document> filterByHint(List<Document> documents, String hint) {
        if (hint == null || hint.isBlank()) {
            return List.of();
        }
        String normalizedHint = hint.trim().toLowerCase();
        return documents.stream()
                .filter(document -> documentMatchesHint(document, normalizedHint))
                .toList();
    }

    private boolean documentMatchesHint(Document document, String normalizedHint) {
        if (document.getText() != null && document.getText().toLowerCase().contains(normalizedHint)) {
            return true;
        }
        if (document.getMetadata() == null || document.getMetadata().isEmpty()) {
            return false;
        }
        return document.getMetadata().values().stream()
                .filter(value -> value != null)
                .map(String::valueOf)
                .map(String::toLowerCase)
                .anyMatch(value -> value.contains(normalizedHint));
    }

    private double documentScoreOrMaxDistance(Document document) {
        if (document.getMetadata() == null || document.getMetadata().isEmpty()) {
            return Double.MAX_VALUE;
        }
        Object score = document.getMetadata().get("distance");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        score = document.getMetadata().get("score");
        if (score instanceof Number number) {
            return -number.doubleValue();
        }
        return Double.MAX_VALUE;
    }

    private String formatRetrievedKnowledge(List<Document> retrievedDocs, boolean retrievalRequested) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return retrievalRequested
                    ? "未检索到可用知识库片段；回答时可结合会话上下文，但要明确说明不确定处。\n"
                    : "当前任务未启用知识库检索。\n";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document document = retrievedDocs.get(i);
            builder.append("- 资料").append(i + 1).append(" | 来源: ")
                    .append(formatDocumentSource(document))
                    .append('\n');
            builder.append("  内容: ")
                    .append(limitText(document.getText(), RAG_MAX_DOC_CONTENT_LENGTH))
                    .append('\n');
        }
        return builder.toString();
    }

    private String formatDocumentForContext(Document document) {
        return "来源: " + formatDocumentSource(document) + "\n内容: "
                + limitText(document.getText(), RAG_MAX_DOC_CONTENT_LENGTH);
    }

    private String formatDocumentSource(Document document) {
        if (document.getMetadata() == null || document.getMetadata().isEmpty()) {
            return document.getId() == null ? "unknown" : document.getId();
        }
        Object filename = document.getMetadata().get("filename");
        if (filename != null) {
            return String.valueOf(filename);
        }
        Object source = document.getMetadata().get("source");
        if (source != null) {
            return String.valueOf(source);
        }
        Object subject = document.getMetadata().get("subject");
        Object chapter = document.getMetadata().get("chapter");
        if (subject != null || chapter != null) {
            return "%s/%s".formatted(
                    subject == null ? "unknown-subject" : subject,
                    chapter == null ? "unknown-chapter" : chapter
            );
        }
        return document.getId() == null ? "unknown" : document.getId();
    }

    private String limitText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "无";
        }
        String normalizedText = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalizedText.length() <= maxLength) {
            return normalizedText;
        }
        return normalizedText.substring(0, maxLength) + "...";
    }

    private Map<String, Object> buildUserTurnMetadata(LearningChatCommand command, IntentDecision decision) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", command.requestId());
        if (command.subject() != null && !command.subject().isBlank()) {
            metadata.put("subject", command.subject());
        }
        if (command.currentTopic() != null && !command.currentTopic().isBlank()) {
            metadata.put("currentTopic", command.currentTopic());
        }
        metadata.putAll(decision.toMetadata());
        return metadata;
    }

    private Map<String, Object> buildAssistantTurnMetadata(LearningChatCommand command, IntentDecision decision) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", command.requestId());
        metadata.putAll(decision.toMetadata());
        return metadata;
    }

    private Flux<String> monitorResponseStream(String stage,
                                               LearningChatCommand command,
                                               IntentDecision decision,
                                               Flux<String> stream) {
        return Flux.defer(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            java.util.concurrent.atomic.AtomicInteger chunkCount = new java.util.concurrent.atomic.AtomicInteger();

            return stream.doOnNext(ignored -> chunkCount.incrementAndGet())
                    .doFinally(signalType -> {
                        String outcome = metricOutcome(signalType);
                        List<Tag> tags = responseMetricTags(stage, decision, outcome);

                        Counter.builder("ai.learning.response.requests")
                                .description("Learning chat response stream executions")
                                .tags(tags)
                                .register(meterRegistry)
                                .increment();

                        DistributionSummary.builder("ai.learning.response.chunks")
                                .description("Visible chunks emitted by learning chat responses")
                                .tags(tags)
                                .register(meterRegistry)
                                .record(chunkCount.get());

                        sample.stop(Timer.builder("ai.learning.response.duration")
                                .description("Learning chat response stream duration")
                                .tags(tags)
                                .register(meterRegistry));

                        log.info(
                                "Learning response stream finished. conversationId={}, requestId={}, stage={}, outcome={}, chunks={}",
                                command.conversationId(),
                                command.requestId(),
                                stage,
                                outcome,
                                chunkCount.get()
                        );
                    });
        });
    }

    private void recordRetrievalMetrics(IntentDecision decision,
                                        String outcome,
                                        int documentCount,
                                        long durationNanos) {
        List<Tag> tags = retrievalMetricTags(decision, outcome);

        Counter.builder("ai.learning.retrieval.requests")
                .description("Learning retrieval executions")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        DistributionSummary.builder("ai.learning.retrieval.docs")
                .description("Retrieved documents selected for context")
                .baseUnit("documents")
                .tags(tags)
                .register(meterRegistry)
                .record(documentCount);

        Timer.builder("ai.learning.retrieval.duration")
                .description("Learning retrieval duration")
                .tags(tags)
                .register(meterRegistry)
                .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private List<Tag> responseMetricTags(String stage, IntentDecision decision, String outcome) {
        List<Tag> tags = new ArrayList<>(baseMetricTags(decision));
        tags.add(Tag.of("stage", stage));
        tags.add(Tag.of("outcome", outcome));
        return List.copyOf(tags);
    }

    private List<Tag> retrievalMetricTags(IntentDecision decision, String outcome) {
        List<Tag> tags = new ArrayList<>(baseMetricTags(decision));
        tags.add(Tag.of("outcome", outcome));
        return List.copyOf(tags);
    }

    private List<Tag> baseMetricTags(IntentDecision decision) {
        return List.of(
                Tag.of("executionMode", decision.executionMode().name()),
                Tag.of("intentType", decision.intentType().name()),
                Tag.of("retrievalEnabled", Boolean.toString(decision.needRetrieval()))
        );
    }

    private String metricOutcome(SignalType signalType) {
        return switch (signalType) {
            case ON_COMPLETE -> "completed";
            case CANCEL -> "canceled";
            case ON_ERROR -> "failed";
            default -> "unknown";
        };
    }

    private Context buildRequestContext(LearningChatCommand command) {
        return Context.of(
                CONTEXT_CONVERSATION_ID, command.conversationId(),
                CONTEXT_REQUEST_ID, command.requestId(),
                CONTEXT_EXECUTION_MODE, "pending"
        );
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    private static final class RetrievedDocumentCandidate {
        private Document document;
        private double rrfScore;
        private int hitCount;
        private double bestScore;
        private final List<String> sources = new ArrayList<>();

        private RetrievedDocumentCandidate(Document document,
                                           double rrfScore,
                                           int hitCount,
                                           double bestScore,
                                           String source) {
            this.document = document;
            this.rrfScore = rrfScore;
            this.hitCount = hitCount;
            this.bestScore = bestScore;
            this.sources.add(source);
        }

        private double finalScore() {
            return rrfScore + Math.max(0, hitCount - 1) * 1.0e-4 - Math.min(bestScore, 1.0D) * 1.0e-6;
        }
    }

    private record QuerySearchResult(String source, String query, List<Document> documents) {
    }
}
