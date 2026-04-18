package org.example.kbsystemproject.ailearning.domain.session;

import java.util.List;

public record SessionMemorySnapshot(
        LearningSessionRecord session,
        List<ConversationTurn> shortTermMemory,
        List<LongTermMemoryEntry> longTermMemory
) {
}
