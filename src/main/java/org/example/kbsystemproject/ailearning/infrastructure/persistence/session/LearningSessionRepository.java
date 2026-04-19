package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface LearningSessionRepository extends ReactiveCrudRepository<LearningSessionEntity, Long> {

    Mono<LearningSessionEntity> findFirstByConversationId(String conversationId);

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

    @Modifying
    @Query("""
            UPDATE learning_session
            SET last_active_at = NOW(),
                updated_at = NOW(),
                current_topic = COALESCE(:currentTopic, current_topic)
            WHERE conversation_id = :conversationId
            """)
    Mono<Integer> touch(String conversationId, String currentTopic);

    @Modifying
    @Query("""
            UPDATE learning_session
            SET turn_count = 0,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
            """)
    Mono<Integer> resetTurnCount(String conversationId);

    @Modifying
    @Query("""
            UPDATE learning_session
            SET status = :status,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
            """)
    Mono<Integer> updateStatus(String conversationId, String status);

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

    @Modifying
    @Query("""
            UPDATE learning_session
            SET last_summarized_turn = :lastSummarizedTurn,
                updated_at = NOW(),
                last_active_at = NOW()
            WHERE conversation_id = :conversationId
            """)
    Mono<Integer> updateLastSummarizedTurn(String conversationId, Integer lastSummarizedTurn);
}
