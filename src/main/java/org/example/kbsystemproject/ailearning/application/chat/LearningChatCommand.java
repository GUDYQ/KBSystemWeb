package org.example.kbsystemproject.ailearning.application.chat;

import org.example.kbsystemproject.ailearning.application.session.SessionOpenCommand;
import org.example.kbsystemproject.ailearning.domain.session.ConversationMode;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;

public record LearningChatCommand(
        String conversationId,
        String requestId,
        String userId,
        String subject,
        LearningSessionType sessionType,
        String learningGoal,
        String currentTopic,
        ConversationMode conversationMode,
        String prompt
) {
    public LearningChatCommand(String conversationId,
                               String requestId,
                               String userId,
                               String subject,
                               LearningSessionType sessionType,
                               String learningGoal,
                               String currentTopic,
                               String prompt) {
        this(
                conversationId,
                requestId,
                userId,
                subject,
                sessionType,
                learningGoal,
                currentTopic,
                ConversationMode.defaultMode(),
                prompt
        );
    }

    public LearningChatCommand withCurrentTopic(String currentTopic) {
        return new LearningChatCommand(
                conversationId,
                requestId,
                userId,
                subject,
                sessionType,
                learningGoal,
                currentTopic,
                conversationMode,
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
                currentTopic,
                conversationMode
        );
    }
}

