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

    // 读取会话主记录；不存在时创建，存在时顺带刷新用户/主题等最新上下文。
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

    // 按 conversationId 读取会话主记录。
    public Mono<LearningSessionRecord> getByConversationId(String conversationId) {
        return repository.findFirstByConversationId(conversationId)
                .map(this::toRecord);
    }

    // 原子递增会话轮次，并返回新的 turnIndex。
    public Mono<Integer> reserveNextTurn(String conversationId, String currentTopic) {
        return repository.reserveNextTurn(conversationId, currentTopic, LearningSessionStatus.ACTIVE.name())
                .switchIfEmpty(Mono.error(new IllegalStateException("Session is missing or not active: " + conversationId)));
    }

    // 刷新会话活跃时间，可选同步更新当前主题。
    public Mono<Void> touch(String conversationId, String currentTopic) {
        return repository.touch(conversationId, currentTopic).then();
    }

    // 把会话轮次清零，通常用于重置运行态。
    public Mono<Void> resetTurnCount(String conversationId) {
        return repository.resetTurnCount(conversationId).then();
    }

    // 将会话标记为 CLOSED。
    public Mono<Void> close(String conversationId) {
        return repository.updateStatus(conversationId, LearningSessionStatus.CLOSED.name()).then();
    }

    // 为当前请求占用会话处理槽位，避免同一会话并发生成。
    public Mono<Boolean> acquireProcessingSlot(String conversationId, String requestId, OffsetDateTime leaseExpiresAt) {
        return repository.acquireProcessingSlot(conversationId, requestId, leaseExpiresAt, LearningSessionStatus.ACTIVE.name())
                .hasElement();
    }

    // 释放当前请求占用的处理槽位。
    public Mono<Void> releaseProcessingSlot(String conversationId, String requestId) {
        return repository.releaseProcessingSlot(conversationId, requestId).then();
    }

    // 记录摘要已经覆盖到哪一轮，避免重复归档。
    public Mono<Void> updateLastSummarizedTurn(String conversationId, int lastSummarizedTurn) {
        return repository.updateLastSummarizedTurn(conversationId, lastSummarizedTurn).then();
    }

    // 使用 CAS 推进摘要游标，避免并发归档时重复推进或回退。
    public Mono<Boolean> advanceLastSummarizedTurn(String conversationId,
                                                   int expectedLastSummarizedTurn,
                                                   int nextLastSummarizedTurn) {
        return repository.advanceLastSummarizedTurn(
                        conversationId,
                        expectedLastSummarizedTurn,
                        nextLastSummarizedTurn
                )
                .map(updatedRows -> updatedRows != null && updatedRows > 0)
                .defaultIfEmpty(false);
    }

    // 构造一条全新的会话主记录；并发创建时用唯一键兜底回查。
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
        entity.setProcessingRequestId(null);
        entity.setProcessingLeaseExpiresAt(null);
        entity.setStatus(LearningSessionStatus.ACTIVE.name());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setLastActiveAt(now);
        return repository.save(entity)
                .onErrorResume(DuplicateKeyException.class, error -> repository.findFirstByConversationId(conversationId));
    }

    // 复用旧会话时，把新的提示字段合并回主记录并刷新活跃时间。
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

    // 把空 sessionType 统一归一化为 QA。
    private String resolveSessionType(LearningSessionType sessionType) {
        return (sessionType == null ? LearningSessionType.QA : sessionType).name();
    }

    // 只有新值非空时才覆盖旧值。
    private String preferValue(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    // 对可空整型字段做默认值兜底。
    private Integer defaultInteger(Integer value) {
        return value == null ? 0 : value;
    }

    // 把持久化实体转换成应用层使用的会话记录对象。
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
                entity.getProcessingRequestId(),
                LearningSessionStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getLastActiveAt()
        );
    }
}
