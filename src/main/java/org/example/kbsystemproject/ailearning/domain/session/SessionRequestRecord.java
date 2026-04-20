package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;

public record SessionRequestRecord(
        Long id,
        String conversationId,
        String requestId,
        SessionRequestStatus status,
        String ownerInstance,
        OffsetDateTime leaseExpiresAt,
        Integer turnIndex,
        String assistantContent,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
