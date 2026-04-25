package org.example.kbsystemproject.ailearning.application.session.compression;

import org.example.kbsystemproject.ailearning.application.session.SessionStorageService;
import org.example.kbsystemproject.ailearning.domain.session.CompressionAction;
import org.example.kbsystemproject.ailearning.domain.session.CompressionDecision;
import org.example.kbsystemproject.ailearning.domain.session.CompressionSignals;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.SessionTopicBlock;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionTopicBlockStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class SessionCompressionService {

    private final LearningSessionTopicBlockStore topicBlockStore;
    private final CompressionSignalExtractor signalExtractor;
    private final CompressionDecider compressionDecider;
    private final SessionStorageService sessionStorageService;

    public SessionCompressionService(LearningSessionTopicBlockStore topicBlockStore,
                                     CompressionSignalExtractor signalExtractor,
                                     CompressionDecider compressionDecider,
                                     SessionStorageService sessionStorageService) {
        this.topicBlockStore = topicBlockStore;
        this.signalExtractor = signalExtractor;
        this.compressionDecider = compressionDecider;
        this.sessionStorageService = sessionStorageService;
    }

    public Mono<Void> handleTurn(LearningSessionRecord session,
                                 int turnIndex,
                                 ConversationTurn userTurn,
                                 ConversationTurn assistantTurn,
                                 List<ConversationTurn> recentTurns) {
        return topicBlockStore.findActiveByConversationId(session.conversationId())
                .defaultIfEmpty(emptyBlock(session.conversationId()))
                .flatMap(activeBlock -> {
                    SessionTopicBlock normalizedBlock = activeBlock.id() == null ? null : activeBlock;
                    CompressionSignals signals = signalExtractor.extract(session, userTurn, recentTurns, normalizedBlock);
                    CompressionDecision decision = compressionDecider.decide(signals);
                    return applyDecision(session, turnIndex, userTurn, assistantTurn, normalizedBlock, signals, decision, recentTurns);
                });
    }

    private Mono<Void> applyDecision(LearningSessionRecord session,
                                     int turnIndex,
                                     ConversationTurn userTurn,
                                     ConversationTurn assistantTurn,
                                     SessionTopicBlock activeBlock,
                                     CompressionSignals signals,
                                     CompressionDecision decision,
                                     List<ConversationTurn> recentTurns) {
        String currentSummary = summarizeBlock(signals.currentTopic(), userTurn, assistantTurn, recentTurns, signals.unresolvedQuestionCount());
        if (activeBlock == null) {
            return topicBlockStore.createActiveBlock(
                            session.conversationId(),
                            signals.currentTopic(),
                            turnIndex,
                            signals.unresolvedQuestionCount(),
                            signals.lowInfoTurn() ? 1.0D : 0.0D,
                            currentSummary
                    )
                    .then();
        }

        if (decision.finalizeCurrentBlock()) {
            Mono<Void> finalize = topicBlockStore.finalizeBlock(activeBlock, summarizeBlock(activeBlock.topic(), userTurn, assistantTurn, recentTurns, signals.unresolvedQuestionCount()))
                    .flatMap(finalizedBlock -> decision.archiveLongTerm()
                            ? archiveLongTermSummary(session, finalizedBlock)
                            : Mono.empty())
                    .then();
            Mono<Void> createNext = topicBlockStore.createActiveBlock(
                            session.conversationId(),
                            signals.currentTopic(),
                            turnIndex,
                            signals.unresolvedQuestionCount(),
                            signals.lowInfoTurn() ? 1.0D : 0.0D,
                            currentSummary
                    )
                    .then();
            return finalize.then(createNext);
        }

        int stableScore = activeBlock.stableScore() + (signals.topicSwitched() ? 0 : 1);
        double lowInfoRatio = nextLowInfoRatio(activeBlock, signals.lowInfoTurn());
        return topicBlockStore.updateActiveBlock(
                        activeBlock,
                        turnIndex,
                        stableScore,
                        Math.max(activeBlock.unresolvedCount(), signals.unresolvedQuestionCount()),
                        lowInfoRatio,
                        decision.action() == CompressionAction.LIGHT_SHORT_TERM_COMPRESS
                                ? currentSummary
                                : summarizeBlock(activeBlock.topic(), userTurn, assistantTurn, recentTurns, signals.unresolvedQuestionCount())
                )
                .then();
    }

    private Mono<Void> archiveLongTermSummary(LearningSessionRecord session, SessionTopicBlock finalizedBlock) {
        return sessionStorageService.archiveSummary(
                session.conversationId(),
                buildLongTermSummary(finalizedBlock),
                java.util.Map.of(
                        "summaryType", "TOPIC_BLOCK",
                        "topic", finalizedBlock.topic(),
                        "startTurn", finalizedBlock.startTurn(),
                        "endTurn", finalizedBlock.lastTurn()
                )
        );
    }

    private String buildLongTermSummary(SessionTopicBlock block) {
        StringBuilder builder = new StringBuilder();
        builder.append("长期学习摘要:\n");
        builder.append("- 主题: ").append(block.topic()).append('\n');
        builder.append("- 覆盖轮次: ").append(block.startTurn()).append('-').append(block.lastTurn()).append('\n');
        builder.append("- 主题稳定度: ").append(block.stableScore()).append('\n');
        if (block.unresolvedCount() > 0) {
            builder.append("- 未解决问题数: ").append(block.unresolvedCount()).append('\n');
        }
        if (block.summary() != null && !block.summary().isBlank()) {
            builder.append(block.summary());
        }
        return builder.toString();
    }

    private String summarizeBlock(String topic,
                                  ConversationTurn userTurn,
                                  ConversationTurn assistantTurn,
                                  List<ConversationTurn> recentTurns,
                                  int unresolvedCount) {
        StringBuilder builder = new StringBuilder();
        builder.append("短期主题摘要:\n");
        builder.append("- 当前主题: ").append(topic).append('\n');
        if (userTurn != null && userTurn.content() != null && !userTurn.content().isBlank()) {
            builder.append("- 最近用户问题: ").append(limit(userTurn.content(), 80)).append('\n');
        }
        if (assistantTurn != null && assistantTurn.content() != null && !assistantTurn.content().isBlank()) {
            builder.append("- 最近回答结论: ").append(limit(assistantTurn.content(), 100)).append('\n');
        }
        if (recentTurns != null && !recentTurns.isEmpty()) {
            builder.append("- 最近对话轮数: ").append(recentTurns.size() / 2).append('\n');
        }
        if (unresolvedCount > 0) {
            builder.append("- 仍有未解决问题: ").append(unresolvedCount).append('\n');
        }
        return builder.toString();
    }

    private double nextLowInfoRatio(SessionTopicBlock block, boolean lowInfoTurn) {
        double lowInfoCount = block.lowInfoRatio() * block.turnCount();
        if (lowInfoTurn) {
            lowInfoCount++;
        }
        return lowInfoCount / (block.turnCount() + 1);
    }

    private String limit(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private SessionTopicBlock emptyBlock(String conversationId) {
        return new SessionTopicBlock(null, conversationId, null, "EMPTY", 0, 0, 0, 0, 0, 0, null, null);
    }
}

