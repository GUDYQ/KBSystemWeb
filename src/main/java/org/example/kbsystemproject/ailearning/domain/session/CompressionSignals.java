package org.example.kbsystemproject.ailearning.domain.session;

public record CompressionSignals(
        String currentTopic,
        boolean topicSwitched,
        boolean lowInfoTurn,
        int unresolvedQuestionCount,
        boolean contextBudgetPressure,
        int activeBlockTurnCount,
        boolean sessionClosing
) {
}
