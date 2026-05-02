package org.example.kbsystemproject.ailearning.application.session;

import org.example.kbsystemproject.ailearning.domain.session.ConversationMode;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.MemoryCandidate;
import org.example.kbsystemproject.ailearning.domain.session.SessionWhiteboard;
import org.example.kbsystemproject.ailearning.domain.session.ToolMemoryEntry;
import org.example.kbsystemproject.ailearning.infrastructure.ai.prompt.MemoryPromptService;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.MemoryItemStore;
import org.example.kbsystemproject.ailearning.infrastructure.persistence.session.SessionWhiteboardStore;
import org.example.kbsystemproject.config.MemoryProperties;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class MemoryCandidateService {

    private final SessionWhiteboardStore sessionWhiteboardStore;
    private final MemoryItemStore memoryItemStore;
    private final MemoryPromptService memoryPromptService;
    private final MemoryProperties memoryProperties;

    MemoryCandidateService(SessionWhiteboardStore sessionWhiteboardStore,
                           MemoryItemStore memoryItemStore,
                           MemoryPromptService memoryPromptService,
                           MemoryProperties memoryProperties) {
        this.sessionWhiteboardStore = sessionWhiteboardStore;
        this.memoryItemStore = memoryItemStore;
        this.memoryPromptService = memoryPromptService;
        this.memoryProperties = memoryProperties;
    }

    Mono<MemoryCollectionState> collect(TurnMemoryContext context, MemoryProcessingPlan plan) {
        MemoryPromptService.CompressionPromptInput input = memoryPromptService.buildCompressionInput(
                context.session(),
                context.turnIndex(),
                context.recentTurns(),
                context.existingWhiteboard(),
                context.recentToolMemories()
        );
        Mono<MemoryPromptService.CompressionResult> compressionMono = plan.fullCompression()
                ? memoryPromptService.compress(input)
                .onErrorResume(error -> Mono.just(memoryPromptService.fallbackResult(input)))
                : Mono.just(memoryPromptService.fallbackResult(input));
        return compressionMono.flatMap(result -> persistWhiteboardAndCandidates(context, plan, result));
    }

    Mono<MemoryCollectionState> persistCandidates(MemoryCollectionState state) {
        LearningSessionRecord session = state.context().session();
        if (!state.plan().longTermCollection()
                || session.normalizedConversationMode() != ConversationMode.MEMORY_ENABLED
                || !memoryProperties.getLongTerm().isEnabled()
                || state.candidates().isEmpty()) {
            return Mono.just(state);
        }
        return memoryItemStore.saveCandidates(session.userId(), state.candidates())
                .map(savedCandidates -> new MemoryCollectionState(
                        state.context(),
                        state.whiteboard(),
                        savedCandidates,
                        state.plan()
                ));
    }

    private Mono<MemoryCollectionState> persistWhiteboardAndCandidates(TurnMemoryContext context,
                                                                       MemoryProcessingPlan plan,
                                                                       MemoryPromptService.CompressionResult result) {
        SessionWhiteboard whiteboard = buildWhiteboard(context, plan, result);
        List<MemoryCandidate> candidates = plan.longTermCollection()
                ? selectLongTermCandidates(result.toMemoryCandidates(
                context.session().userId(),
                context.session().conversationId(),
                context.turnIndex()))
                : List.of();
        return sessionWhiteboardStore.upsert(whiteboard)
                .thenReturn(new MemoryCollectionState(context, whiteboard, candidates, plan));
    }

    private SessionWhiteboard buildWhiteboard(TurnMemoryContext context,
                                              MemoryProcessingPlan plan,
                                              MemoryPromptService.CompressionResult result) {
        LearningSessionRecord session = context.session();
        SessionWhiteboard existingWhiteboard = context.existingWhiteboard();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (existingWhiteboard != null && existingWhiteboard.metadata() != null) {
            metadata.putAll(existingWhiteboard.metadata());
        }
        metadata.put("requestId", context.requestId());
        metadata.put("turnIndex", context.turnIndex());
        metadata.put("conversationMode", session.normalizedConversationMode().name());
        metadata.put("updatedBy", "session-memory-orchestrator");
        metadata.put("whiteboardUpdateMode", plan.fullCompression() ? "full" : "incremental");
        metadata.put("currentTurnTokenEstimate", plan.currentTurnTokenEstimate());
        metadata.put("currentTurnToolCount", plan.currentTurnToolCount());
        if (plan.fullCompression()) {
            metadata.put("lastCompressionTurn", context.turnIndex());
        }
        if (plan.longTermCollection()) {
            metadata.put("lastLongTermCollectionTurn", context.turnIndex());
        }
        return new SessionWhiteboard(
                session.conversationId(),
                existingWhiteboard == null ? 1 : Math.max(1, existingWhiteboard.version() + 1),
                firstNonBlank(result.currentFocus(), existingWhiteboard == null ? null : existingWhiteboard.currentFocus(), session.currentTopic()),
                firstNonBlank(result.userGoal(), session.learningGoal(), existingWhiteboard == null ? null : existingWhiteboard.userGoal()),
                preferList(result.softHints(), existingWhiteboard == null ? List.of() : existingWhiteboard.constraints()),
                preferList(result.decisions(), existingWhiteboard == null ? List.of() : existingWhiteboard.decisions()),
                preferList(result.openQuestions(), existingWhiteboard == null ? List.of() : existingWhiteboard.openQuestions()),
                mergeToolFindings(result.recentToolFindings(), context.recentToolMemories()),
                firstNonBlank(result.continuityState(), "CONTINUE"),
                result.continuityConfidence() == null ? 0.5D : result.continuityConfidence(),
                firstNonBlank(result.rawSummary(), existingWhiteboard == null ? null : existingWhiteboard.rawSummary()),
                metadata,
                OffsetDateTime.now()
        );
    }

    private List<MemoryCandidate> selectLongTermCandidates(List<MemoryCandidate> rawCandidates) {
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return List.of();
        }
        int maxCandidatesPerTurn = Math.max(1, memoryProperties.getLongTerm().getMaxCandidatesPerTurn());
        Map<String, MemoryCandidate> candidatesByFingerprint = new LinkedHashMap<>();
        rawCandidates.stream()
                .filter(this::isEligibleLongTermCandidate)
                .sorted(longTermCandidateComparator())
                .forEach(candidate -> candidatesByFingerprint.putIfAbsent(candidateFingerprint(candidate), candidate));
        return candidatesByFingerprint.values().stream()
                .limit(maxCandidatesPerTurn)
                .toList();
    }

    private boolean isEligibleLongTermCandidate(MemoryCandidate candidate) {
        if (candidate == null || candidate.memoryValue() == null || candidate.memoryValue().isBlank()) {
            return false;
        }
        String sourceType = normalize(candidate.sourceType());
        String memoryType = normalize(candidate.type());
        int evidenceCount = evidenceCount(candidate.evidence());
        double confidence = safeDouble(candidate.confidence());

        if (!supportsLongTermType(memoryType, sourceType, candidate.memoryKey())) {
            return false;
        }
        if (sourceType.contains("user_explicit")) {
            return evidenceCount >= Math.max(1, memoryProperties.getLongTerm().getMinEvidenceCount())
                    && confidence >= memoryProperties.getLongTerm().getExplicitMinConfidence();
        }
        if (sourceType.contains("tool/system_structured") || sourceType.contains("tool_structured")) {
            return evidenceCount >= Math.max(1, memoryProperties.getLongTerm().getMinEvidenceCount())
                    && confidence >= memoryProperties.getLongTerm().getStructuredMinConfidence();
        }
        if (sourceType.contains("behavioral_pattern")) {
            return evidenceCount >= Math.max(2, memoryProperties.getLongTerm().getInferredMinEvidenceCount())
                    && confidence >= memoryProperties.getLongTerm().getBehavioralMinConfidence();
        }
        if (sourceType.contains("compression_inferred")) {
            return evidenceCount >= Math.max(2, memoryProperties.getLongTerm().getInferredMinEvidenceCount())
                    && confidence >= memoryProperties.getLongTerm().getInferredMinConfidence();
        }
        return false;
    }

    private boolean supportsLongTermType(String memoryType, String sourceType, String memoryKey) {
        return switch (memoryType) {
            case "goal", "preference", "constraint", "decision" -> true;
            case "plan" -> sourceType.contains("user_explicit") || sourceType.contains("behavioral_pattern");
            case "fact" -> (sourceType.contains("user_explicit")
                    || sourceType.contains("tool/system_structured")
                    || sourceType.contains("tool_structured"))
                    && memoryKey != null
                    && !memoryKey.isBlank();
            default -> false;
        };
    }

    private Comparator<MemoryCandidate> longTermCandidateComparator() {
        return Comparator
                .comparingInt((MemoryCandidate candidate) -> sourcePriority(candidate.sourceType()))
                .thenComparing((MemoryCandidate candidate) -> safeDouble(candidate.confidence()), Comparator.reverseOrder())
                .thenComparing((MemoryCandidate candidate) -> evidenceCount(candidate.evidence()), Comparator.reverseOrder());
    }

    private int sourcePriority(String sourceType) {
        String normalized = normalize(sourceType);
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

    private int evidenceCount(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0;
        }
        return (int) evidence.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .count();
    }

    private String candidateFingerprint(MemoryCandidate candidate) {
        return normalize(candidate.scope()) + '|'
                + normalize(candidate.type()) + '|'
                + normalize(candidate.memoryKey()) + '|'
                + normalize(candidate.memoryValue());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private double safeDouble(Double value) {
        return value == null ? 0D : value;
    }

    private List<String> preferList(List<String> candidate, List<String> fallback) {
        if (candidate != null && !candidate.isEmpty()) {
            return candidate;
        }
        return fallback == null ? List.of() : fallback;
    }

    private List<String> mergeToolFindings(List<String> candidateFindings, List<ToolMemoryEntry> recentToolMemories) {
        List<String> toolFindings = recentToolMemories == null ? List.of() : recentToolMemories.stream()
                .map(entry -> entry.summary() == null || entry.summary().isBlank() ? entry.toolName() : entry.summary())
                .filter(value -> value != null && !value.isBlank())
                .limit(6)
                .toList();
        if (candidateFindings != null && !candidateFindings.isEmpty()) {
            return candidateFindings.stream().distinct().limit(8).toList();
        }
        return toolFindings;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
