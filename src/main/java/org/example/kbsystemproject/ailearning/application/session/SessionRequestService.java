package org.example.kbsystemproject.ailearning.application.session;

import org.example.kbsystemproject.ailearning.domain.session.SessionRequestDecision;
import org.example.kbsystemproject.ailearning.domain.session.SessionRequestRecord;
import org.example.kbsystemproject.ailearning.domain.session.SessionRequestStatus;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionRequestStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class SessionRequestService {

    private final LearningSessionStore learningSessionStore;
    private final LearningSessionRequestStore learningSessionRequestStore;
    private final MemoryProperties memoryProperties;
    private final String ownerInstance;

    public SessionRequestService(LearningSessionStore learningSessionStore,
                                 LearningSessionRequestStore learningSessionRequestStore,
                                 MemoryProperties memoryProperties) {
        this.learningSessionStore = learningSessionStore;
        this.learningSessionRequestStore = learningSessionRequestStore;
        this.memoryProperties = memoryProperties;
        this.ownerInstance = resolveOwnerInstance();
    }

    public Mono<SessionRequestDecision> beginRequest(String conversationId, String requestId) {
        return learningSessionRequestStore.findByConversationIdAndRequestId(conversationId, requestId)
                .flatMap(existing -> {
                    if (existing.status() == SessionRequestStatus.SUCCEEDED) {
                        return Mono.just(SessionRequestDecision.completed(existing));
                    }
                    if (existing.status() == SessionRequestStatus.PROCESSING
                            && existing.leaseExpiresAt() != null
                            && existing.leaseExpiresAt().isAfter(OffsetDateTime.now())) {
                        return Mono.just(SessionRequestDecision.processing(existing));
                    }
                    return acquireRequest(conversationId, requestId);
                })
                .switchIfEmpty(acquireRequest(conversationId, requestId));
    }

    public Mono<Void> markFailed(String conversationId, String requestId, Throwable error) {
        return learningSessionRequestStore.markFailed(conversationId, requestId, error == null ? null : error.getMessage())
                .then(learningSessionStore.releaseProcessingSlot(conversationId, requestId));
    }

    private Mono<SessionRequestDecision> acquireRequest(String conversationId, String requestId) {
        OffsetDateTime leaseExpiresAt = OffsetDateTime.now()
                .plusSeconds(Math.max(30, memoryProperties.getRequest().getProcessingLeaseSeconds()));
        return learningSessionStore.acquireProcessingSlot(conversationId, requestId, leaseExpiresAt)
                .flatMap(acquired -> acquired
                        ? learningSessionRequestStore.upsertProcessing(conversationId, requestId, ownerInstance, leaseExpiresAt)
                        .map(SessionRequestDecision::acquired)
                        .switchIfEmpty(learningSessionRequestStore.findByConversationIdAndRequestId(conversationId, requestId)
                                .map(SessionRequestDecision::completed))
                        .onErrorResume(error -> learningSessionStore.releaseProcessingSlot(conversationId, requestId)
                                .then(Mono.error(error)))
                        : learningSessionRequestStore.findByConversationIdAndRequestId(conversationId, requestId)
                        .map(SessionRequestDecision::processing)
                        .defaultIfEmpty(SessionRequestDecision.conversationBusy()));
    }

    private String resolveOwnerInstance() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID();
        } catch (Exception error) {
            return "instance:" + UUID.randomUUID();
        }
    }
}

