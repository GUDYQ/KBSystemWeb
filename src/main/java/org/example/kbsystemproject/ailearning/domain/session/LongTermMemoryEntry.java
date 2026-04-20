package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;
import java.util.Map;

public record LongTermMemoryEntry(
        String id,
        String conversationId,
        String memoryType,
        String content,
        Double score,
        OffsetDateTime createdAt,
        Map<String, Object> metadata
) {
}
