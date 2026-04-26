package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;

public record ToolMemoryEntry(
        Integer turnIndex,
        Integer stepIndex,
        String toolName,
        String summary,
        ToolExecutionStatus status,
        OffsetDateTime createdAt
) {
}
