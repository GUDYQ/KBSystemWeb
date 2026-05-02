package org.example.kbsystemproject.ailearning.application.session;

import org.example.kbsystemproject.ailearning.application.session.compression.SessionCompressionService;
import org.example.kbsystemproject.ailearning.domain.session.ConversationMode;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.example.kbsystemproject.ailearning.infrastructure.memory.ConversationArchiveStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Map;

class MemoryArchiveService {

    private final SessionStorageService sessionStorageService;
    private final ConversationArchiveStore conversationArchiveStore;
    private final EmbeddingModel embeddingModel;
    private final Scheduler aiBlockingScheduler;
    private final SessionCompressionService sessionCompressionService;
    private final MemoryProperties memoryProperties;

    MemoryArchiveService(SessionStorageService sessionStorageService,
                         ConversationArchiveStore conversationArchiveStore,
                         EmbeddingModel embeddingModel,
                         Scheduler aiBlockingScheduler,
                         SessionCompressionService sessionCompressionService,
                         MemoryProperties memoryProperties) {
        this.sessionStorageService = sessionStorageService;
        this.conversationArchiveStore = conversationArchiveStore;
        this.embeddingModel = embeddingModel;
        this.aiBlockingScheduler = aiBlockingScheduler;
        this.sessionCompressionService = sessionCompressionService;
        this.memoryProperties = memoryProperties;
    }

    Mono<Void> runCompatibilityFlows(TurnMemoryContext context, MemoryProcessingPlan plan) {
        return archiveTurnPairIfNeeded(context, plan)
                .then(compressTopicBlockIfNeeded(context, plan))
                .then(archiveSummaryIfNeeded(context));
    }

    private Mono<Void> archiveTurnPairIfNeeded(TurnMemoryContext context, MemoryProcessingPlan plan) {
        if (!plan.longTermCollection()
                || context.session().normalizedConversationMode() != ConversationMode.MEMORY_ENABLED
                || !memoryProperties.getLongTerm().isEnabled()) {
            return Mono.empty();
        }
        Map<String, Object> metadata = sessionStorageService.enrichMetadata(
                Map.of("requestId", context.requestId()),
                context.turnIndex()
        );
        return Mono.zip(embed(context.userTurn().content()), embed(context.assistantTurn().content()))
                .flatMap(embeddingTuple -> conversationArchiveStore.archiveTurnPair(
                        context.session().conversationId(),
                        new SessionTurnPair(context.userTurn(), context.assistantTurn()),
                        embeddingTuple.getT1(),
                        embeddingTuple.getT2(),
                        metadata,
                        context.turnIndex()
                ));
    }

    private Mono<Void> compressTopicBlockIfNeeded(TurnMemoryContext context, MemoryProcessingPlan plan) {
        if (!memoryProperties.getCompression().isEnabled() || !plan.fullCompression()) {
            return Mono.empty();
        }
        return sessionCompressionService.handleTurn(
                context.session(),
                context.turnIndex(),
                context.userTurn(),
                context.assistantTurn(),
                context.recentTurns()
        );
    }

    private Mono<Void> archiveSummaryIfNeeded(TurnMemoryContext context) {
        if (context.session().normalizedConversationMode() != ConversationMode.MEMORY_ENABLED) {
            return Mono.empty();
        }
        return sessionStorageService.maybeArchiveSummary(context.session().conversationId(), context.turnIndex());
    }

    private Mono<float[]> embed(String text) {
        return Mono.fromCallable(() -> embeddingModel.embed(text))
                .subscribeOn(aiBlockingScheduler);
    }
}
