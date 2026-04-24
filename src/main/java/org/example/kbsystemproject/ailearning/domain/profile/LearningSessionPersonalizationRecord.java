package org.example.kbsystemproject.ailearning.domain.profile;

import java.time.OffsetDateTime;
import java.util.List;

public record LearningSessionPersonalizationRecord(
        String conversationId,
        String userId,
        String currentTopic,
        List<String> recentTopics,
        OffsetDateTime updatedAt
) {
}
