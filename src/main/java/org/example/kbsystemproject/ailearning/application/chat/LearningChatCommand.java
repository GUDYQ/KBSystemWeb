package org.example.kbsystemproject.ailearning.application.chat;

import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;
import org.example.kbsystemproject.ailearning.application.session.SessionOpenCommand;

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

