package org.example.kbsystemproject.ailearning.application.service;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
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
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String ownerInstance;

    public SessionMemorySynchronizationService(LearningSessionMemoryTaskStore learningSessionMemoryTaskStore,
                                               SessionStorageService sessionStorageService,
                                               ConversationArchiveStore conversationArchiveStore,
                                               EmbeddingModel embeddingModel,
                                               MemoryProperties memoryProperties,
                                               @Qualifier("aiBlockingScheduler") Scheduler aiBlockingScheduler) {
        this.learningSessionMemoryTaskStore = learningSessionMemoryTaskStore;
        this.sessionStorageService = sessionStorageService;
        this.conversationArchiveStore = conversationArchiveStore;
        this.embeddingModel = embeddingModel;
        this.memoryProperties = memoryProperties;
        this.aiBlockingScheduler = aiBlockingScheduler;
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
        return sessionStorageService.loadTurnsByTurnRange(task.conversationId(), task.turnIndex(), task.turnIndex())
                .flatMap(turns -> {
                    if (turns.size() < 2) {
                        return Mono.error(new IllegalStateException("Missing persisted turn pair for task " + task.id()));
                    }
                    ConversationTurn userTurn = turns.get(0);
                    ConversationTurn assistantTurn = turns.get(1);
                    Map<String, Object> metadata = sessionStorageService.enrichMetadata(
                            Map.of("requestId", task.requestId()),
                            task.turnIndex()
                    );
                    return Mono.zip(embed(userTurn.content()), embed(assistantTurn.content()))
                            .flatMap(tuple -> conversationArchiveStore.archiveTurnPair(
                                    task.conversationId(),
                                    new SessionTurnPair(userTurn, assistantTurn),
                                    tuple.getT1(),
                                    tuple.getT2(),
                                    metadata,
                                    task.turnIndex()
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
