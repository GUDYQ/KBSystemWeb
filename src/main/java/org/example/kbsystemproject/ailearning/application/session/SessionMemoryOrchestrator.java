package org.example.kbsystemproject.ailearning.application.session;

import org.example.kbsystemproject.ailearning.application.session.compression.SessionCompressionService;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.SessionWhiteboard;
import org.example.kbsystemproject.ailearning.infrastructure.memory.ConversationArchiveStore;
import org.example.kbsystemproject.ailearning.infrastructure.ai.prompt.MemoryPromptService;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.MemoryItemStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.SessionWhiteboardStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;

@Service
public class SessionMemoryOrchestrator {

    private final SessionStorageService sessionStorageService;
    private final SessionWhiteboardStore sessionWhiteboardStore;
    private final MemoryProperties memoryProperties;
    private final MemoryTriggerPolicy triggerPolicy;
    private final MemoryCandidateService memoryCandidateService;
    private final MemoryArchiveService memoryArchiveService;

    @Autowired
    public SessionMemoryOrchestrator(SessionStorageService sessionStorageService,
                                     SessionWhiteboardStore sessionWhiteboardStore,
                                     MemoryItemStore memoryItemStore,
                                     MemoryPromptService memoryPromptService,
                                     ConversationArchiveStore conversationArchiveStore,
                                     EmbeddingModel embeddingModel,
                                     @Qualifier("aiBlockingScheduler") Scheduler aiBlockingScheduler,
                                     SessionCompressionService sessionCompressionService,
                                     MemoryProperties memoryProperties) {
        this(
                sessionStorageService,
                sessionWhiteboardStore,
                memoryProperties,
                new MemoryTriggerPolicy(memoryProperties),
                new MemoryCandidateService(sessionWhiteboardStore, memoryItemStore, memoryPromptService, memoryProperties),
                new MemoryArchiveService(
                        sessionStorageService,
                        conversationArchiveStore,
                        embeddingModel,
                        aiBlockingScheduler,
                        sessionCompressionService,
                        memoryProperties
                )
        );
    }

    SessionMemoryOrchestrator(SessionStorageService sessionStorageService,
                              SessionWhiteboardStore sessionWhiteboardStore,
                              MemoryProperties memoryProperties,
                              MemoryTriggerPolicy triggerPolicy,
                              MemoryCandidateService memoryCandidateService,
                              MemoryArchiveService memoryArchiveService) {
        this.sessionStorageService = sessionStorageService;
        this.sessionWhiteboardStore = sessionWhiteboardStore;
        this.memoryProperties = memoryProperties;
        this.triggerPolicy = triggerPolicy;
        this.memoryCandidateService = memoryCandidateService;
        this.memoryArchiveService = memoryArchiveService;
    }

    public Mono<Void> processTurn(String conversationId, String requestId, int turnIndex) {
        return loadProcessingContext(conversationId, requestId, turnIndex)
                .flatMap(context -> {
                    MemoryProcessingPlan plan = triggerPolicy.decide(context);
                    return memoryCandidateService.collect(context, plan)
                            .flatMap(memoryCandidateService::persistCandidates)
                            .then(memoryArchiveService.runCompatibilityFlows(context, plan));
                });
    }

    private Mono<TurnMemoryContext> loadProcessingContext(String conversationId, String requestId, int turnIndex) {
        return Mono.zip(
                        sessionStorageService.loadTurnsByTurnRange(conversationId, turnIndex, turnIndex),
                        sessionStorageService.getSession(conversationId),
                        sessionStorageService.loadRecentTurns(
                                conversationId,
                                Math.max(2, memoryProperties.getCompression().getRecentRawTurns() * 2)
                        ),
                        sessionWhiteboardStore.findByConversationId(conversationId)
                                .defaultIfEmpty(SessionWhiteboard.empty(conversationId)),
                        sessionStorageService.loadRecentToolMemories(conversationId, 4)
                )
                .flatMap(tuple -> {
                    List<ConversationTurn> currentTurnPair = tuple.getT1();
                    if (currentTurnPair.size() < 2) {
                        return Mono.error(new IllegalStateException("Missing persisted turn pair for conversation " + conversationId
                                + " at turn " + turnIndex));
                    }
                    return Mono.just(new TurnMemoryContext(
                            tuple.getT2(),
                            requestId,
                            turnIndex,
                            currentTurnPair.get(0),
                            currentTurnPair.get(1),
                            tuple.getT3(),
                            tuple.getT4(),
                            tuple.getT5()
                    ));
                });
    }
}
