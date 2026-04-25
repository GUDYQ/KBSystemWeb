package org.example.kbsystemproject.ailearning.application.session;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.LongTermMemoryEntry;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionTopicBlock;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.example.kbsystemproject.ailearning.infrastructure.memory.ConversationArchiveStore;
import org.example.kbsystemproject.ailearning.infrastructure.memory.RedisShortTermMemoryStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.profile.LearningProfileTaskStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionMemoryTaskStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionMessageStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionRequestStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionTopicBlockStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SessionStorageService {

    private final LearningSessionStore learningSessionStore;
    private final LearningSessionRequestStore learningSessionRequestStore;
    private final LearningSessionMessageStore learningSessionMessageStore;
    private final LearningSessionMemoryTaskStore learningSessionMemoryTaskStore;
    private final LearningProfileTaskStore learningProfileTaskStore;
    private final LearningSessionTopicBlockStore learningSessionTopicBlockStore;
    private final RedisShortTermMemoryStore shortTermMemoryStore;
    private final ConversationArchiveStore conversationArchiveStore;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final MemoryProperties memoryProperties;
    private final Scheduler aiBlockingScheduler;
    private final TransactionalOperator transactionalOperator;
    private final SessionLockService sessionLockService;

    public SessionStorageService(LearningSessionStore learningSessionStore,
                                 LearningSessionRequestStore learningSessionRequestStore,
                                 LearningSessionMessageStore learningSessionMessageStore,
                                 LearningSessionMemoryTaskStore learningSessionMemoryTaskStore,
                                 LearningProfileTaskStore learningProfileTaskStore,
                                 LearningSessionTopicBlockStore learningSessionTopicBlockStore,
                                 RedisShortTermMemoryStore shortTermMemoryStore,
                                 ConversationArchiveStore conversationArchiveStore,
                                 EmbeddingModel embeddingModel,
                                 @Qualifier("chatClient") ChatClient chatClient,
                                 MemoryProperties memoryProperties,
                                 @Qualifier("aiBlockingScheduler") Scheduler aiBlockingScheduler,
                                 TransactionalOperator transactionalOperator,
                                 SessionLockService sessionLockService) {
        this.learningSessionStore = learningSessionStore;
        this.learningSessionRequestStore = learningSessionRequestStore;
        this.learningSessionMessageStore = learningSessionMessageStore;
        this.learningSessionMemoryTaskStore = learningSessionMemoryTaskStore;
        this.learningProfileTaskStore = learningProfileTaskStore;
        this.learningSessionTopicBlockStore = learningSessionTopicBlockStore;
        this.shortTermMemoryStore = shortTermMemoryStore;
        this.conversationArchiveStore = conversationArchiveStore;
        this.embeddingModel = embeddingModel;
        this.chatClient = chatClient;
        this.memoryProperties = memoryProperties;
        this.aiBlockingScheduler = aiBlockingScheduler;
        this.transactionalOperator = transactionalOperator;
        this.sessionLockService = sessionLockService;
    }

    // 打开或复用会话主记录，确保后续短期记忆读写都有会话上下文。
    public Mono<LearningSessionRecord> openSession(SessionOpenCommand command) {
        return learningSessionStore.getOrCreate(
                command.conversationId(),
                command.userId(),
                command.subject(),
                command.sessionType(),
                command.learningGoal(),
                command.currentTopic()
        );
    }

    // 对外暴露的写入入口：先做会话级串行化，再执行数据库落库和缓存同步。
    public Mono<Void> appendTurn(String conversationId,
                                 String requestId,
                                 SessionTurnPair turnPair,
                                 String currentTopic,
                                 Map<String, Object> sessionMetadata) {
        return sessionLockService.execute(conversationId, () -> appendTurnInternal(conversationId, requestId, turnPair, currentTopic, sessionMetadata));
    }

    public Mono<Void> archiveSummary(String conversationId, String summary, Map<String, Object> metadata) {
        if (!memoryProperties.getLongTerm().isEnabled()) {
            return Mono.empty();
        }
        return embed(summary)
                .flatMap(embedding -> conversationArchiveStore.archiveSummary(conversationId, summary, embedding, metadata))
                .then(learningSessionStore.touch(conversationId, null));
    }

    // 读取当前会话的短期记忆，只返回最近若干轮上下文。
    public Mono<List<ConversationTurn>> getShortTermMemory(String conversationId) {
        return learningSessionStore.getByConversationId(conversationId)
                .flatMap(this::resolveShortTermMemory)
                .defaultIfEmpty(List.of());
    }

    // 长期记忆查询入口；当前调试短期链路时会被配置开关直接短路。
    public Mono<List<LongTermMemoryEntry>> getLongTermMemory(String conversationId, String query) {
        if (!memoryProperties.getLongTerm().isEnabled()) {
            return Mono.just(List.of());
        }
        if (query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        return embed(query)
                .flatMap(embedding -> conversationArchiveStore.searchRelevantMemories(
                        conversationId,
                        embedding,
                        List.of("SUMMARY", "MESSAGE"),
                        memoryProperties.getLongTerm().getTopK()
                ))
                .map(memories -> memories.stream()
                        .filter(memory -> memory.score() == null
                                || memory.score() >= memoryProperties.getLongTerm().getSimilarityThreshold())
                        .toList());
    }

    // 组装一次对话所需的上下文快照：会话信息、短期记忆、长期记忆和当前主题块。
    public Mono<SessionMemorySnapshot> loadSnapshot(String conversationId, String query) {
        Mono<LearningSessionRecord> sessionMono = learningSessionStore.getByConversationId(conversationId).cache();
        Mono<List<ConversationTurn>> shortTermMono = sessionMono.flatMap(this::resolveShortTermMemory);
        Mono<List<LongTermMemoryEntry>> longTermMono = getLongTermMemory(conversationId, query);
        Mono<SessionTopicBlock> activeTopicBlockMono = memoryProperties.getCompression().isEnabled()
                ? learningSessionTopicBlockStore.findActiveByConversationId(conversationId)
                .defaultIfEmpty(new SessionTopicBlock(null, conversationId, null, "EMPTY", 0, 0, 0, 0, 0, 0, null, null))
                : Mono.just(new SessionTopicBlock(null, conversationId, null, "EMPTY", 0, 0, 0, 0, 0, 0, null, null));

        return Mono.zip(sessionMono, shortTermMono, longTermMono, activeTopicBlockMono)
                .map(tuple -> new SessionMemorySnapshot(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4()));
    }

    // 以 learning_session.turn_count 作为权威轮次来源。
    public Mono<Long> getTurnCount(String conversationId) {
        return learningSessionStore.getByConversationId(conversationId)
                .map(session -> Long.valueOf(session.turnCount()))
                .defaultIfEmpty(0L);
    }

    // 按会话 ID 读取会话主记录。
    public Mono<LearningSessionRecord> getSession(String conversationId) {
        return learningSessionStore.getByConversationId(conversationId);
    }

    // 按轮次范围从数据库回放消息，供异步任务或摘要流程使用。
    public Mono<List<ConversationTurn>> loadTurnsByTurnRange(String conversationId, int startTurnInclusive, int endTurnInclusive) {
        return learningSessionMessageStore.loadTurnsByTurnRange(conversationId, startTurnInclusive, endTurnInclusive);
    }

    // 从数据库读取最近若干条消息，通常作为 Redis 失效时的回源结果。
    public Mono<List<ConversationTurn>> loadRecentTurns(String conversationId, int limit) {
        return learningSessionMessageStore.loadRecentTurns(conversationId, limit);
    }

    // 短期记忆写入的核心事务：分配 turnIndex、写消息表、投递异步任务、更新请求状态。
    private Mono<Void> appendTurnInternal(String conversationId,
                                          String requestId,
                                          SessionTurnPair turnPair,
                                          String currentTopic,
                                          Map<String, Object> sessionMetadata) {
        return transactionalOperator.transactional(
                        learningSessionStore.reserveNextTurn(conversationId, currentTopic)
                                .flatMap(turnIndex -> {
                                    SessionTurnPair indexedTurnPair = indexTurnPair(turnPair, turnIndex);
                                    return learningSessionMessageStore.saveTurnPair(conversationId, requestId, indexedTurnPair, turnIndex)
                                            .then(learningSessionMemoryTaskStore.enqueueTurnSync(conversationId, requestId, turnIndex))
                                            .then(learningProfileTaskStore.enqueueTurnSync(conversationId, requestId, turnIndex))
                                            .then(learningSessionRequestStore.markSucceeded(
                                                    conversationId,
                                                    requestId,
                                                    turnIndex,
                                                    indexedTurnPair.assistantTurn().content()
                                            ))
                                            .then(learningSessionStore.releaseProcessingSlot(conversationId, requestId))
                                            .thenReturn(new PersistedTurn(turnIndex, indexedTurnPair));
                                })
                )
                .flatMap(persistedTurn -> synchronizeShortTermMemory(conversationId, persistedTurn))
                .then();
    }

    // 事务提交后把新的一轮同步进 Redis；失败时降级为仅数据库可用。
    private Mono<Integer> synchronizeShortTermMemory(String conversationId, PersistedTurn persistedTurn) {
        return shortTermMemoryStore.appendTurnPair(conversationId, persistedTurn.turnPair(), persistedTurn.turnIndex())
                .thenReturn(persistedTurn.turnIndex())
                .onErrorResume(error -> {
                    log.warn("Short-term memory cache append skipped because Redis is unavailable. conversationId={}", conversationId, error);
                    return Mono.just(persistedTurn.turnIndex());
                });
    }

    // 短期记忆读取策略：先读 Redis，校验不一致时回源数据库并自动重建缓存。
    private Mono<List<ConversationTurn>> resolveShortTermMemory(LearningSessionRecord session) {
        return Mono.zip(
                        shortTermMemoryStore.getRecentTurns(session.conversationId()),
                        shortTermMemoryStore.getTotalTurns(session.conversationId())
                )
                .onErrorResume(error -> {
                    log.warn("Short-term memory cache read failed, falling back to message store. conversationId={}", session.conversationId(), error);
                    return Mono.empty();
                })
                .flatMap(tuple -> {
                    List<ConversationTurn> cachedTurns = tuple.getT1();
                    long cachedTurnCount = tuple.getT2();
                    long authoritativeTurnCount = session.turnCount() == null ? 0 : session.turnCount();
                    if (isCacheConsistent(cachedTurns, cachedTurnCount, authoritativeTurnCount)) {
                        return Mono.just(cachedTurns);
                    }
                    return learningSessionMessageStore.loadRecentTurns(
                                    session.conversationId(),
                                    Math.max(2, memoryProperties.getShortTerm().getMaxTurns() * 2)
                            )
                            .flatMap(fallbackTurns -> shortTermMemoryStore.rebuild(
                                            session.conversationId(),
                                            fallbackTurns,
                                            authoritativeTurnCount
                                    )
                                    .onErrorResume(error -> {
                                        log.warn("Short-term memory cache rebuild skipped because Redis is unavailable. conversationId={}", session.conversationId(), error);
                                        return Mono.empty();
                                    })
                                    .thenReturn(fallbackTurns));
                })
                .switchIfEmpty(learningSessionMessageStore.loadRecentTurns(
                        session.conversationId(),
                        Math.max(2, memoryProperties.getShortTerm().getMaxTurns() * 2)
                ));
    }

    // 用数据库权威轮次校验 Redis 是否可直接信任。
    private boolean isCacheConsistent(List<ConversationTurn> cachedTurns, long cachedTurnCount, long authoritativeTurnCount) {
        if (authoritativeTurnCount == 0) {
            return cachedTurns.isEmpty() && cachedTurnCount == 0;
        }
        return !cachedTurns.isEmpty()
                && cachedTurnCount == authoritativeTurnCount
                && latestTurnIndex(cachedTurns) == authoritativeTurnCount;
    }

    // 从消息元数据中找出当前缓存包含的最大 turnIndex。
    private int latestTurnIndex(List<ConversationTurn> turns) {
        return turns.stream()
                .map(ConversationTurn::metadata)
                .map(metadata -> metadata.get("turnIndex"))
                .filter(Integer.class::isInstance)
                .map(Integer.class::cast)
                .max(Integer::compareTo)
                .orElse(-1);
    }

    // 给异步处理链统一补齐 turnIndex 等公共元数据。
    public Map<String, Object> enrichMetadata(Map<String, Object> sessionMetadata, int turnIndex) {
        Map<String, Object> metadata = new HashMap<>();
        if (sessionMetadata != null) {
            metadata.putAll(sessionMetadata);
        }
        metadata.put("turnIndex", turnIndex);
        return metadata;
    }

    // 把一轮问答拆成“用户消息 + 助手消息”，并统一注入轮次编号。
    private SessionTurnPair indexTurnPair(SessionTurnPair turnPair, int turnIndex) {
        return new SessionTurnPair(
                indexTurn(turnPair.userTurn(), turnIndex, 0),
                indexTurn(turnPair.assistantTurn(), turnIndex, 1)
        );
    }

    // 给单条消息补齐 turnIndex 和 messageIndex，方便数据库/Redis 排序。
    private ConversationTurn indexTurn(ConversationTurn turn, int turnIndex, int messageIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (turn.metadata() != null) {
            metadata.putAll(turn.metadata());
        }
        metadata.put("turnIndex", turnIndex);
        metadata.put("messageIndex", messageIndex);
        return new ConversationTurn(turn.role(), turn.content(), turn.createdAt(), metadata);
    }

    // 定期把较旧的消息片段压缩成摘要归档；当前短期记忆调试模式下默认关闭。
    public Mono<Void> maybeArchiveSummary(String conversationId, int turnIndex) {
        if (!memoryProperties.getCompression().isEnabled()) {
            return Mono.empty();
        }
        if (!memoryProperties.getLongTerm().isEnabled()) {
            return Mono.empty();
        }
        if (!memoryProperties.getSummary().isEnabled()) {
            return Mono.empty();
        }
        int triggerTurns = memoryProperties.getSummary().getTriggerTurns();
        if (triggerTurns <= 0) {
            return Mono.empty();
        }
        return sessionLockService.executeStrict(conversationId, () -> maybeArchiveSummaryInternal(conversationId, turnIndex, triggerTurns))
                .onErrorResume(error -> {
                    log.warn("Summary archival skipped because exclusive session lock is unavailable. conversationId={}, turnIndex={}",
                            conversationId,
                            turnIndex,
                            error);
                    return Mono.empty();
                });
    }

    private Mono<Void> maybeArchiveSummaryInternal(String conversationId, int turnIndex, int triggerTurns) {
        return learningSessionStore.getByConversationId(conversationId)
                .flatMap(session -> {
                    int lastSummarizedTurn = session.lastSummarizedTurn() == null ? 0 : session.lastSummarizedTurn();
                    if (turnIndex - lastSummarizedTurn < triggerTurns) {
                        return Mono.empty();
                    }
                    int startTurn = Math.max(1, lastSummarizedTurn + 1);
                    return learningSessionMessageStore.loadTurnsByTurnRange(conversationId, startTurn, turnIndex)
                            .flatMap(turns -> summarizeTurns(conversationId, session, turns, startTurn, turnIndex))
                            .flatMap(summary -> {
                                Map<String, Object> metadata = new LinkedHashMap<>();
                                metadata.put("summaryType", "TURN_RANGE");
                                metadata.put("summaryStartTurn", startTurn);
                                metadata.put("summaryEndTurn", turnIndex);
                                metadata.put("startTurn", startTurn);
                                metadata.put("endTurn", turnIndex);
                                metadata.put("generatedBy", "session-memory-sync-service");
                                return archiveSummary(conversationId, summary, metadata)
                                        .then(learningSessionStore.advanceLastSummarizedTurn(
                                                conversationId,
                                                lastSummarizedTurn,
                                                turnIndex
                                        ))
                                        .doOnNext(advanced -> {
                                            if (!advanced) {
                                                log.info("Summary cursor already advanced by another worker. conversationId={}, expectedTurn={}, targetTurn={}",
                                                        conversationId,
                                                        lastSummarizedTurn,
                                                        turnIndex);
                                            }
                                        })
                                        .then();
                            })
                            .onErrorResume(error -> {
                                log.warn("Summary archival skipped due to downstream failure. conversationId={}", conversationId, error);
                                return Mono.empty();
                            });
                });
    }

    // 调用大模型把指定轮次区间整理成适合长期检索的摘要。
    private Mono<String> summarizeTurns(String conversationId,
                                        LearningSessionRecord session,
                                        List<ConversationTurn> turns,
                                        int startTurn,
                                        int endTurn) {
        if (turns == null || turns.isEmpty()) {
            return Mono.empty();
        }
        List<SummaryChunk> chunks = chunkTurnsForSummary(turns, startTurn, endTurn);
        if (chunks.size() == 1) {
            SummaryChunk chunk = chunks.getFirst();
            return summarizeTranscript(conversationId, session, chunk.startTurn(), chunk.endTurn(), chunk.transcript());
        }

        return reactor.core.publisher.Flux.fromIterable(chunks)
                .concatMap(chunk -> summarizeTranscript(
                        conversationId,
                        session,
                        chunk.startTurn(),
                        chunk.endTurn(),
                        chunk.transcript()
                ))
                .collectList()
                .flatMap(chunkSummaries -> {
                    if (chunkSummaries.isEmpty()) {
                        return Mono.empty();
                    }
                    String mergedSummaryInput = buildMergedSummaryInput(chunks, chunkSummaries);
                    return Mono.fromCallable(() -> chatClient.prompt()
                                    .system("""
                                            你是学习会话记忆整理器。
                                            下面是同一会话多个分段摘要，请合并成一份最终长期记忆摘要。
                                            输出要求：
                                            1. 只保留稳定事实、用户目标、关键知识点、未解决问题。
                                            2. 去掉重复表述，保留时间顺序。
                                            3. 使用 4 到 8 条中文要点。
                                            """)
                                    .user("""
                                            conversationId: %s
                                            subject: %s
                                            learningGoal: %s
                                            currentTopic: %s
                                            turnRange: %d-%d

                                            partialSummaries:
                                            %s
                                            """.formatted(
                                            conversationId,
                                            defaultText(session.subject()),
                                            defaultText(session.learningGoal()),
                                            defaultText(session.currentTopic()),
                                            startTurn,
                                            endTurn,
                                            mergedSummaryInput
                                    ))
                                    .call()
                                    .content())
                            .subscribeOn(aiBlockingScheduler)
                            .map(summary -> summary == null ? "" : summary.trim())
                            .filter(summary -> !summary.isBlank());
                });
    }

    private Mono<String> summarizeTranscript(String conversationId,
                                             LearningSessionRecord session,
                                             int startTurn,
                                             int endTurn,
                                             String transcript) {
        return Mono.fromCallable(() -> chatClient.prompt()
                        .system("""
                                你是学习会话记忆整理器。
                                请把会话片段整理成适合长期记忆检索的摘要。
                                输出要求：
                                1. 只保留稳定事实、用户目标、已确认偏好、关键知识点、未解决问题。
                                2. 不要复述寒暄，不要编造。
                                3. 使用 4 到 8 条中文要点，便于后续检索。
                                """)
                        .user("""
                                conversationId: %s
                                subject: %s
                                learningGoal: %s
                                currentTopic: %s
                                turnRange: %d-%d

                                transcript:
                                %s
                                """.formatted(
                                conversationId,
                                defaultText(session.subject()),
                                defaultText(session.learningGoal()),
                                defaultText(session.currentTopic()),
                                startTurn,
                                endTurn,
                                transcript
                        ))
                        .call()
                        .content())
                .subscribeOn(aiBlockingScheduler)
                .map(summary -> summary == null ? "" : summary.trim())
                .filter(summary -> !summary.isBlank());
    }

    private List<SummaryChunk> chunkTurnsForSummary(List<ConversationTurn> turns, int defaultStartTurn, int defaultEndTurn) {
        int maxInputTokensEstimate = Math.max(512, memoryProperties.getSummary().getMaxInputTokensEstimate());
        List<SummaryChunk> chunks = new ArrayList<>();
        List<String> currentLines = new ArrayList<>();
        int currentStartTurn = -1;
        int currentEndTurn = -1;
        int currentTokenEstimate = 0;

        for (ConversationTurn turn : turns) {
            String line = turn.role().name() + ": " + turn.content();
            int turnIndex = resolveTurnIndex(turn, currentEndTurn == -1 ? defaultStartTurn : currentEndTurn);
            int lineTokenEstimate = estimateTokenCount(line) + 8;

            if (!currentLines.isEmpty() && currentTokenEstimate + lineTokenEstimate > maxInputTokensEstimate) {
                chunks.add(new SummaryChunk(
                        currentStartTurn == -1 ? defaultStartTurn : currentStartTurn,
                        currentEndTurn == -1 ? defaultEndTurn : currentEndTurn,
                        String.join("\n", currentLines)
                ));
                currentLines = new ArrayList<>();
                currentStartTurn = -1;
                currentEndTurn = -1;
                currentTokenEstimate = 0;
            }

            if (currentStartTurn == -1) {
                currentStartTurn = turnIndex;
            }
            currentEndTurn = turnIndex;
            currentLines.add(line);
            currentTokenEstimate += lineTokenEstimate;
        }

        if (!currentLines.isEmpty()) {
            chunks.add(new SummaryChunk(
                    currentStartTurn == -1 ? defaultStartTurn : currentStartTurn,
                    currentEndTurn == -1 ? defaultEndTurn : currentEndTurn,
                    String.join("\n", currentLines)
            ));
        }

        return chunks;
    }

    private int resolveTurnIndex(ConversationTurn turn, int fallbackTurn) {
        if (turn != null && turn.metadata() != null) {
            Object value = turn.metadata().get("turnIndex");
            if (value instanceof Integer integerValue) {
                return integerValue;
            }
        }
        return fallbackTurn;
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        double tokens = 0D;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isWhitespace(current)) {
                continue;
            }
            tokens += current <= 127 ? 0.25D : 1D;
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private String buildMergedSummaryInput(List<SummaryChunk> chunks, List<String> chunkSummaries) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < chunkSummaries.size(); i++) {
            SummaryChunk chunk = chunks.get(i);
            builder.append("分段 ")
                    .append(i + 1)
                    .append(" [")
                    .append(chunk.startTurn())
                    .append('-')
                    .append(chunk.endTurn())
                    .append("]:\n")
                    .append(chunkSummaries.get(i))
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    // 统一封装 embedding 调用，避免阻塞 Reactor 主线程。
    private Mono<float[]> embed(String text) {
        return Mono.fromCallable(() -> embeddingModel.embed(text))
                .subscribeOn(aiBlockingScheduler);
    }

    // 组装摘要 prompt 时对空字段做兜底。
    private String defaultText(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    private record SummaryChunk(int startTurn, int endTurn, String transcript) {
    }

    private record PersistedTurn(int turnIndex, SessionTurnPair turnPair) {
    }
}

