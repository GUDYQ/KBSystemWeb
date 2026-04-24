package org.example.kbsystemproject.ailearning.domain.profile;

import java.util.List;

public record LearningProfileContext(
        String learningGoal,
        String preferredStyle,
        String preferredLanguage,
        List<String> weakPoints,
        String currentTopic,
        List<String> recentTopics
) {
    public static final LearningProfileContext EMPTY = new LearningProfileContext(
            null,
            null,
            null,
            List.of(),
            null,
            List.of()
    );

    public boolean isEmpty() {
        return (learningGoal == null || learningGoal.isBlank())
                && (preferredStyle == null || preferredStyle.isBlank())
                && (preferredLanguage == null || preferredLanguage.isBlank())
                && (weakPoints == null || weakPoints.isEmpty())
                && (currentTopic == null || currentTopic.isBlank())
                && (recentTopics == null || recentTopics.isEmpty());
    }
}
