package org.example.kbsystemproject.ailearning.application.session;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemoryTaskRecord;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionMemoryTaskStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class SessionMemorySynchronizationService {

    private final LearningSessionMemoryTaskStore learningSessionMemoryTaskStore;
    private final MemoryProperties memoryProperties;
    private final SessionMemoryOrchestrator sessionMemoryOrchestrator;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String ownerInstance;

    public SessionMemorySynchronizationService(LearningSessionMemoryTaskStore learningSessionMemoryTaskStore,
                                               MemoryProperties memoryProperties,
                                               SessionMemoryOrchestrator sessionMemoryOrchestrator) {
        this.learningSessionMemoryTaskStore = learningSessionMemoryTaskStore;
        this.memoryProperties = memoryProperties;
        this.sessionMemoryOrchestrator = sessionMemoryOrchestrator;
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
        return sessionMemoryOrchestrator.processTurn(task.conversationId(), task.requestId(), task.turnIndex())
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

    private String resolveOwnerInstance() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID();
        } catch (Exception error) {
            return "memory-sync:" + UUID.randomUUID();
        }
    }
}

