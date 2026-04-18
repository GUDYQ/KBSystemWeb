package org.example.kbsystemproject.ailearning.application.service;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.LongTermMemoryEntry;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.example.kbsystemproject.ailearning.infrastructure.memory.ConversationArchiveStore;
import org.example.kbsystemproject.ailearning.infrastructure.memory.RedisShortTermMemoryStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SessionStorageService {

    private final LearningSessionStore learningSessionStore;
    private final RedisShortTermMemoryStore shortTermMemoryStore;
    private final ConversationArchiveStore conversationArchiveStore;
    private final EmbeddingModel embeddingModel;
    private final ChatClient chatClient;
    private final MemoryProperties memoryProperties;
    private final Scheduler aiBlockingScheduler;
    private final TransactionalOperator transactionalOperator;
    private final SessionLockService sessionLockService;

    public SessionStorageService(LearningSessionStore learningSessionStore,
                                 RedisShortTermMemoryStore shortTermMemoryStore,
                                 ConversationArchiveStore conversationArchiveStore,
                                 EmbeddingModel embeddingModel,
                                 @Qualifier("chatClient") ChatClient chatClient,
                                 MemoryProperties memoryProperties,
                                 @Qualifier("aiBlockingScheduler") Scheduler aiBlockingScheduler,
                                 TransactionalOperator transactionalOperator,
                                 SessionLockService sessionLockService) {
        this.learningSessionStore = learningSessionStore;
        this.shortTermMemoryStore = shortTermMemoryStore;
        this.conversationArchiveStore = conversationArchiveStore;
        this.embeddingModel = embeddingModel;
        this.chatClient = chatClient;
        this.memoryProperties = memoryProperties;
        this.aiBlockingScheduler = aiBlockingScheduler;
        this.transactionalOperator = transactionalOperator;
        this.sessionLockService = sessionLockService;
    }

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

    public Mono<Void> appendTurn(String conversationId,
                                 SessionTurnPair turnPair,
                                 String currentTopic,
                                 Map<String, Object> sessionMetadata) {
        return sessionLockService.execute(conversationId, () -> appendTurnInternal(conversationId, turnPair, currentTopic, sessionMetadata));
    }

    public Mono<Void> archiveSummary(String conversationId, String summary, Map<String, Object> metadata) {
        return embed(summary)
                .flatMap(embedding -> conversationArchiveStore.archiveSummary(conversationId, summary, embedding, metadata))
                .then(learningSessionStore.touch(conversationId, null));
    }

    public Mono<List<ConversationTurn>> getShortTermMemory(String conversationId) {
        return learningSessionStore.getByConversationId(conversationId)
                .flatMap(this::resolveShortTermMemory)
                .defaultIfEmpty(List.of());
    }

    public Mono<List<LongTermMemoryEntry>> getLongTermMemory(String conversationId, String query) {
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

    public Mono<SessionMemorySnapshot> loadSnapshot(String conversationId, String query) {
        Mono<LearningSessionRecord> sessionMono = learningSessionStore.getByConversationId(conversationId).cache();
        Mono<List<ConversationTurn>> shortTermMono = sessionMono.flatMap(this::resolveShortTermMemory);
        Mono<List<LongTermMemoryEntry>> longTermMono = getLongTermMemory(conversationId, query);

        return Mono.zip(sessionMono, shortTermMono, longTermMono)
                .map(tuple -> new SessionMemorySnapshot(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    public Mono<Long> getTurnCount(String conversationId) {
        return learningSessionStore.getByConversationId(conversationId)
                .map(session -> Long.valueOf(session.turnCount()))
                .defaultIfEmpty(0L);
    }

    public Mono<Void> clearShortTermMemory(String conversationId) {
        return shortTermMemoryStore.clear(conversationId)
                .onErrorResume(error -> {
                    log.warn("Short-term memory clear skipped because Redis is unavailable. conversationId={}", conversationId, error);
                    return Mono.empty();
                });
    }

    public Mono<Void> closeSession(String conversationId) {
        return sessionLockService.execute(conversationId, () -> learningSessionStore.close(conversationId)
                .then(clearShortTermMemory(conversationId))
                .thenReturn(Boolean.TRUE))
                .then();
    }

    private Mono<Void> recoverShortTermMemory(String conversationId) {
        return conversationArchiveStore.loadRecentTurns(
                        conversationId,
                        Math.max(2, memoryProperties.getShortTerm().getMaxTurns() * 2)
                )
                .flatMap(turns -> learningSessionStore.getByConversationId(conversationId)
                        .flatMap(session -> shortTermMemoryStore.rebuild(
                                        conversationId,
                                        turns,
                                        session.turnCount()
                                )
                        ));
    }

    private Mono<Void> appendTurnInternal(String conversationId,
                                          SessionTurnPair turnPair,
                                          String currentTopic,
                                          Map<String, Object> sessionMetadata) {
        Mono<float[]> userEmbedding = embed(turnPair.userTurn().content());
        Mono<float[]> assistantEmbedding = embed(turnPair.assistantTurn().content());

        return Mono.zip(userEmbedding, assistantEmbedding)
                .flatMap(tuple -> transactionalOperator.transactional(
                                learningSessionStore.reserveNextTurn(conversationId, currentTopic)
                                        .flatMap(turnIndex -> {
                                            SessionTurnPair indexedTurnPair = indexTurnPair(turnPair, turnIndex);
                                            return conversationArchiveStore.archiveTurnPair(
                                                            conversationId,
                                                            indexedTurnPair,
                                                            tuple.getT1(),
                                                            tuple.getT2(),
                                                            enrichMetadata(sessionMetadata, turnIndex),
                                                            turnIndex
                                                    )
                                                    .thenReturn(new PersistedTurn(turnIndex, indexedTurnPair));
                                        })
                        )
                        .flatMap(persistedTurn -> synchronizeShortTermMemory(conversationId, persistedTurn))
                        .flatMap(turnIndex -> maybeArchiveSummary(conversationId, turnIndex).thenReturn(turnIndex))
                        .then());
    }

    private Mono<Integer> synchronizeShortTermMemory(String conversationId, PersistedTurn persistedTurn) {
        return shortTermMemoryStore.appendTurnPair(conversationId, persistedTurn.turnPair(), persistedTurn.turnIndex())
                .thenReturn(persistedTurn.turnIndex())
                .onErrorResume(error -> {
                    log.warn("Short-term memory cache append skipped because Redis is unavailable. conversationId={}", conversationId, error);
                    return Mono.just(persistedTurn.turnIndex());
                });
    }

    private Mono<List<ConversationTurn>> resolveShortTermMemory(LearningSessionRecord session) {
        return Mono.zip(
                        shortTermMemoryStore.getRecentTurns(session.conversationId()),
                        shortTermMemoryStore.getTotalTurns(session.conversationId())
                )
                .onErrorResume(error -> {
                    log.warn("Short-term memory cache read failed, falling back to archive store. conversationId={}", session.conversationId(), error);
                    return Mono.empty();
                })
                .flatMap(tuple -> {
                    List<ConversationTurn> cachedTurns = tuple.getT1();
                    long cachedTurnCount = tuple.getT2();
                    long authoritativeTurnCount = session.turnCount() == null ? 0 : session.turnCount();
                    if (isCacheConsistent(cachedTurns, cachedTurnCount, authoritativeTurnCount)) {
                        return Mono.just(cachedTurns);
                    }
                    return conversationArchiveStore.loadRecentTurns(
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
                .switchIfEmpty(conversationArchiveStore.loadRecentTurns(
                        session.conversationId(),
                        Math.max(2, memoryProperties.getShortTerm().getMaxTurns() * 2)
                ));
    }

    private boolean isCacheConsistent(List<ConversationTurn> cachedTurns, long cachedTurnCount, long authoritativeTurnCount) {
        if (authoritativeTurnCount == 0) {
            return cachedTurns.isEmpty() && cachedTurnCount == 0;
        }
        return !cachedTurns.isEmpty()
                && cachedTurnCount == authoritativeTurnCount
                && latestTurnIndex(cachedTurns) == authoritativeTurnCount;
    }

    private int latestTurnIndex(List<ConversationTurn> turns) {
        return turns.stream()
                .map(ConversationTurn::metadata)
                .map(metadata -> metadata.get("turnIndex"))
                .filter(Integer.class::isInstance)
                .map(Integer.class::cast)
                .max(Integer::compareTo)
                .orElse(-1);
    }

    private Map<String, Object> enrichMetadata(Map<String, Object> sessionMetadata, int turnIndex) {
        Map<String, Object> metadata = new HashMap<>();
        if (sessionMetadata != null) {
            metadata.putAll(sessionMetadata);
        }
        metadata.put("turnIndex", turnIndex);
        return metadata;
    }

    private SessionTurnPair indexTurnPair(SessionTurnPair turnPair, int turnIndex) {
        return new SessionTurnPair(
                indexTurn(turnPair.userTurn(), turnIndex, 0),
                indexTurn(turnPair.assistantTurn(), turnIndex, 1)
        );
    }

    private ConversationTurn indexTurn(ConversationTurn turn, int turnIndex, int messageIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (turn.metadata() != null) {
            metadata.putAll(turn.metadata());
        }
        metadata.put("turnIndex", turnIndex);
        metadata.put("messageIndex", messageIndex);
        return new ConversationTurn(turn.role(), turn.content(), turn.createdAt(), metadata);
    }

    private Mono<Void> maybeArchiveSummary(String conversationId, int turnIndex) {
        if (!memoryProperties.getSummary().isEnabled()) {
            return Mono.empty();
        }
        int triggerTurns = memoryProperties.getSummary().getTriggerTurns();
        if (triggerTurns <= 0) {
            return Mono.empty();
        }
        return learningSessionStore.getByConversationId(conversationId)
                .flatMap(session -> {
                    int lastSummarizedTurn = session.lastSummarizedTurn() == null ? 0 : session.lastSummarizedTurn();
                    if (turnIndex - lastSummarizedTurn < triggerTurns) {
                        return Mono.empty();
                    }
                    int startTurn = Math.max(1, lastSummarizedTurn + 1);
                    return conversationArchiveStore.loadTurnsByTurnRange(conversationId, startTurn, turnIndex)
                            .flatMap(turns -> summarizeTurns(conversationId, session, turns, startTurn, turnIndex))
                            .flatMap(summary -> archiveSummary(
                                            conversationId,
                                            summary,
                                            Map.of(
                                                    "summaryStartTurn", startTurn,
                                                    "summaryEndTurn", turnIndex,
                                                    "generatedBy", "session-storage-service"
                                            )
                                    )
                                    .then(learningSessionStore.updateLastSummarizedTurn(conversationId, turnIndex)))
                            .onErrorResume(error -> {
                                log.warn("Summary archival skipped due to downstream failure. conversationId={}", conversationId, error);
                                return Mono.empty();
                            });
                });
    }

    private Mono<String> summarizeTurns(String conversationId,
                                        LearningSessionRecord session,
                                        List<ConversationTurn> turns,
                                        int startTurn,
                                        int endTurn) {
        if (turns == null || turns.isEmpty()) {
            return Mono.empty();
        }
        String transcript = turns.stream()
                .map(turn -> turn.role().name() + ": " + turn.content())
                .collect(java.util.stream.Collectors.joining("\n"));
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

    private Mono<float[]> embed(String text) {
        return Mono.fromCallable(() -> embeddingModel.embed(text))
                .subscribeOn(aiBlockingScheduler);
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    private record PersistedTurn(int turnIndex, SessionTurnPair turnPair) {
    }
}
