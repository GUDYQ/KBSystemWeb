package org.example.kbsystemproject.ailearning.application.session;

import org.example.kbsystemproject.ailearning.domain.session.ConversationMode;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.SessionWhiteboard;
import org.example.kbsystemproject.ailearning.domain.session.ToolMemoryEntry;
import org.example.kbsystemproject.config.MemoryProperties;

import java.util.List;

class MemoryTriggerPolicy {

    private final MemoryProperties memoryProperties;

    MemoryTriggerPolicy(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties;
    }

    MemoryProcessingPlan decide(TurnMemoryContext context) {
        SessionWhiteboard whiteboard = context.existingWhiteboard();
        int lastCompressionTurn = readMetadataInt(whiteboard, "lastCompressionTurn");
        int lastLongTermCollectionTurn = readMetadataInt(whiteboard, "lastLongTermCollectionTurn");
        int currentTurnTokenEstimate = estimateTurnTokenCount(context.userTurn()) + estimateTurnTokenCount(context.assistantTurn());
        int currentTurnToolCount = countCurrentTurnTools(context.recentToolMemories(), context.turnIndex());
        boolean missingWhiteboard = whiteboard == null
                || whiteboard.version() == null
                || whiteboard.version() <= 0
                || (whiteboard.currentFocus() == null || whiteboard.currentFocus().isBlank())
                && (whiteboard.rawSummary() == null || whiteboard.rawSummary().isBlank());
        int whiteboardRefreshTurns = Math.max(1, memoryProperties.getCompression().getWhiteboardRefreshTurns());
        int whiteboardRefreshMinTokens = Math.max(1, memoryProperties.getCompression().getWhiteboardRefreshMinTokens());
        boolean fullCompression = missingWhiteboard
                || context.turnIndex() <= 1
                || currentTurnToolCount > 0
                || currentTurnTokenEstimate >= whiteboardRefreshMinTokens
                || context.turnIndex() - lastCompressionTurn >= whiteboardRefreshTurns;
        int longTermCollectionTurns = Math.max(1, memoryProperties.getLongTerm().getCollectionTurns());
        int longTermCollectionMinTokens = Math.max(1, memoryProperties.getLongTerm().getCollectionMinTokens());
        boolean longTermCollection = fullCompression
                && context.session().normalizedConversationMode() == ConversationMode.MEMORY_ENABLED
                && memoryProperties.getLongTerm().isEnabled()
                && (context.turnIndex() <= 1
                || lastLongTermCollectionTurn <= 0
                || currentTurnToolCount > 0
                || currentTurnTokenEstimate >= longTermCollectionMinTokens
                || context.turnIndex() - lastLongTermCollectionTurn >= longTermCollectionTurns);
        return new MemoryProcessingPlan(fullCompression, longTermCollection, currentTurnTokenEstimate, currentTurnToolCount);
    }

    private int readMetadataInt(SessionWhiteboard whiteboard, String key) {
        if (whiteboard == null || whiteboard.metadata() == null) {
            return 0;
        }
        Object value = whiteboard.metadata().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int estimateTurnTokenCount(ConversationTurn turn) {
        if (turn == null || turn.content() == null || turn.content().isBlank()) {
            return 0;
        }
        double tokens = 0D;
        String text = turn.content();
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isWhitespace(current)) {
                continue;
            }
            tokens += current <= 127 ? 0.25D : 1D;
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    private int countCurrentTurnTools(List<ToolMemoryEntry> toolMemories, int turnIndex) {
        if (toolMemories == null || toolMemories.isEmpty()) {
            return 0;
        }
        return (int) toolMemories.stream()
                .filter(entry -> entry != null && entry.turnIndex() != null && entry.turnIndex() >= turnIndex)
                .count();
    }
}
