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
        ConversationMode conversationMode,
        Integer turnCount,
        Integer lastSummarizedTurn,
        String processingRequestId,
        LearningSessionStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime lastActiveAt
) {
    public LearningSessionRecord(Long id,
                                 String conversationId,
                                 String userId,
                                 String subject,
                                 LearningSessionType sessionType,
                                 String learningGoal,
                                 String currentTopic,
                                 Integer turnCount,
                                 Integer lastSummarizedTurn,
                                 String processingRequestId,
                                 LearningSessionStatus status,
                                 OffsetDateTime createdAt,
                                 OffsetDateTime updatedAt,
                                 OffsetDateTime lastActiveAt) {
        this(
                id,
                conversationId,
                userId,
                subject,
                sessionType,
                learningGoal,
                currentTopic,
                ConversationMode.defaultMode(),
                turnCount,
                lastSummarizedTurn,
                processingRequestId,
                status,
                createdAt,
                updatedAt,
                lastActiveAt
        );
    }

    public ConversationMode normalizedConversationMode() {
        return ConversationMode.normalize(conversationMode);
    }
}
