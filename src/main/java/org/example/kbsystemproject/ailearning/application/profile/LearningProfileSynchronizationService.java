package org.example.kbsystemproject.ailearning.application.profile;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.application.session.SessionStorageService;
import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileRecord;
import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileTaskRecord;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.profile.LearningProfileStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.profile.LearningProfileTaskStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.profile.LearningSessionPersonalizationStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class LearningProfileSynchronizationService {

    private final LearningProfileTaskStore learningProfileTaskStore;
    private final SessionStorageService sessionStorageService;
    private final LearningProfileStore learningProfileStore;
    private final LearningSessionPersonalizationStore learningSessionPersonalizationStore;
    private final ProfileContextService profileContextService;
    private final MemoryProperties memoryProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String ownerInstance;

    public LearningProfileSynchronizationService(LearningProfileTaskStore learningProfileTaskStore,
                                                 SessionStorageService sessionStorageService,
                                                 LearningProfileStore learningProfileStore,
                                                 LearningSessionPersonalizationStore learningSessionPersonalizationStore,
                                                 ProfileContextService profileContextService,
                                                 MemoryProperties memoryProperties) {
        this.learningProfileTaskStore = learningProfileTaskStore;
        this.sessionStorageService = sessionStorageService;
        this.learningProfileStore = learningProfileStore;
        this.learningSessionPersonalizationStore = learningSessionPersonalizationStore;
        this.profileContextService = profileContextService;
        this.memoryProperties = memoryProperties;
        this.ownerInstance = resolveOwnerInstance();
    }

    @Scheduled(fixedDelayString = "${memory.async.fixed-delay-millis:3000}")
    public void schedule() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        runOnce()
                .doFinally(signalType -> running.set(false))
                .subscribe(
                        ignored -> {
                        },
                        error -> log.warn("Learning profile sync loop failed", error)
                );
    }

    public Mono<Void> runOnce() {
        OffsetDateTime leaseExpiresAt = OffsetDateTime.now().plusSeconds(Math.max(30, memoryProperties.getAsync().getLeaseSeconds()));
        return learningProfileTaskStore.claimBatch(
                        Math.max(1, memoryProperties.getAsync().getBatchSize()),
                        ownerInstance,
                        leaseExpiresAt
                )
                .concatMap(this::processTask)
                .then();
    }

    private Mono<Void> processTask(LearningProfileTaskRecord task) {
        return Mono.zip(
                        sessionStorageService.loadTurnsByTurnRange(task.conversationId(), task.turnIndex(), task.turnIndex()),
                        sessionStorageService.getSession(task.conversationId())
                )
                .flatMap(tuple -> updatePersonalization(task, tuple.getT1(), tuple.getT2()))
                .then(learningProfileTaskStore.markCompleted(task.id()))
                .onErrorResume(error -> {
                    log.warn("Learning profile task failed. taskId={}, conversationId={}, requestId={}",
                            task.id(), task.conversationId(), task.requestId(), error);
                    return learningProfileTaskStore.markFailed(
                            task.id(),
                            error.getMessage(),
                            OffsetDateTime.now().plusSeconds(Math.max(10, memoryProperties.getAsync().getRetryDelaySeconds()))
                    );
                });
    }

    private Mono<Void> updatePersonalization(LearningProfileTaskRecord task,
                                             List<ConversationTurn> turns,
                                             LearningSessionRecord session) {
        if (turns == null || turns.isEmpty()) {
            return Mono.empty();
        }
        ConversationTurn userTurn = turns.getFirst();
        String topic = extractTopic(userTurn, session);
        String learningGoal = normalizeText(session.learningGoal());
        String subject = profileContextService.resolveSubject(session.subject());

        Mono<Void> sessionTopicUpdate = topic == null || topic.isBlank()
                ? Mono.empty()
                : learningSessionPersonalizationStore.upsertTopic(task.conversationId(), session.userId(), topic);

        if (subject == null) {
            return sessionTopicUpdate;
        }

        return sessionTopicUpdate.then(
                learningProfileStore.findByUserIdAndSubject(session.userId(), subject)
                        .defaultIfEmpty(new LearningProfileRecord(null, session.userId(), subject, null, null, null, List.of(), null))
                        .flatMap(existing -> learningProfileStore.upsert(
                                session.userId(),
                                subject,
                                preferValue(learningGoal, existing.learningGoal()),
                                existing.preferredStyle(),
                                existing.preferredLanguage(),
                                existing.weakPoints()
                        ))
        );
    }

    private List<String> mergeWeakPoints(List<String> existing, List<String> incoming) {
        ArrayList<String> merged = new ArrayList<>();
        if (existing != null) {
            merged.addAll(existing);
        }
        if (incoming != null) {
            merged.addAll(incoming);
        }
        return merged.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(10)
                .toList();
    }

    private String preferValue(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private String extractTopic(ConversationTurn userTurn, LearningSessionRecord session) {
        if (userTurn != null && userTurn.metadata() != null) {
            Object currentTopic = userTurn.metadata().get("currentTopic");
            if (currentTopic instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        if (session.currentTopic() != null && !session.currentTopic().isBlank()) {
            return session.currentTopic();
        }
        return null;
    }

    private String normalizeText(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    private String resolveOwnerInstance() {
        try {
            return InetAddress.getLocalHost().getHostName() + ":" + UUID.randomUUID();
        } catch (Exception error) {
            return "learning-profile-sync:" + UUID.randomUUID();
        }
    }
}

