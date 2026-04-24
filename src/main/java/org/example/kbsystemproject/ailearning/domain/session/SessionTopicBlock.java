package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;

public record SessionTopicBlock(
        Long id,
        String conversationId,
        String topic,
        String status,
        int startTurn,
        int lastTurn,
        int turnCount,
        int stableScore,
        int unresolvedCount,
        double lowInfoRatio,
        String summary,
        OffsetDateTime updatedAt
) {
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
