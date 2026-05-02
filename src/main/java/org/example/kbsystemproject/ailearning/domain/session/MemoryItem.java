package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;
import java.util.Map;

public record MemoryItem(
        Long id,
        String userId,
        String scope,
        String type,
        String memoryKey,
        String memoryValue,
        Double confidence,
        String status,
        String sourceType,
        Integer evidenceCount,
        Long supersedesId,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime lastSeenAt
) {
}
