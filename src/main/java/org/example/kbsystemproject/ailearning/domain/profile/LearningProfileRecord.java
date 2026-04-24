package org.example.kbsystemproject.ailearning.domain.profile;

import java.time.OffsetDateTime;
import java.util.List;

public record LearningProfileRecord(
        Long id,
        String userId,
        String subject,
        String learningGoal,
        String preferredStyle,
        String preferredLanguage,
        List<String> weakPoints,
        OffsetDateTime updatedAt
) {
}
