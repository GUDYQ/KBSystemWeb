package org.example.kbsystemproject.ailearning.application.session;

import org.example.kbsystemproject.ailearning.domain.session.ConversationMode;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;

public record SessionOpenCommand(
        String conversationId,
        String userId,
        String subject,
        LearningSessionType sessionType,
        String learningGoal,
        String currentTopic,
        ConversationMode conversationMode
) {
    public SessionOpenCommand(String conversationId,
                              String userId,
                              String subject,
                              LearningSessionType sessionType,
                              String learningGoal,
                              String currentTopic) {
        this(
                conversationId,
                userId,
                subject,
                sessionType,
                learningGoal,
                currentTopic,
                ConversationMode.defaultMode()
        );
    }
}

