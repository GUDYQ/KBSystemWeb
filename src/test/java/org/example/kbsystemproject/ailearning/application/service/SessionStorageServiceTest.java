package org.example.kbsystemproject.ailearning.application.service;

import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionStatus;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionMemoryTaskStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionMessageStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionRequestStore;
import org.example.kbsystemproject.ailearning.infrastructure.memory.ConversationArchiveStore;
import org.example.kbsystemproject.ailearning.infrastructure.memory.RedisShortTermMemoryStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.LearningSessionStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SessionStorageServiceTest {

    @Mock
    private LearningSessionStore learningSessionStore;

    @Mock
    private RedisShortTermMemoryStore shortTermMemoryStore;

    @Mock
    private ConversationArchiveStore conversationArchiveStore;

    @Mock
    private LearningSessionRequestStore learningSessionRequestStore;

    @Mock
    private LearningSessionMessageStore learningSessionMessageStore;

    @Mock
    private LearningSessionMemoryTaskStore learningSessionMemoryTaskStore;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private ChatClient chatClient;

    @Mock
    private TransactionalOperator transactionalOperator;

    @Mock
    private SessionLockService sessionLockService;

    private SessionStorageService sessionStorageService;

    @BeforeEach
    void setUp() {
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.getShortTerm().setMaxTurns(3);
        sessionStorageService = new SessionStorageService(
                learningSessionStore,
                learningSessionRequestStore,
                learningSessionMessageStore,
                learningSessionMemoryTaskStore,
                shortTermMemoryStore,
                conversationArchiveStore,
                embeddingModel,
                chatClient,
                memoryProperties,
                Schedulers.immediate(),
                transactionalOperator,
                sessionLockService
        );
        lenient().when(learningSessionMessageStore.loadRecentTurns(anyString(), anyInt())).thenReturn(Mono.just(List.of()));
    }

    @Test
    void getShortTermMemoryReturnsCacheWhenCacheMatchesDatabaseTurnCount() {
        LearningSessionRecord session = sessionRecord("conv-1", 2, 0);
        List<ConversationTurn> cachedTurns = List.of(
                turn("first", 1, 0),
                turn("second", 2, 1)
        );

        when(learningSessionStore.getByConversationId("conv-1")).thenReturn(Mono.just(session));
        when(shortTermMemoryStore.getRecentTurns("conv-1")).thenReturn(Mono.just(cachedTurns));
        when(shortTermMemoryStore.getTotalTurns("conv-1")).thenReturn(Mono.just(2L));

        StepVerifier.create(sessionStorageService.getShortTermMemory("conv-1"))
                .expectNext(cachedTurns)
                .verifyComplete();

        verify(shortTermMemoryStore, never()).rebuild(eq("conv-1"), anyList(), eq(2L));
    }

    @Test
    void getShortTermMemoryRebuildsCacheWhenRedisStateIsStale() {
        LearningSessionRecord session = sessionRecord("conv-2", 3, 0);
        List<ConversationTurn> staleTurns = List.of(turn("stale", 2, 1));
        List<ConversationTurn> archiveTurns = List.of(
                turn("user", 3, 0),
                turn("assistant", 3, 1)
        );

        when(learningSessionStore.getByConversationId("conv-2")).thenReturn(Mono.just(session));
        when(shortTermMemoryStore.getRecentTurns("conv-2")).thenReturn(Mono.just(staleTurns));
        when(shortTermMemoryStore.getTotalTurns("conv-2")).thenReturn(Mono.just(2L));
        when(learningSessionMessageStore.loadRecentTurns("conv-2", 6)).thenReturn(Mono.just(archiveTurns));
        when(shortTermMemoryStore.rebuild("conv-2", archiveTurns, 3L)).thenReturn(Mono.empty());

        StepVerifier.create(sessionStorageService.getShortTermMemory("conv-2"))
                .expectNext(archiveTurns)
                .verifyComplete();

        verify(shortTermMemoryStore).rebuild("conv-2", archiveTurns, 3L);
    }

    @Test
    void getTurnCountUsesLearningSessionAsAuthoritativeSource() {
        when(learningSessionStore.getByConversationId("conv-3")).thenReturn(Mono.just(sessionRecord("conv-3", 8, 4)));

        StepVerifier.create(sessionStorageService.getTurnCount("conv-3"))
                .expectNext(8L)
                .verifyComplete();
    }

    private LearningSessionRecord sessionRecord(String conversationId, int turnCount, int lastSummarizedTurn) {
        OffsetDateTime now = OffsetDateTime.now();
        return new LearningSessionRecord(
                1L,
                conversationId,
                "user-1",
                "math",
                LearningSessionType.QA,
                "goal",
                "topic",
                turnCount,
                lastSummarizedTurn,
                null,
                LearningSessionStatus.ACTIVE,
                now,
                now,
                now
        );
    }

    private ConversationTurn turn(String content, int turnIndex, int messageIndex) {
        return new ConversationTurn(
                org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole.USER,
                content,
                OffsetDateTime.now(),
                Map.of("turnIndex", turnIndex, "messageIndex", messageIndex)
        );
    }
}
