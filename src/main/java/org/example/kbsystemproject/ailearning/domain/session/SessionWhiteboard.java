package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record SessionWhiteboard(
        String conversationId,
        Integer version,
        String currentFocus,
        String userGoal,
        List<String> constraints,
        List<String> decisions,
        List<String> openQuestions,
        List<String> recentToolFindings,
        String continuityState,
        Double continuityConfidence,
        String rawSummary,
        Map<String, Object> metadata,
        OffsetDateTime updatedAt
) {
    public static SessionWhiteboard empty(String conversationId) {
        return new SessionWhiteboard(
                conversationId,
                0,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "EMPTY",
                0D,
                null,
                Map.of(),
                null
        );
    }
}
