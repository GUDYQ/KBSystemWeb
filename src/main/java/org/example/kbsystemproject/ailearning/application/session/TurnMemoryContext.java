package org.example.kbsystemproject.ailearning.application.session;

import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.SessionWhiteboard;
import org.example.kbsystemproject.ailearning.domain.session.ToolMemoryEntry;

import java.util.List;

record TurnMemoryContext(LearningSessionRecord session,
                         String requestId,
                         int turnIndex,
                         ConversationTurn userTurn,
                         ConversationTurn assistantTurn,
                         List<ConversationTurn> recentTurns,
                         SessionWhiteboard existingWhiteboard,
                         List<ToolMemoryEntry> recentToolMemories) {
}
