package org.example.kbsystemproject.ailearning.infrastructure.persistence.profile;

import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileTaskRecord;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemoryTaskStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

@Repository
public class LearningProfileTaskStore {

    private final DatabaseClient databaseClient;

    public LearningProfileTaskStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<Void> enqueueTurnSync(String conversationId, String requestId, int turnIndex) {
        return databaseClient.sql("""
                        INSERT INTO learning_profile_task (
                            conversation_id, request_id, turn_index, status, available_at, created_at, updated_at
                        )
                        VALUES (
                            :conversationId, :requestId, :turnIndex, :status, NOW(), NOW(), NOW()
                        )
                        ON CONFLICT (conversation_id, request_id) DO NOTHING
                        """)
                .bind("conversationId", conversationId)
                .bind("requestId", requestId)
                .bind("turnIndex", turnIndex)
                .bind("status", SessionMemoryTaskStatus.PENDING.name())
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Flux<LearningProfileTaskRecord> claimBatch(int limit, String ownerInstance, OffsetDateTime leaseExpiresAt) {
        return databaseClient.sql("""
                        UPDATE learning_profile_task task
                        SET status = :processingStatus,
                            owner_instance = :ownerInstance,
                            lease_expires_at = :leaseExpiresAt,
                            updated_at = NOW()
                        WHERE task.id IN (
                            SELECT id
                            FROM learning_profile_task
                            WHERE (
                                    status IN ('PENDING', 'FAILED')
                                    AND available_at <= NOW()
                                  )
                               OR (
                                    status = 'PROCESSING'
                                    AND lease_expires_at IS NOT NULL
                                    AND lease_expires_at < NOW()
                                  )
                            ORDER BY available_at ASC, created_at ASC
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        RETURNING id, conversation_id, request_id, turn_index, status, owner_instance, lease_expires_at,
                                  retry_count, available_at, last_error
                        """)
                .bind("processingStatus", SessionMemoryTaskStatus.PROCESSING.name())
                .bind("ownerInstance", ownerInstance)
                .bind("leaseExpiresAt", leaseExpiresAt)
                .bind("limit", limit)
                .map((row, metadata) -> new LearningProfileTaskRecord(
                        row.get("id", Long.class),
                        row.get("conversation_id", String.class),
                        row.get("request_id", String.class),
                        row.get("turn_index", Integer.class),
                        SessionMemoryTaskStatus.valueOf(row.get("status", String.class)),
                        row.get("owner_instance", String.class),
                        row.get("lease_expires_at", OffsetDateTime.class),
                        row.get("retry_count", Integer.class),
                        row.get("available_at", OffsetDateTime.class),
                        row.get("last_error", String.class)
                ))
                .all();
    }

    public Mono<Void> markCompleted(Long taskId) {
        return databaseClient.sql("""
                        UPDATE learning_profile_task
                        SET status = :status,
                            owner_instance = NULL,
                            lease_expires_at = NULL,
                            updated_at = NOW()
                        WHERE id = :taskId
                        """)
                .bind("status", SessionMemoryTaskStatus.COMPLETED.name())
                .bind("taskId", taskId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Void> markFailed(Long taskId, String lastError, OffsetDateTime availableAt) {
        return databaseClient.sql("""
                        UPDATE learning_profile_task
                        SET status = :status,
                            owner_instance = NULL,
                            lease_expires_at = NULL,
                            retry_count = retry_count + 1,
                            available_at = :availableAt,
                            last_error = :lastError,
                            updated_at = NOW()
                        WHERE id = :taskId
                        """)
                .bind("status", SessionMemoryTaskStatus.FAILED.name())
                .bind("availableAt", availableAt)
                .bind("lastError", truncate(lastError))
                .bind("taskId", taskId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }
}
