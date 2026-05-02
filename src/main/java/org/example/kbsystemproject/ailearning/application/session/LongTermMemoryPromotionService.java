package org.example.kbsystemproject.ailearning.application.session;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.session.MemoryCandidate;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.MemoryItemStore;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class LongTermMemoryPromotionService {

    private final MemoryItemStore memoryItemStore;
    private final MemoryProperties memoryProperties;
    private final SessionLockService sessionLockService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LongTermMemoryPromotionService(MemoryItemStore memoryItemStore,
                                          MemoryProperties memoryProperties,
                                          SessionLockService sessionLockService) {
        this.memoryItemStore = memoryItemStore;
        this.memoryProperties = memoryProperties;
        this.sessionLockService = sessionLockService;
    }

    @Scheduled(fixedDelayString = "${memory.async.fixed-delay-millis:3000}")
    public void schedule() {
        if (!memoryProperties.getLongTerm().isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        promotePendingCandidates()
                .doFinally(signalType -> running.set(false))
                .subscribe(
                        ignored -> {
                        },
                        error -> log.warn("Long-term memory promotion loop failed", error)
                );
    }

    public Mono<Void> promotePendingCandidates() {
        if (!memoryProperties.getLongTerm().isEnabled()) {
            return Mono.empty();
        }
        int batchSize = Math.max(1, memoryProperties.getLongTerm().getPromotionBatchSize());
        return memoryItemStore.findPendingCandidateGroups(batchSize)
                .concatMap(group -> sessionLockService.executeStrict(
                                "memory-promotion:" + group.userId(),
                                () -> promoteGroup(group)
                        )
                        .onErrorResume(error -> {
                            log.warn("Long-term memory promotion skipped. userId={}, fingerprint={}",
                                    group.userId(),
                                    group.candidateFingerprint(),
                                    error);
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> promoteGroup(MemoryItemStore.CandidateGroupKey group) {
        return memoryItemStore.findCandidateGroup(group.userId(), group.candidateFingerprint())
                .flatMap(candidates -> {
                    if (candidates.isEmpty()) {
                        return Mono.empty();
                    }
                    ObservedCandidate observed = ObservedCandidate.from(candidates);
                    PromotionDecision decision = decide(observed);
                    if (!decision.promotable()) {
                        if (decision.resolved()) {
                            return memoryItemStore.markCandidateGroupResolved(
                                    group.userId(),
                                    group.candidateFingerprint(),
                                    new MemoryItemStore.CandidateMergeResult("rejected", decision.reason(), null, null)
                            );
                        }
                        return memoryItemStore.markCandidateGroupObserved(group.userId(), group.candidateFingerprint(), decision.reason());
                    }
                    return memoryItemStore.mergeCandidateWithResult(group.userId(), observed.toCandidate())
                            .flatMap(result -> {
                                if ("conflicted".equals(result.status()) || "rejected".equals(result.status())) {
                                    return memoryItemStore.markCandidateGroupObserved(group.userId(), group.candidateFingerprint(), result.resolution());
                                }
                                return memoryItemStore.markCandidateGroupResolved(group.userId(), group.candidateFingerprint(), result);
                            });
                });
    }

    private PromotionDecision decide(ObservedCandidate observed) {
        if (!supportsLongTermType(observed)) {
            return PromotionDecision.rejected("unsupported_type");
        }
        double confidenceFloor = confidenceThreshold(observed.sourceType());
        if (observed.confidence() < confidenceFloor) {
            return PromotionDecision.observe("low_confidence");
        }
        int minTurns = minDistinctTurns(observed.sourceType());
        int minSessions = minDistinctSessions(observed.sourceType());
        if (observed.distinctTurnCount() < minTurns && observed.distinctSessionCount() < minSessions) {
            return PromotionDecision.observe("await_more_observation");
        }
        return PromotionDecision.promote();
    }

    private boolean supportsLongTermType(ObservedCandidate observed) {
        String memoryType = normalizeValue(observed.type());
        String sourceType = normalizeValue(observed.sourceType());
        return switch (memoryType) {
            case "goal", "preference", "constraint", "decision" -> true;
            case "plan" -> sourceType.contains("user_explicit") || sourceType.contains("behavioral_pattern");
            case "fact" -> (sourceType.contains("user_explicit")
                    || sourceType.contains("tool/system_structured")
                    || sourceType.contains("tool_structured"))
                    && observed.memoryKey() != null
                    && !observed.memoryKey().isBlank();
            default -> false;
        };
    }

    private double confidenceThreshold(String sourceType) {
        String normalized = normalizeValue(sourceType);
        if (normalized.contains("user_explicit")) {
            return memoryProperties.getLongTerm().getExplicitMinConfidence();
        }
        if (normalized.contains("tool/system_structured") || normalized.contains("tool_structured")) {
            return memoryProperties.getLongTerm().getStructuredMinConfidence();
        }
        if (normalized.contains("behavioral_pattern")) {
            return memoryProperties.getLongTerm().getBehavioralMinConfidence();
        }
        return memoryProperties.getLongTerm().getInferredMinConfidence();
    }

    private int minDistinctTurns(String sourceType) {
        String normalized = normalizeValue(sourceType);
        if (normalized.contains("behavioral_pattern") || normalized.contains("compression_inferred")) {
            return Math.max(memoryProperties.getLongTerm().getPromotionMinDistinctTurns() + 1,
                    memoryProperties.getLongTerm().getInferredMinEvidenceCount() + 1);
        }
        return Math.max(1, memoryProperties.getLongTerm().getPromotionMinDistinctTurns());
    }

    private int minDistinctSessions(String sourceType) {
        String normalized = normalizeValue(sourceType);
        if (normalized.contains("tool/system_structured") || normalized.contains("tool_structured")) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, memoryProperties.getLongTerm().getPromotionMinDistinctSessions());
    }

    private static int sourcePriorityOf(String sourceType) {
        String normalized = normalizeValue(sourceType);
        if (normalized.contains("user_explicit")) {
            return 0;
        }
        if (normalized.contains("tool/system_structured") || normalized.contains("tool_structured")) {
            return 1;
        }
        if (normalized.contains("behavioral_pattern")) {
            return 2;
        }
        if (normalized.contains("compression_inferred")) {
            return 3;
        }
        return 4;
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record PromotionDecision(boolean promotable, boolean resolved, String reason) {
        private static PromotionDecision promote() {
            return new PromotionDecision(true, false, "ready_for_promotion");
        }

        private static PromotionDecision observe(String reason) {
            return new PromotionDecision(false, false, reason);
        }

        private static PromotionDecision rejected(String reason) {
            return new PromotionDecision(false, true, reason);
        }
    }

    private record ObservedCandidate(String userId,
                                     String candidateFingerprint,
                                     String scope,
                                     String type,
                                     String memoryKey,
                                     String memoryValue,
                                     Double confidence,
                                     String sourceType,
                                     List<String> evidence,
                                     Map<String, Object> metadata,
                                     int observationCount,
                                     int distinctTurnCount,
                                     int distinctSessionCount,
                                     OffsetDateTime firstSeenAt,
                                     OffsetDateTime lastSeenAt) {

        private static ObservedCandidate from(List<MemoryCandidate> candidates) {
            MemoryCandidate representative = candidates.stream()
                    .sorted(Comparator
                            .comparingInt((MemoryCandidate candidate) -> sourcePriorityOf(candidate.sourceType()))
                            .thenComparing((MemoryCandidate candidate) -> candidate.confidence() == null ? 0D : candidate.confidence(), Comparator.reverseOrder())
                            .thenComparing(MemoryCandidate::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .findFirst()
                    .orElseThrow();
            List<String> evidence = candidates.stream()
                    .flatMap(candidate -> candidate.evidence() == null ? java.util.stream.Stream.<String>empty() : candidate.evidence().stream())
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(10)
                    .toList();
            Map<String, Object> metadata = new LinkedHashMap<>(representative.metadata() == null ? Map.of() : representative.metadata());
            int distinctTurnCount = (int) candidates.stream()
                    .map(candidate -> candidate.conversationId() + ":" + (candidate.turnIndex() == null ? -1 : candidate.turnIndex()))
                    .distinct()
                    .count();
            int distinctSessionCount = (int) candidates.stream()
                    .map(MemoryCandidate::conversationId)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct()
                    .count();
            metadata.put("observationCount", candidates.size());
            metadata.put("distinctTurnCount", distinctTurnCount);
            metadata.put("distinctSessionCount", distinctSessionCount);
            metadata.put("firstSeenAt", candidates.stream().map(MemoryCandidate::createdAt).filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null));
            metadata.put("lastSeenAt", candidates.stream().map(MemoryCandidate::createdAt).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
            return new ObservedCandidate(
                    representative.userId(),
                    representative.candidateFingerprint(),
                    representative.scope(),
                    representative.type(),
                    representative.memoryKey(),
                    representative.memoryValue(),
                    candidates.stream()
                            .map(MemoryCandidate::confidence)
                            .filter(java.util.Objects::nonNull)
                            .max(Comparator.naturalOrder())
                            .orElse(0D),
                    representative.sourceType(),
                    evidence,
                    metadata,
                    candidates.size(),
                    distinctTurnCount,
                    distinctSessionCount,
                    candidates.stream().map(MemoryCandidate::createdAt).filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                    candidates.stream().map(MemoryCandidate::createdAt).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null)
            );
        }

        private MemoryCandidate toCandidate() {
            return new MemoryCandidate(
                    null,
                    userId,
                    null,
                    null,
                    scope,
                    type,
                    memoryKey,
                    memoryValue,
                    confidence,
                    sourceType,
                    candidateFingerprint,
                    "promoting",
                    evidence,
                    metadata,
                    firstSeenAt
            );
        }
    }
}
