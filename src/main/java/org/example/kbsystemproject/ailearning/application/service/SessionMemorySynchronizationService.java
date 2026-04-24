package org.example.kbsystemproject.ailearning.application.service;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemoryTaskRecord;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.example.kbsystemproject.ailearning.infrastructure.memory.ConversationArchiveStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionMemoryTaskStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class SessionMemorySynchronizationService {

    private final LearningSessionMemoryTaskStore learningSessionMemoryTaskStore;
    private final SessionStorageService sessionStorageService;
    private final ConversationArchiveStore conversationArchiveStore;
    private final EmbeddingModel embeddingModel;
    private final MemoryProperties memoryProperties;
    private final Scheduler aiBlockingScheduler;
    private final SessionCompressionService sessionCompressionService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String ownerInstance;

    public SessionMemorySynchronizationService(LearningSessionMemoryTaskStore learningSessionMemoryTaskStore,
                                               SessionStorageService sessionStorageService,
                                               ConversationArchiveStore conversationArchiveStore,
                                               EmbeddingModel embeddingModel,
                                               MemoryProperties memoryProperties,
                                               @Qualifier("aiBlockingScheduler") Scheduler aiBlockingScheduler,
                                               SessionCompressionService sessionCompressionService) {
        this.learningSessionMemoryTaskStore = learningSessionMemoryTaskStore;
        this.sessionStorageService = sessionStorageService;
        this.conversationArchiveStore = conversationArchiveStore;
        this.embeddingModel = embeddingModel;
        this.memoryProperties = memoryProperties;
        this.aiBlockingScheduler = aiBlockingScheduler;
        this.sessionCompressionService = sessionCompressionService;
        this.ownerInstance = resolveOwnerInstance();
    }

    @Scheduled(fixedDelayString = "${memory.async.fixed-delay-millis:3000}")
    public void schedule() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        processPendingTasks()
                .doFinally(signalType -> running.set(false))
                .subscribe(
                        ignored -> {
                        },
                        error -> log.warn("Session memory sync loop failed", error)
                );
    }

    private Mono<Void> processPendingTasks() {
        OffsetDateTime leaseExpiresAt = OffsetDateTime.now().plusSeconds(Math.max(30, memoryProperties.getAsync().getLeaseSeconds()));
        return learningSessionMemoryTaskStore.claimBatch(
                        Math.max(1, memoryProperties.getAsync().getBatchSize()),
                        ownerInstance,
                        leaseExpiresAt
                )
                .concatMap(this::processTask)
                .then();
    }

    private Mono<Void> processTask(SessionMemoryTaskRecord task) {
        return Mono.zip(
                        sessionStorageService.loadTurnsByTurnRange(task.conversationId(), task.turnIndex(), task.turnIndex()),
                        sessionStorageService.getSession(task.conversationId()),
                        sessionStorageService.loadRecentTurns(task.conversationId(), memoryProperties.getCompression().getRecentRawTurns() * 2)
                )
                .flatMap(tuple -> {
                    List<ConversationTurn> turns = tuple.getT1();
                    LearningSessionRecord session = tuple.getT2();
                    List<ConversationTurn> recentTurns = tuple.getT3();
                    if (turns.size() < 2) {
                        return Mono.error(new IllegalStateException("Missing persisted turn pair for task " + task.id()));
                    }
                    ConversationTurn userTurn = turns.get(0);
                    ConversationTurn assistantTurn = turns.get(1);
                    Map<String, Object> metadata = sessionStorageService.enrichMetadata(
                            Map.of("requestId", task.requestId()),
                            task.turnIndex()
                    );
                    Mono<Void> archiveTurnPair = memoryProperties.getLongTerm().isEnabled()
                            ? Mono.zip(embed(userTurn.content()), embed(assistantTurn.content()))
                            .flatMap(embeddingTuple -> conversationArchiveStore.archiveTurnPair(
                                    task.conversationId(),
                                    new SessionTurnPair(userTurn, assistantTurn),
                                    embeddingTuple.getT1(),
                                    embeddingTuple.getT2(),
                                    metadata,
                                    task.turnIndex()
                            ))
                            : Mono.empty();
                    return archiveTurnPair
                            .then(sessionCompressionService.handleTurn(
                                    session,
                                    task.turnIndex(),
                                    userTurn,
                                    assistantTurn,
                                    recentTurns
                            ))
                            .then(sessionStorageService.maybeArchiveSummary(task.conversationId(), task.turnIndex()));
                })
                .then(learningSessionMemoryTaskStore.markCompleted(task.id()))
                .onErrorResume(error -> {
                    log.warn("Session memory task failed. taskId={}, conversationId={}, requestId={}",
                            task.id(),
                            task.conversationId(),
                            task.requestId(),
                            error);
                    return learningSessionMemoryTaskStore.markFailed(
                            task.id(),
                            error.getMessage(),
                            OffsetDateTime.now().plusSeconds(Math.max(10, memoryProperties.getAsync().getRetryDelaySeconds()))
                    );
                });
    }

    private Mono<float[]> embed(String text) {
        return Mono.fromCallable(() -> embeddingModel.embed(text))
                .subscribeOn(aiBlockingScheduler);
    }

    private String resolveOwnerInstance() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID();
        } catch (Exception error) {
            return "memory-sync:" + UUID.randomUUID();
        }
    }
}
