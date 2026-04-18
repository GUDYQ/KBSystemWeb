package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;

public record LearningSessionRecord(
        Long id,
        String conversationId,
        String userId,
        String subject,
        LearningSessionType sessionType,
        String learningGoal,
        String currentTopic,
        Integer turnCount,
        Integer lastSummarizedTurn,
        LearningSessionStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime lastActiveAt
) {
}
