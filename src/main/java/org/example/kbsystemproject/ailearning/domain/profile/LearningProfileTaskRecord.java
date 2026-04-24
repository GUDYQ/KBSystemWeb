package org.example.kbsystemproject.ailearning.domain.profile;

import org.example.kbsystemproject.ailearning.domain.session.SessionMemoryTaskStatus;

import java.time.OffsetDateTime;

public record LearningProfileTaskRecord(
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
