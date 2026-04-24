package org.example.kbsystemproject.ailearning.application.service;

import lombok.extern.slf4j.Slf4j;
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
import java.util.Locale;
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

    Mono<Void> runOnce() {
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
        String prompt = userTurn.content();
        String topic = extractTopic(userTurn, session, prompt);
        String learningGoal = extractLearningGoal(prompt);
        String preferredStyle = extractPreferredStyle(prompt);
        String preferredLanguage = extractPreferredLanguage(prompt);
        List<String> weakPoints = extractWeakPoints(prompt, topic);
        String subject = profileContextService.resolveSubject(session.subject());

        Mono<Void> sessionTopicUpdate = topic == null || topic.isBlank()
                ? Mono.empty()
                : learningSessionPersonalizationStore.upsertTopic(task.conversationId(), session.userId(), topic);

        return sessionTopicUpdate.then(
                learningProfileStore.findByUserIdAndSubject(session.userId(), subject)
                        .defaultIfEmpty(new LearningProfileRecord(null, session.userId(), subject, null, null, null, List.of(), null))
                        .flatMap(existing -> learningProfileStore.upsert(
                                session.userId(),
                                subject,
                                preferValue(learningGoal, existing.learningGoal()),
                                preferValue(preferredStyle, existing.preferredStyle()),
                                preferValue(preferredLanguage, existing.preferredLanguage()),
                                mergeWeakPoints(existing.weakPoints(), weakPoints)
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

    private String extractLearningGoal(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        for (String keyword : List.of("我想学", "我要准备", "我现在在学", "我想准备")) {
            int index = prompt.indexOf(keyword);
            if (index >= 0) {
                return normalizeText(prompt.substring(index + keyword.length()));
            }
        }
        return null;
    }

    private String extractPreferredStyle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        if (containsAny(prompt, "详细一点", "一步一步", "分步骤")) {
            return "STEP_BY_STEP";
        }
        if (containsAny(prompt, "给我代码", "先给代码", "代码示例")) {
            return "CODE_FIRST";
        }
        if (containsAny(prompt, "先讲原理", "先讲概念")) {
            return "CONCEPT_FIRST";
        }
        if (containsAny(prompt, "简单说", "简洁一点", "短一点")) {
            return "SHORT_ANSWER";
        }
        return null;
    }

    private String extractPreferredLanguage(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (normalized.contains("java")) {
            return "java";
        }
        if (normalized.contains("python")) {
            return "python";
        }
        if (normalized.contains("c++")) {
            return "c++";
        }
        return null;
    }

    private List<String> extractWeakPoints(String prompt, String topic) {
        if (prompt == null || prompt.isBlank() || topic == null || topic.isBlank()) {
            return List.of();
        }
        if (!containsAny(prompt, "没懂", "还是不会", "为什么这里", "总是搞混", "不太明白")) {
            return List.of();
        }
        return List.of(topic);
    }

    private String extractTopic(ConversationTurn userTurn, LearningSessionRecord session, String prompt) {
        Object currentTopic = userTurn.metadata().get("currentTopic");
        if (currentTopic instanceof String value && !value.isBlank()) {
            return value;
        }
        if (session.currentTopic() != null && !session.currentTopic().isBlank()) {
            return session.currentTopic();
        }
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        for (String keyword : List.of("redis", "mysql", "tcp", "spring", "java", "python", "二叉树", "递归", "索引", "事务")) {
            if (prompt.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                return keyword;
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
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
