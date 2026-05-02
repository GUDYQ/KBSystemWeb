package org.example.kbsystemproject.ailearning.domain.session;

import java.util.List;

public record SessionMemorySnapshot(
        LearningSessionRecord session,
        List<ConversationTurn> shortTermMemory,
        List<LongTermMemoryEntry> longTermMemory,
        SessionWhiteboard whiteboard,
        SessionTopicBlock activeTopicBlock,
        List<ToolMemoryEntry> recentToolMemories
) {
    public SessionMemorySnapshot(LearningSessionRecord session,
                                 List<ConversationTurn> shortTermMemory,
                                 List<LongTermMemoryEntry> longTermMemory) {
        this(session, shortTermMemory, longTermMemory, null, null, List.of());
    }

    public SessionMemorySnapshot(LearningSessionRecord session,
                                 List<ConversationTurn> shortTermMemory,
                                 List<LongTermMemoryEntry> longTermMemory,
                                 SessionTopicBlock activeTopicBlock,
                                 List<ToolMemoryEntry> recentToolMemories) {
        this(session, shortTermMemory, longTermMemory, null, activeTopicBlock, recentToolMemories);
    }

    public SessionMemorySnapshot(LearningSessionRecord session,
                                 List<ConversationTurn> shortTermMemory,
                                 List<LongTermMemoryEntry> longTermMemory,
                                 SessionWhiteboard whiteboard,
                                 SessionTopicBlock activeTopicBlock) {
        this(session, shortTermMemory, longTermMemory, whiteboard, activeTopicBlock, List.of());
    }

    public SessionMemorySnapshot(LearningSessionRecord session,
                                 List<ConversationTurn> shortTermMemory,
                                 List<LongTermMemoryEntry> longTermMemory,
                                 SessionTopicBlock activeTopicBlock) {
        this(session, shortTermMemory, longTermMemory, null, activeTopicBlock, List.of());
    }
}
