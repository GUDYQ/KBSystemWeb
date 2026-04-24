package org.example.kbsystemproject.ailearning.interfaces.http.response;

import java.util.List;

public record LearningProfileResponse(
        String userId,
        String subject,
        String learningGoal,
        String preferredStyle,
        String preferredLanguage,
        List<String> weakPoints,
        String currentTopic,
        List<String> recentTopics
) {
}
