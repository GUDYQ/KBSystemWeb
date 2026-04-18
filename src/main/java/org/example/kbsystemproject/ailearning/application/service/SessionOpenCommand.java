package org.example.kbsystemproject.ailearning.application.service;

import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;

public record SessionOpenCommand(
        String conversationId,
        String userId,
        String subject,
        LearningSessionType sessionType,
        String learningGoal,
        String currentTopic
) {
}
