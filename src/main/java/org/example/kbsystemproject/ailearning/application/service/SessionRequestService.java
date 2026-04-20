package org.example.kbsystemproject.ailearning.application.service;

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
                .flatMap(existing -> handleExisting(conversationId, requestId, existing))
                .switchIfEmpty(acquireForNewRequest(conversationId, requestId));
    }

    public Mono<Void> markFailed(String conversationId, String requestId, Throwable error) {
        return learningSessionRequestStore.markFailed(conversationId, requestId, error == null ? null : error.getMessage())
                .then(learningSessionStore.releaseProcessingSlot(conversationId, requestId));
    }

    private Mono<SessionRequestDecision> handleExisting(String conversationId,
                                                        String requestId,
                                                        SessionRequestRecord existing) {
        if (existing.status() == SessionRequestStatus.SUCCEEDED) {
            return Mono.just(SessionRequestDecision.completed(existing));
        }
        if (existing.status() == SessionRequestStatus.PROCESSING && isLeaseActive(existing)) {
            return Mono.just(SessionRequestDecision.processing(existing));
        }
        return acquireForNewRequest(conversationId, requestId);
    }

    private Mono<SessionRequestDecision> acquireForNewRequest(String conversationId, String requestId) {
        OffsetDateTime leaseExpiresAt = requestLeaseExpiresAt();
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

    private boolean isLeaseActive(SessionRequestRecord record) {
        return record.leaseExpiresAt() != null && record.leaseExpiresAt().isAfter(OffsetDateTime.now());
    }

    private OffsetDateTime requestLeaseExpiresAt() {
        return OffsetDateTime.now().plusSeconds(Math.max(30, memoryProperties.getRequest().getProcessingLeaseSeconds()));
    }

    private String resolveOwnerInstance() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID();
        } catch (Exception error) {
            return "instance:" + UUID.randomUUID();
        }
    }
}
