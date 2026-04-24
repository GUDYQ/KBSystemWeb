package org.example.kbsystemproject.ailearning.application.service;

import org.example.kbsystemproject.ailearning.domain.session.CompressionAction;
import org.example.kbsystemproject.ailearning.domain.session.CompressionDecision;
import org.example.kbsystemproject.ailearning.domain.session.CompressionSignals;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.stereotype.Service;

@Service
public class CompressionDecider {

    private final MemoryProperties memoryProperties;

    public CompressionDecider(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties;
    }

    public CompressionDecision decide(CompressionSignals signals) {
        if (signals.topicSwitched()) {
            boolean archiveLongTerm = signals.activeBlockTurnCount() >= memoryProperties.getCompression().getLongTermArchiveMinTurns();
            return new CompressionDecision(
                    archiveLongTerm ? CompressionAction.ARCHIVE_LONG_TERM_SUMMARY : CompressionAction.FINALIZE_TOPIC_BLOCK,
                    true,
                    archiveLongTerm,
                    "topic-switched"
            );
        }
        if (signals.sessionClosing() && signals.activeBlockTurnCount() >= memoryProperties.getCompression().getTopicBlockMinTurns()) {
            return new CompressionDecision(
                    CompressionAction.ARCHIVE_LONG_TERM_SUMMARY,
                    true,
                    true,
                    "session-closing"
            );
        }
        if (signals.contextBudgetPressure()
                && signals.lowInfoTurn()
                && signals.unresolvedQuestionCount() < memoryProperties.getCompression().getUnresolvedThreshold()) {
            return new CompressionDecision(
                    CompressionAction.LIGHT_SHORT_TERM_COMPRESS,
                    false,
                    false,
                    "context-budget-pressure"
            );
        }
        return new CompressionDecision(CompressionAction.NO_COMPRESS, false, false, "keep-raw-context");
    }
}
