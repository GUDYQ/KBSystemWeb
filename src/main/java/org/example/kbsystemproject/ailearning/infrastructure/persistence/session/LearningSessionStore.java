package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionStatus;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

@Service
public class LearningSessionStore {

    private final LearningSessionRepository repository;

    public LearningSessionStore(LearningSessionRepository repository) {
        this.repository = repository;
    }

    public Mono<LearningSessionRecord> getOrCreate(String conversationId,
                                                   String userId,
                                                   String subject,
                                                   LearningSessionType sessionType,
                                                   String learningGoal,
                                                   String currentTopic) {
        return repository.findFirstByConversationId(conversationId)
                .switchIfEmpty(createSession(conversationId, userId, subject, sessionType, learningGoal, currentTopic))
                .flatMap(entity -> refreshSession(entity, userId, subject, sessionType, learningGoal, currentTopic))
                .map(this::toRecord);
    }

    public Mono<LearningSessionRecord> getByConversationId(String conversationId) {
        return repository.findFirstByConversationId(conversationId)
                .map(this::toRecord);
    }

    public Mono<Integer> reserveNextTurn(String conversationId, String currentTopic) {
        return repository.reserveNextTurn(conversationId, currentTopic, LearningSessionStatus.ACTIVE.name())
                .switchIfEmpty(Mono.error(new IllegalStateException("Session is missing or not active: " + conversationId)));
    }

    public Mono<Void> touch(String conversationId, String currentTopic) {
        return repository.touch(conversationId, currentTopic).then();
    }

    public Mono<Void> resetTurnCount(String conversationId) {
        return repository.resetTurnCount(conversationId).then();
    }

    public Mono<Void> close(String conversationId) {
        return repository.updateStatus(conversationId, LearningSessionStatus.CLOSED.name()).then();
    }

    public Mono<Void> updateLastSummarizedTurn(String conversationId, int lastSummarizedTurn) {
        return repository.updateLastSummarizedTurn(conversationId, lastSummarizedTurn).then();
    }

    private Mono<LearningSessionEntity> createSession(String conversationId,
                                                      String userId,
                                                      String subject,
                                                      LearningSessionType sessionType,
                                                      String learningGoal,
                                                      String currentTopic) {
        LearningSessionEntity entity = new LearningSessionEntity();
        OffsetDateTime now = OffsetDateTime.now();
        entity.setConversationId(conversationId);
        entity.setUserId(userId);
        entity.setSubject(subject);
        entity.setSessionType(resolveSessionType(sessionType));
        entity.setLearningGoal(learningGoal);
        entity.setCurrentTopic(currentTopic);
        entity.setTurnCount(0);
        entity.setLastSummarizedTurn(0);
        entity.setStatus(LearningSessionStatus.ACTIVE.name());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setLastActiveAt(now);
        return repository.save(entity)
                .onErrorResume(DuplicateKeyException.class, error -> repository.findFirstByConversationId(conversationId));
    }

    private Mono<LearningSessionEntity> refreshSession(LearningSessionEntity entity,
                                                       String userId,
                                                       String subject,
                                                       LearningSessionType sessionType,
                                                       String learningGoal,
                                                       String currentTopic) {
        entity.setUserId(preferValue(userId, entity.getUserId()));
        entity.setSubject(preferValue(subject, entity.getSubject()));
        entity.setSessionType(resolveSessionType(sessionType));
        entity.setLearningGoal(preferValue(learningGoal, entity.getLearningGoal()));
        entity.setCurrentTopic(preferValue(currentTopic, entity.getCurrentTopic()));
        entity.setStatus(LearningSessionStatus.ACTIVE.name());
        entity.setLastSummarizedTurn(defaultInteger(entity.getLastSummarizedTurn()));
        OffsetDateTime now = OffsetDateTime.now();
        entity.setUpdatedAt(now);
        entity.setLastActiveAt(now);
        return repository.save(entity);
    }

    private String resolveSessionType(LearningSessionType sessionType) {
        return (sessionType == null ? LearningSessionType.QA : sessionType).name();
    }

    private String preferValue(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private Integer defaultInteger(Integer value) {
        return value == null ? 0 : value;
    }

    private LearningSessionRecord toRecord(LearningSessionEntity entity) {
        return new LearningSessionRecord(
                entity.getId(),
                entity.getConversationId(),
                entity.getUserId(),
                entity.getSubject(),
                LearningSessionType.valueOf(entity.getSessionType()),
                entity.getLearningGoal(),
                entity.getCurrentTopic(),
                entity.getTurnCount(),
                defaultInteger(entity.getLastSummarizedTurn()),
                LearningSessionStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastActiveAt()
        );
    }
}
