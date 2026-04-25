package org.example.kbsystemproject.ailearning.application.session.compression;

import org.example.kbsystemproject.ailearning.domain.session.CompressionSignals;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionStatus;
import org.example.kbsystemproject.ailearning.domain.session.SessionTopicBlock;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CompressionSignalExtractor {

    private final MemoryProperties memoryProperties;

    public CompressionSignalExtractor(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties;
    }

    public CompressionSignals extract(LearningSessionRecord session,
                                      ConversationTurn userTurn,
                                      List<ConversationTurn> recentTurns,
                                      SessionTopicBlock activeBlock) {
        String currentTopic = "UNSPECIFIED";
        if (userTurn != null && userTurn.metadata() != null) {
            Object currentTopicValue = userTurn.metadata().get("currentTopic");
            if (currentTopicValue instanceof String value && !value.isBlank()) {
                currentTopic = value.trim();
            }
        }
        if ("UNSPECIFIED".equals(currentTopic) && session.currentTopic() != null && !session.currentTopic().isBlank()) {
            currentTopic = session.currentTopic().trim();
        }
        boolean topicSwitched = activeBlock != null
                && currentTopic != null
                && !currentTopic.equalsIgnoreCase(activeBlock.topic());
        boolean lowInfoTurn = userTurn == null || userTurn.content() == null || userTurn.content().isBlank();
        int unresolvedQuestionCount = 0;
        boolean contextBudgetPressure = (session.turnCount() != null && session.turnCount() >= memoryProperties.getCompression().getShortTermTriggerTurns())
                || recentTurns.size() >= memoryProperties.getCompression().getRecentRawTurns() * 2;
        int activeBlockTurnCount = activeBlock == null ? 0 : activeBlock.turnCount();
        boolean sessionClosing = session.status() == LearningSessionStatus.CLOSED;
        return new CompressionSignals(
                currentTopic,
                topicSwitched,
                lowInfoTurn,
                unresolvedQuestionCount,
                contextBudgetPressure,
                activeBlockTurnCount,
                sessionClosing
        );
    }
}

