package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import org.example.kbsystemproject.ailearning.domain.session.SessionRequestRecord;
import org.example.kbsystemproject.ailearning.domain.session.SessionRequestStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

@Repository
public class LearningSessionRequestStore {

    private final DatabaseClient databaseClient;

    public LearningSessionRequestStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<SessionRequestRecord> findByConversationIdAndRequestId(String conversationId, String requestId) {
        return databaseClient.sql("""
                        SELECT id, conversation_id, request_id, status, owner_instance, lease_expires_at,
                               turn_index, assistant_content, error_message, created_at, updated_at
                        FROM learning_session_request
                        WHERE conversation_id = :conversationId
                          AND request_id = :requestId
                        """)
                .bind("conversationId", conversationId)
                .bind("requestId", requestId)
                .map((row, metadata) -> mapRecord(row))
                .one();
    }

    public Mono<SessionRequestRecord> upsertProcessing(String conversationId,
                                                       String requestId,
                                                       String ownerInstance,
                                                       OffsetDateTime leaseExpiresAt) {
        return databaseClient.sql("""
                        INSERT INTO learning_session_request (
                            conversation_id, request_id, status, owner_instance, lease_expires_at, created_at, updated_at
                        )
                        VALUES (
                            :conversationId, :requestId, :status, :ownerInstance, :leaseExpiresAt, NOW(), NOW()
                        )
                        ON CONFLICT (conversation_id, request_id)
                        DO UPDATE SET
                            status = EXCLUDED.status,
                            owner_instance = EXCLUDED.owner_instance,
                            lease_expires_at = EXCLUDED.lease_expires_at,
                            error_message = NULL,
                            updated_at = NOW()
                        WHERE learning_session_request.status <> 'SUCCEEDED'
                        RETURNING id, conversation_id, request_id, status, owner_instance, lease_expires_at,
                                  turn_index, assistant_content, error_message, created_at, updated_at
                        """)
                .bind("conversationId", conversationId)
                .bind("requestId", requestId)
                .bind("status", SessionRequestStatus.PROCESSING.name())
                .bind("ownerInstance", ownerInstance)
                .bind("leaseExpiresAt", leaseExpiresAt)
                .map((row, metadata) -> mapRecord(row))
                .one();
    }

    public Mono<Void> markSucceeded(String conversationId,
                                    String requestId,
                                    int turnIndex,
                                    String assistantContent) {
        return databaseClient.sql("""
                        UPDATE learning_session_request
                        SET status = :status,
                            turn_index = :turnIndex,
                            assistant_content = :assistantContent,
                            error_message = NULL,
                            lease_expires_at = NULL,
                            updated_at = NOW()
                        WHERE conversation_id = :conversationId
                          AND request_id = :requestId
                        """)
                .bind("status", SessionRequestStatus.SUCCEEDED.name())
                .bind("turnIndex", turnIndex)
                .bind("assistantContent", assistantContent)
                .bind("conversationId", conversationId)
                .bind("requestId", requestId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Void> markFailed(String conversationId,
                                 String requestId,
                                 String errorMessage) {
        return databaseClient.sql("""
                        UPDATE learning_session_request
                        SET status = :status,
                            error_message = :errorMessage,
                            lease_expires_at = NULL,
                            updated_at = NOW()
                        WHERE conversation_id = :conversationId
                          AND request_id = :requestId
                          AND status = 'PROCESSING'
                        """)
                .bind("status", SessionRequestStatus.FAILED.name())
                .bind("errorMessage", truncate(errorMessage))
                .bind("conversationId", conversationId)
                .bind("requestId", requestId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private SessionRequestRecord mapRecord(io.r2dbc.spi.Readable row) {
        return new SessionRequestRecord(
                row.get("id", Long.class),
                row.get("conversation_id", String.class),
                row.get("request_id", String.class),
                SessionRequestStatus.valueOf(row.get("status", String.class)),
                row.get("owner_instance", String.class),
                row.get("lease_expires_at", OffsetDateTime.class),
                row.get("turn_index", Integer.class),
                row.get("assistant_content", String.class),
                row.get("error_message", String.class),
                row.get("created_at", OffsetDateTime.class),
                row.get("updated_at", OffsetDateTime.class)
        );
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }
}
