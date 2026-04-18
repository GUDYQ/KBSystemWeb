package org.example.kbsystemproject.ailearning.application.service;

import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;

public record LearningChatCommand(
        String conversationId,
        String userId,
        String subject,
        LearningSessionType sessionType,
        String learningGoal,
        String currentTopic,
        String prompt
) {
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
