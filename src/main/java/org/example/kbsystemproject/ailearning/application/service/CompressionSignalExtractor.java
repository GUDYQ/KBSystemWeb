package org.example.kbsystemproject.ailearning.application.service;

import org.example.kbsystemproject.ailearning.domain.session.CompressionSignals;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionStatus;
import org.example.kbsystemproject.ailearning.domain.session.SessionTopicBlock;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

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
        String currentTopic = resolveTopic(session, userTurn);
        boolean topicSwitched = activeBlock != null
                && currentTopic != null
                && !currentTopic.equalsIgnoreCase(activeBlock.topic());
        boolean lowInfoTurn = isLowInfoTurn(userTurn == null ? null : userTurn.content());
        int unresolvedQuestionCount = calculateUnresolvedCount(recentTurns);
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

    private String resolveTopic(LearningSessionRecord session, ConversationTurn userTurn) {
        if (userTurn != null && userTurn.metadata() != null) {
            Object currentTopic = userTurn.metadata().get("currentTopic");
            if (currentTopic instanceof String value && !value.isBlank()) {
                return value.trim();
            }
        }
        if (session.currentTopic() != null && !session.currentTopic().isBlank()) {
            return session.currentTopic().trim();
        }
        if (userTurn == null || userTurn.content() == null || userTurn.content().isBlank()) {
            return "general";
        }
        String prompt = userTurn.content().toLowerCase(Locale.ROOT);
        for (String keyword : List.of("redis", "mysql", "tcp", "http", "spring", "java", "python", "事务", "索引", "递归", "二叉树")) {
            if (prompt.contains(keyword.toLowerCase(Locale.ROOT))) {
                return keyword;
            }
        }
        return "general";
    }

    private boolean isLowInfoTurn(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        return List.of("继续", "明白了", "举个例子", "详细一点", "这个呢", "再说一下")
                .stream()
                .anyMatch(content::contains);
    }

    private int calculateUnresolvedCount(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return 0;
        }
        return (int) turns.stream()
                .filter(turn -> turn.role().name().equals("USER"))
                .map(ConversationTurn::content)
                .filter(content -> content != null && List.of("没懂", "还是不会", "为什么这里", "总是搞混", "不太明白")
                        .stream()
                        .anyMatch(content::contains))
                .count();
    }
}
