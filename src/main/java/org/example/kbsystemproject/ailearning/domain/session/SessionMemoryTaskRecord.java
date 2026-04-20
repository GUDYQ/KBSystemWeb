package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;

public record SessionMemoryTaskRecord(
        Long id,
        String conversationId,
        String requestId,
        Integer turnIndex,
        SessionMemoryTaskStatus status,
        String ownerInstance,
        OffsetDateTime leaseExpiresAt,
        Integer retryCount,
        OffsetDateTime availableAt,
        String lastError
) {
}
