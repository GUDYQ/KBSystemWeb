package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record MemoryCandidate(
        Long id,
        String userId,
        String conversationId,
        Integer turnIndex,
        String scope,
        String type,
        String memoryKey,
        String memoryValue,
        Double confidence,
        String sourceType,
        String candidateFingerprint,
        String status,
        List<String> evidence,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {
}
