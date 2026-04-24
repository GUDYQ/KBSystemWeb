package org.example.kbsystemproject.ailearning.application.service;

import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;

public record LearningChatCommand(
        String conversationId,
        String requestId,
        String userId,
        String subject,
        LearningSessionType sessionType,
        String learningGoal,
        String currentTopic,
        String prompt
) {
    public LearningChatCommand withCurrentTopic(String currentTopic) {
        return new LearningChatCommand(
                conversationId,
                requestId,
                userId,
                subject,
                sessionType,
                learningGoal,
                currentTopic,
                prompt
        );
    }

    public SessionOpenCommand toSessionOpenCommand() {
        return new SessionOpenCommand(
                conversationId,
                userId,
                subject,
                sessionType,
                learningGoal,
                currentTopic
        );
    }
}
