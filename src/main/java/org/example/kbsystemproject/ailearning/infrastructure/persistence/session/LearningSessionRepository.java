package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface LearningSessionRepository extends ReactiveCrudRepository<LearningSessionEntity, Long> {

    // 按会话 ID 读取主记录。
    Mono<LearningSessionEntity> findFirstByConversationId(String conversationId);

    // 原子递增 turn_count，并返回新的轮次编号。
    @Query("""
            UPDATE learning_session
            SET turn_count = turn_count + 1,
                updated_at = NOW(),
                last_active_at = NOW(),
                current_topic = COALESCE(:currentTopic, current_topic)
            WHERE conversation_id = :conversationId
              AND status = :status
            RETURNING turn_count
            """)
    Mono<Integer> reserveNextTurn(String conversationId, String currentTopic, String status);

    // 刷新活跃时间，并可选更新当前主题。
    @Modifying
    @Query("""
            UPDATE learning_session
            SET last_active_at = NOW(),
                updated_at = NOW(),
                current_topic = COALESCE(:currentTopic, current_topic)
            WHERE conversation_id = :conversationId
            """)
    Mono<Integer> touch(String conversationId, String currentTopic);

    // 把会话轮次重置为 0。
    @Modifying
    @Query("""
            UPDATE learning_session
            SET turn_count = 0,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
            """)
    Mono<Integer> resetTurnCount(String conversationId);

    // 更新会话状态，例如 ACTIVE/CLOSED。
    @Modifying
    @Query("""
            UPDATE learning_session
            SET status = :status,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
            """)
    Mono<Integer> updateStatus(String conversationId, String status);

    // 尝试抢占当前会话的处理槽位，成功时返回 conversationId。
    @Query("""
            UPDATE learning_session
            SET processing_request_id = :requestId,
                processing_lease_expires_at = :leaseExpiresAt,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
              AND status = :status
              AND (
                    processing_request_id IS NULL
                    OR processing_request_id = :requestId
                    OR processing_lease_expires_at IS NULL
                    OR processing_lease_expires_at < NOW()
              )
            RETURNING conversation_id
            """)
    Mono<String> acquireProcessingSlot(String conversationId,
                                       String requestId,
                                       java.time.OffsetDateTime leaseExpiresAt,
                                       String status);

    // 释放当前请求持有的处理槽位。
    @Modifying
    @Query("""
            UPDATE learning_session
            SET processing_request_id = NULL,
                processing_lease_expires_at = NULL,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
              AND processing_request_id = :requestId
            """)
    Mono<Integer> releaseProcessingSlot(String conversationId, String requestId);

    // 更新最近一次已完成摘要的轮次。
    @Modifying
    @Query("""
            UPDATE learning_session
            SET last_summarized_turn = :lastSummarizedTurn,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
            """)
    Mono<Integer> updateLastSummarizedTurn(String conversationId, Integer lastSummarizedTurn);

    // 只有当前游标仍等于 expectedLastSummarizedTurn 时，才推进到 nextLastSummarizedTurn。
    @Modifying
    @Query("""
            UPDATE learning_session
            SET last_summarized_turn = :nextLastSummarizedTurn,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
              AND last_summarized_turn = :expectedLastSummarizedTurn
            """)
    Mono<Integer> advanceLastSummarizedTurn(String conversationId,
                                            Integer expectedLastSummarizedTurn,
                                            Integer nextLastSummarizedTurn);
}
