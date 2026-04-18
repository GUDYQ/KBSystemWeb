package org.example.kbsystemproject.ailearning.domain.session;

public record SessionTurnPair(
        ConversationTurn userTurn,
        ConversationTurn assistantTurn
) {
}
