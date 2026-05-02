package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.session.LongTermMemoryEntry;
import org.example.kbsystemproject.ailearning.domain.session.MemoryCandidate;
import org.example.kbsystemproject.ailearning.domain.session.MemoryItem;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Repository
public class MemoryItemStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;

    public MemoryItemStore(DatabaseClient databaseClient,
                           ObjectMapper objectMapper,
                           MemoryProperties memoryProperties) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
        this.memoryProperties = memoryProperties;
    }

    public Mono<List<MemoryCandidate>> saveCandidates(String userId, List<MemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(candidates)
                .concatMap(candidate -> saveCandidate(userId, candidate))
                .collectList();
    }

    public Mono<List<LongTermMemoryEntry>> findRelevantItems(String userId, String query, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) {
            return Mono.just(List.of());
        }
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank()) {
            return databaseClient.sql("""
                            SELECT id, user_id, scope, type, memory_key, memory_value, confidence, status, source_type,
                                   evidence_count, supersedes_id, item_metadata, created_at, last_seen_at
                            FROM learning_memory_item
                            WHERE user_id = :userId
                              AND status = 'active'
                              AND confidence >= :minConfidence
                              AND type IN ('preference', 'constraint', 'decision')
                            ORDER BY confidence DESC, last_seen_at DESC, created_at DESC
                            LIMIT :limit
                            """)
                    .bind("userId", userId)
                    .bind("minConfidence", memoryProperties.getLongTerm().getRecallMinConfidence())
                    .bind("limit", Math.min(limit, Math.max(1, memoryProperties.getLongTerm().getBlankQueryTopK())))
                    .map((row, metadata) -> toLongTermMemoryEntry(toMemoryItem(row)))
                    .all()
                    .collectList();
        }
        return databaseClient.sql("""
                        SELECT id, user_id, scope, type, memory_key, memory_value, confidence, status, source_type,
                               evidence_count, supersedes_id, item_metadata, created_at, last_seen_at,
                               CASE
                                   WHEN :query = '' THEN 0
                                   WHEN LOWER(memory_value) LIKE '%' || LOWER(:query) || '%' THEN 100
                                   WHEN memory_key IS NOT NULL AND LOWER(memory_key) LIKE '%' || LOWER(:query) || '%' THEN 80
                                   WHEN LOWER(type) LIKE '%' || LOWER(:query) || '%' THEN 60
                                   ELSE 0
                               END AS match_score
                        FROM learning_memory_item
                        WHERE user_id = :userId
                          AND status = 'active'
                          AND confidence >= :minConfidence
                          AND (
                                LOWER(memory_value) LIKE '%' || LOWER(:query) || '%'
                                OR (memory_key IS NOT NULL AND LOWER(memory_key) LIKE '%' || LOWER(:query) || '%')
                                OR LOWER(type) LIKE '%' || LOWER(:query) || '%'
                          )
                        ORDER BY match_score DESC, last_seen_at DESC, created_at DESC
                        LIMIT :limit
                        """)
                .bind("userId", userId)
                .bind("query", normalizedQuery)
                .bind("minConfidence", memoryProperties.getLongTerm().getRecallMinConfidence())
                .bind("limit", limit)
                .map((row, metadata) -> toLongTermMemoryEntry(toMemoryItem(row)))
                .all()
                .collectList();
    }

    public Flux<CandidateGroupKey> findPendingCandidateGroups(int limit) {
        if (limit <= 0) {
            return Flux.empty();
        }
        return databaseClient.sql("""
                        SELECT user_id, candidate_fingerprint
                        FROM learning_memory_candidate
                        WHERE user_id IS NOT NULL
                          AND candidate_fingerprint IS NOT NULL
                          AND status IN ('pending', 'observing')
                        GROUP BY user_id, candidate_fingerprint
                        ORDER BY MAX(last_seen_at) ASC
                        LIMIT :limit
                        """)
                .bind("limit", limit)
                .map((row, metadata) -> new CandidateGroupKey(
                        row.get("user_id", String.class),
                        row.get("candidate_fingerprint", String.class)
                ))
                .all();
    }

    public Mono<List<MemoryCandidate>> findCandidateGroup(String userId, String candidateFingerprint) {
        if (isBlank(userId) || isBlank(candidateFingerprint)) {
            return Mono.just(List.of());
        }
        return databaseClient.sql("""
                        SELECT id, user_id, conversation_id, turn_index, scope, type, memory_key, memory_value,
                               confidence, source_type, candidate_fingerprint, status, evidence_json, candidate_metadata, created_at
                        FROM learning_memory_candidate
                        WHERE user_id = :userId
                          AND candidate_fingerprint = :candidateFingerprint
                          AND status IN ('pending', 'observing')
                        ORDER BY created_at ASC
                        """)
                .bind("userId", userId)
                .bind("candidateFingerprint", candidateFingerprint)
                .map((row, metadata) -> toMemoryCandidate(row))
                .all()
                .collectList();
    }

    public Mono<Void> mergeCandidate(String userId, MemoryCandidate candidate) {
        return mergeCandidateWithResult(userId, candidate).then();
    }

    public Mono<CandidateMergeResult> mergeCandidateWithResult(String userId, MemoryCandidate candidate) {
        if (userId == null || userId.isBlank() || candidate == null || isBlank(candidate.memoryValue())) {
            return markCandidateResolution(candidate, MergeDecision.rejected("invalid_candidate", null, null))
                    .thenReturn(CandidateMergeResult.from(MergeDecision.rejected("invalid_candidate", null, null)));
        }
        return findLatestActiveItem(userId, candidate.scope(), candidate.type(), candidate.memoryKey())
                .flatMap(existing -> mergeIntoExisting(userId, existing, candidate))
                .switchIfEmpty(promoteCandidate(userId, candidate))
                .flatMap(result -> markCandidateResolution(candidate, result)
                        .thenReturn(CandidateMergeResult.from(result)));
    }

    public Mono<Void> markCandidateGroupObserved(String userId, String candidateFingerprint, String reason) {
        if (isBlank(userId) || isBlank(candidateFingerprint)) {
            return Mono.empty();
        }
        return databaseClient.sql("""
                        UPDATE learning_memory_candidate
                        SET status = 'observing',
                            resolution = :resolution,
                            updated_at = NOW(),
                            last_seen_at = NOW()
                        WHERE user_id = :userId
                          AND candidate_fingerprint = :candidateFingerprint
                          AND status IN ('pending', 'observing')
                        """)
                .bind("resolution", defaultValue(reason, "await_more_observation"))
                .bind("userId", userId)
                .bind("candidateFingerprint", candidateFingerprint)
                .fetch()
                .rowsUpdated()
                .then();
    }

    public Mono<Void> markCandidateGroupResolved(String userId,
                                                 String candidateFingerprint,
                                                 CandidateMergeResult result) {
        if (isBlank(userId) || isBlank(candidateFingerprint) || result == null) {
            return Mono.empty();
        }
        Map<String, Object> metadata = Map.of(
                "mergeStatus", result.status(),
                "mergeResolution", result.resolution()
        );
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE learning_memory_candidate
                        SET status = :status,
                            resolution = :resolution,
                            resolved_item_id = :resolvedItemId,
                            supersedes_item_id = :supersedesItemId,
                            candidate_metadata = candidate_metadata || CAST(:metadataJson AS jsonb),
                            updated_at = NOW(),
                            last_seen_at = NOW(),
                            resolved_at = CASE
                                WHEN :status IN ('promoted', 'updated', 'superseded', 'rejected') THEN NOW()
                                ELSE resolved_at
                            END
                        WHERE user_id = :userId
                          AND candidate_fingerprint = :candidateFingerprint
                          AND status IN ('pending', 'observing')
                        """)
                .bind("status", result.status())
                .bind("resolution", result.resolution())
                .bind("metadataJson", stringify(metadata))
                .bind("userId", userId)
                .bind("candidateFingerprint", candidateFingerprint);
        spec = bindNullable(spec, "resolvedItemId", result.resolvedItemId(), Long.class);
        spec = bindNullable(spec, "supersedesItemId", result.supersedesItemId(), Long.class);
        return spec.fetch().rowsUpdated().then();
    }

    private Mono<MemoryCandidate> saveCandidate(String userId, MemoryCandidate candidate) {
        String normalizedUserId = nullableValue(userId);
        String fingerprint = candidateFingerprint(candidate);
        return Mono.zip(
                        toJson(normalizeEvidence(candidate.evidence())),
                        toJson(normalizeMetadata(candidate.metadata()))
                )
                .flatMap(tuple -> {
                    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                                    INSERT INTO learning_memory_candidate (
                                        user_id, conversation_id, turn_index, scope, type, memory_key, memory_value,
                                        confidence, source_type, candidate_fingerprint, status, evidence_json, candidate_metadata,
                                        created_at, updated_at, last_seen_at
                                    )
                                    VALUES (
                                        :userId, :conversationId, :turnIndex, :scope, :type, :memoryKey, :memoryValue,
                                        :confidence, :sourceType, :candidateFingerprint, 'pending',
                                        CAST(:evidenceJson AS jsonb), CAST(:metadataJson AS jsonb), NOW(), NOW(), NOW()
                                    )
                                    ON CONFLICT (conversation_id, turn_index, candidate_fingerprint)
                                    DO UPDATE SET
                                        user_id = EXCLUDED.user_id,
                                        confidence = GREATEST(COALESCE(learning_memory_candidate.confidence, 0), COALESCE(EXCLUDED.confidence, 0)),
                                        source_type = EXCLUDED.source_type,
                                        evidence_json = EXCLUDED.evidence_json,
                                        candidate_metadata = EXCLUDED.candidate_metadata,
                                        updated_at = NOW(),
                                        last_seen_at = NOW()
                                    RETURNING id, user_id, candidate_fingerprint, status, created_at
                                    """)
                            .bind("userId", normalizedUserId)
                            .bind("conversationId", candidate.conversationId())
                            .bind("evidenceJson", tuple.getT1())
                            .bind("metadataJson", tuple.getT2());
                    spec = bindNullable(spec, "turnIndex", candidate.turnIndex(), Integer.class);
                    spec = bindNullable(spec, "scope", candidate.scope(), String.class);
                    spec = bindNullable(spec, "type", candidate.type(), String.class);
                    spec = bindNullable(spec, "memoryKey", candidate.memoryKey(), String.class);
                    spec = bindNullable(spec, "memoryValue", candidate.memoryValue(), String.class);
                    spec = bindNullable(spec, "confidence", candidate.confidence(), Double.class);
                    spec = bindNullable(spec, "sourceType", candidate.sourceType(), String.class);
                    spec = bindNullable(spec, "candidateFingerprint", fingerprint, String.class);
                    return spec.map((row, metadata) -> new MemoryCandidate(
                                    row.get("id", Long.class),
                                    row.get("user_id", String.class),
                                    candidate.conversationId(),
                                    candidate.turnIndex(),
                                    candidate.scope(),
                                    candidate.type(),
                                    candidate.memoryKey(),
                                    candidate.memoryValue(),
                                    candidate.confidence(),
                                    candidate.sourceType(),
                                    row.get("candidate_fingerprint", String.class),
                                    row.get("status", String.class),
                                    candidate.evidence(),
                                    candidate.metadata(),
                                    row.get("created_at", OffsetDateTime.class)
                            ))
                            .one();
                });
    }

    private Mono<MemoryItem> findLatestActiveItem(String userId, String scope, String type, String memoryKey) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        SELECT id, user_id, scope, type, memory_key, memory_value, confidence, status, source_type,
                               evidence_count, supersedes_id, item_metadata, created_at, last_seen_at
                        FROM learning_memory_item
                        WHERE user_id = :userId
                          AND status = 'active'
                          AND scope = :scope
                          AND type = :type
                          AND (
                                (:memoryKey IS NULL AND memory_key IS NULL)
                                OR memory_key = :memoryKey
                          )
                        ORDER BY last_seen_at DESC, created_at DESC
                        LIMIT 1
                        """)
                .bind("userId", userId)
                .bind("scope", defaultValue(scope, "session"))
                .bind("type", defaultValue(type, "fact"));
        spec = memoryKey == null ? spec.bindNull("memoryKey", String.class) : spec.bind("memoryKey", memoryKey);
        return spec.map((row, metadata) -> toMemoryItem(row))
                .one();
    }

    private Mono<Void> refreshExistingItem(MemoryItem existing, MemoryCandidate candidate) {
        int nextEvidenceCount = Math.max(1, safeInteger(existing.evidenceCount())) + Math.max(1, effectiveEvidenceCount(candidate));
        double nextConfidence = Math.max(safeDouble(existing.confidence()), safeDouble(candidate.confidence()));
        Map<String, Object> mergedMetadata = mergeItemMetadata(existing, candidate, "refresh_existing", existing.id());
        return databaseClient.sql("""
                        UPDATE learning_memory_item
                        SET evidence_count = :evidenceCount,
                            confidence = :confidence,
                            source_type = :sourceType,
                            last_seen_at = NOW(),
                            item_metadata = CAST(:metadataJson AS jsonb)
                        WHERE id = :id
                        """)
                .bind("evidenceCount", nextEvidenceCount)
                .bind("confidence", nextConfidence)
                .bind("sourceType", preferSourceType(existing.sourceType(), candidate.sourceType()))
                .bind("id", existing.id())
                .bind("metadataJson", stringify(mergedMetadata))
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Long> supersedeAndInsert(String userId, MemoryItem existing, MemoryCandidate candidate) {
        return databaseClient.sql("""
                        UPDATE learning_memory_item
                        SET status = 'superseded',
                            last_seen_at = NOW()
                        WHERE id = :id
                        """)
                .bind("id", existing.id())
                .fetch()
                .rowsUpdated()
                .then(insertItem(userId, candidate, existing.id()));
    }

    private Mono<MergeDecision> mergeIntoExisting(String userId, MemoryItem existing, MemoryCandidate candidate) {
        String existingValue = normalizeText(existing.memoryValue());
        String candidateValue = normalizeText(candidate.memoryValue());

        if (existingValue.equals(candidateValue)) {
            return refreshExistingItem(existing, candidate)
                    .thenReturn(MergeDecision.updated("refresh_existing", existing.id(), null));
        }
        if (shouldSupersede(existing, candidate)) {
            return supersedeAndInsert(userId, existing, candidate)
                    .map(newItemId -> MergeDecision.superseded("supersede_existing", newItemId, existing.id()));
        }
        return Mono.just(MergeDecision.conflicted("conflict_hold", existing.id(), null));
    }

    private Mono<MergeDecision> promoteCandidate(String userId, MemoryCandidate candidate) {
        if (!isStrongEnough(candidate)) {
            return Mono.just(MergeDecision.rejected("threshold_blocked", null, null));
        }
        return insertItem(userId, candidate, null)
                .map(itemId -> MergeDecision.promoted("new_item", itemId, null));
    }

    private Mono<Long> insertItem(String userId, MemoryCandidate candidate, Long supersedesId) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        INSERT INTO learning_memory_item (
                            user_id, scope, type, memory_key, memory_value, confidence, status, source_type,
                            evidence_count, supersedes_id, item_metadata, created_at, last_seen_at
                        )
                        VALUES (
                            :userId, :scope, :type, :memoryKey, :memoryValue, :confidence, 'active', :sourceType,
                            :evidenceCount, :supersedesId, CAST(:metadataJson AS jsonb), NOW(), NOW()
                        )
                        RETURNING id
                        """)
                .bind("userId", userId)
                .bind("scope", defaultValue(candidate.scope(), "session"))
                .bind("type", defaultValue(candidate.type(), "fact"))
                .bind("memoryValue", candidate.memoryValue().trim())
                .bind("confidence", safeDouble(candidate.confidence()))
                .bind("sourceType", defaultValue(candidate.sourceType(), "compression_inferred"))
                .bind("evidenceCount", Math.max(1, effectiveEvidenceCount(candidate)))
                .bind("metadataJson", stringify(buildItemMetadata(candidate, supersedesId == null ? "new_item" : "supersede_existing", supersedesId)));
        spec = bindNullable(spec, "memoryKey", nullableValue(candidate.memoryKey()), String.class);
        spec = bindNullable(spec, "supersedesId", supersedesId, Long.class);
        return spec.map((row, metadata) -> row.get("id", Long.class)).one();
    }

    private boolean shouldSupersede(MemoryItem existing, MemoryCandidate candidate) {
        String sourceType = defaultValue(candidate.sourceType(), "").toLowerCase(Locale.ROOT);
        double candidateConfidence = safeDouble(candidate.confidence());
        double existingConfidence = safeDouble(existing.confidence());
        int candidateEvidenceCount = Math.max(1, effectiveEvidenceCount(candidate));
        int repeatedObservationFloor = Math.max(2, memoryProperties.getLongTerm().getPromotionMinDistinctTurns());
        if (sourceType.contains("user_explicit")) {
            return supportsLongTermType(candidate)
                    && effectiveEvidenceCount(candidate) >= Math.max(1, memoryProperties.getLongTerm().getMinEvidenceCount())
                    && candidateConfidence >= explicitSupersedeConfidenceFloor(existingConfidence,
                    candidateEvidenceCount >= repeatedObservationFloor);
        }
        if (sourceType.contains("tool/system_structured") || sourceType.contains("tool_structured")) {
            return supportsLongTermType(candidate)
                    && effectiveEvidenceCount(candidate) >= Math.max(1, memoryProperties.getLongTerm().getMinEvidenceCount())
                    && candidateConfidence >= Math.max(memoryProperties.getLongTerm().getStructuredMinConfidence(),
                    existingConfidence);
        }
        return supportsLongTermType(candidate)
                && effectiveEvidenceCount(candidate) >= Math.max(2, memoryProperties.getLongTerm().getInferredMinEvidenceCount())
                && candidateConfidence >= Math.max(memoryProperties.getLongTerm().getInferredMinConfidence(),
                Math.min(1.0D, existingConfidence + 0.05D));
    }

    private double explicitSupersedeConfidenceFloor(double existingConfidence, boolean repeatedObservation) {
        double baseline = repeatedObservation
                ? existingConfidence - 0.02D
                : existingConfidence;
        return Math.max(memoryProperties.getLongTerm().getExplicitMinConfidence(), baseline);
    }

    private boolean isStrongEnough(MemoryCandidate candidate) {
        String sourceType = defaultValue(candidate.sourceType(), "").toLowerCase(Locale.ROOT);
        double confidence = safeDouble(candidate.confidence());
        if (!supportsLongTermType(candidate)) {
            return false;
        }
        if (sourceType.contains("user_explicit")) {
            return effectiveEvidenceCount(candidate) >= Math.max(1, memoryProperties.getLongTerm().getMinEvidenceCount())
                    && confidence >= memoryProperties.getLongTerm().getExplicitMinConfidence();
        }
        if (sourceType.contains("tool/system_structured") || sourceType.contains("tool_structured")) {
            return effectiveEvidenceCount(candidate) >= Math.max(1, memoryProperties.getLongTerm().getMinEvidenceCount())
                    && confidence >= memoryProperties.getLongTerm().getStructuredMinConfidence();
        }
        if (sourceType.contains("behavioral_pattern")) {
            return effectiveEvidenceCount(candidate) >= Math.max(2, memoryProperties.getLongTerm().getInferredMinEvidenceCount())
                    && confidence >= memoryProperties.getLongTerm().getBehavioralMinConfidence();
        }
        if (sourceType.contains("compression_inferred")) {
            return effectiveEvidenceCount(candidate) >= Math.max(2, memoryProperties.getLongTerm().getInferredMinEvidenceCount())
                    && confidence >= memoryProperties.getLongTerm().getInferredMinConfidence();
        }
        return false;
    }

    private boolean supportsLongTermType(MemoryCandidate candidate) {
        String memoryType = defaultValue(candidate.type(), "").toLowerCase(Locale.ROOT);
        String sourceType = defaultValue(candidate.sourceType(), "").toLowerCase(Locale.ROOT);
        return switch (memoryType) {
            case "goal", "preference", "constraint", "decision" -> true;
            case "plan" -> sourceType.contains("user_explicit") || sourceType.contains("behavioral_pattern");
            case "fact" -> (sourceType.contains("user_explicit")
                    || sourceType.contains("tool/system_structured")
                    || sourceType.contains("tool_structured"))
                    && !isBlank(candidate.memoryKey());
            default -> false;
        };
    }

    private int effectiveEvidenceCount(MemoryCandidate candidate) {
        if (candidate == null) {
            return 0;
        }
        Integer distinctTurnCount = asInteger(normalizeMetadata(candidate.metadata()).get("distinctTurnCount"));
        if (distinctTurnCount != null && distinctTurnCount > 0) {
            return distinctTurnCount;
        }
        return normalizeEvidence(candidate.evidence()).size();
    }

    private Mono<Void> markCandidateResolution(MemoryCandidate candidate, MergeDecision decision) {
        if (candidate == null || candidate.id() == null) {
            return Mono.empty();
        }
        Map<String, Object> metadata = buildCandidateMetadata(candidate, decision);
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                        UPDATE learning_memory_candidate
                        SET status = :status,
                            resolution = :resolution,
                            resolved_item_id = :resolvedItemId,
                            supersedes_item_id = :supersedesItemId,
                            candidate_metadata = CAST(:metadataJson AS jsonb),
                            updated_at = NOW(),
                            last_seen_at = NOW(),
                            resolved_at = CASE WHEN :resolved THEN NOW() ELSE resolved_at END
                        WHERE id = :id
                        """)
                .bind("status", decision.status())
                .bind("resolution", decision.resolution())
                .bind("resolved", decision.isResolved())
                .bind("metadataJson", stringify(metadata))
                .bind("id", candidate.id());
        spec = bindNullable(spec, "resolvedItemId", decision.resolvedItemId(), Long.class);
        spec = bindNullable(spec, "supersedesItemId", decision.supersedesItemId(), Long.class);
        return spec.fetch().rowsUpdated().then();
    }

    private Map<String, Object> buildItemMetadata(MemoryCandidate candidate, String mergeAction, Long relatedItemId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(normalizeMetadata(candidate.metadata()));
        metadata.put("conversationId", candidate.conversationId());
        metadata.put("turnIndex", candidate.turnIndex());
        metadata.put("mergeAction", mergeAction);
        if (relatedItemId != null) {
            metadata.put("relatedItemId", relatedItemId);
        }
        return metadata;
    }

    private Map<String, Object> mergeItemMetadata(MemoryItem existing, MemoryCandidate candidate, String mergeAction, Long relatedItemId) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(normalizeMetadata(existing.metadata()));
        metadata.putAll(normalizeMetadata(candidate.metadata()));
        metadata.put("conversationId", candidate.conversationId());
        metadata.put("turnIndex", candidate.turnIndex());
        metadata.put("mergeAction", mergeAction);
        metadata.put("updateCount", safeInteger(asInteger(metadata.get("updateCount"))) + 1);
        if (relatedItemId != null) {
            metadata.put("relatedItemId", relatedItemId);
        }
        return metadata;
    }

    private Map<String, Object> buildCandidateMetadata(MemoryCandidate candidate, MergeDecision decision) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(normalizeMetadata(candidate.metadata()));
        metadata.put("mergeStatus", decision.status());
        metadata.put("mergeResolution", decision.resolution());
        if (decision.resolvedItemId() != null) {
            metadata.put("resolvedItemId", decision.resolvedItemId());
        }
        if (decision.supersedesItemId() != null) {
            metadata.put("supersedesItemId", decision.supersedesItemId());
        }
        return metadata;
    }

    private LongTermMemoryEntry toLongTermMemoryEntry(MemoryItem item) {
        String content = item.memoryKey() == null || item.memoryKey().isBlank()
                ? item.memoryValue()
                : item.memoryKey() + ": " + item.memoryValue();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>(normalizeMetadata(item.metadata()));
        metadata.put("sourceType", item.sourceType());
        metadata.put("status", item.status());
        return new LongTermMemoryEntry(
                String.valueOf(item.id()),
                item.userId(),
                item.type(),
                content,
                item.confidence(),
                item.lastSeenAt(),
                metadata
        );
    }

    private MemoryItem toMemoryItem(io.r2dbc.spi.Row row) {
        return new MemoryItem(
                row.get("id", Long.class),
                row.get("user_id", String.class),
                row.get("scope", String.class),
                row.get("type", String.class),
                row.get("memory_key", String.class),
                row.get("memory_value", String.class),
                row.get("confidence", Double.class),
                row.get("status", String.class),
                row.get("source_type", String.class),
                row.get("evidence_count", Integer.class),
                row.get("supersedes_id", Long.class),
                parseMetadata(row.get("item_metadata", String.class)),
                row.get("created_at", OffsetDateTime.class),
                row.get("last_seen_at", OffsetDateTime.class)
        );
    }

    private MemoryCandidate toMemoryCandidate(io.r2dbc.spi.Row row) {
        return new MemoryCandidate(
                row.get("id", Long.class),
                row.get("user_id", String.class),
                row.get("conversation_id", String.class),
                row.get("turn_index", Integer.class),
                row.get("scope", String.class),
                row.get("type", String.class),
                row.get("memory_key", String.class),
                row.get("memory_value", String.class),
                row.get("confidence", Double.class),
                row.get("source_type", String.class),
                row.get("candidate_fingerprint", String.class),
                row.get("status", String.class),
                parseEvidence(row.get("evidence_json", String.class)),
                parseMetadata(row.get("candidate_metadata", String.class)),
                row.get("created_at", OffsetDateTime.class)
        );
    }

    private DatabaseClient.GenericExecuteSpec bindNullable(DatabaseClient.GenericExecuteSpec spec,
                                                           String name,
                                                           Object value,
                                                           Class<?> type) {
        return value == null ? spec.bindNull(name, type) : spec.bind(name, value);
    }

    private Mono<String> toJson(Object value) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(value));
    }

    private String stringify(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to serialize memory metadata", error);
        }
    }

    private List<String> parseEvidence(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, STRING_LIST_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse memory evidence", error);
        }
    }

    private Map<String, Object> parseMetadata(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse memory metadata", error);
        }
    }

    private List<String> normalizeEvidence(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(10)
                .toList();
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        return metadata == null ? Map.of() : metadata;
    }

    private String candidateFingerprint(MemoryCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        return List.of(
                        normalizeText(candidate.scope()),
                        normalizeText(candidate.type()),
                        normalizeText(candidate.memoryKey()),
                        normalizeText(candidate.memoryValue())
                ).stream()
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + "|" + right)
                .orElse(null);
    }

    private String preferSourceType(String existingSourceType, String candidateSourceType) {
        return sourcePriority(candidateSourceType) <= sourcePriority(existingSourceType)
                ? defaultValue(candidateSourceType, defaultValue(existingSourceType, "compression_inferred"))
                : defaultValue(existingSourceType, defaultValue(candidateSourceType, "compression_inferred"));
    }

    private int sourcePriority(String sourceType) {
        String normalized = defaultValue(sourceType, "").toLowerCase(Locale.ROOT);
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

    private String defaultValue(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String nullableValue(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int safeInteger(Integer value) {
        return value == null ? 0 : value;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private double safeDouble(Double value) {
        return value == null ? 0D : value;
    }

    public record CandidateGroupKey(String userId, String candidateFingerprint) {
    }

    public record CandidateMergeResult(String status,
                                       String resolution,
                                       Long resolvedItemId,
                                       Long supersedesItemId) {
        private static CandidateMergeResult from(MergeDecision decision) {
            return new CandidateMergeResult(
                    decision.status(),
                    decision.resolution(),
                    decision.resolvedItemId(),
                    decision.supersedesItemId()
            );
        }
    }

    private record MergeDecision(String status,
                                 String resolution,
                                 Long resolvedItemId,
                                 Long supersedesItemId,
                                 boolean resolved) {

        private static MergeDecision promoted(String resolution, Long resolvedItemId, Long supersedesItemId) {
            return new MergeDecision("promoted", resolution, resolvedItemId, supersedesItemId, true);
        }

        private static MergeDecision updated(String resolution, Long resolvedItemId, Long supersedesItemId) {
            return new MergeDecision("updated", resolution, resolvedItemId, supersedesItemId, true);
        }

        private static MergeDecision superseded(String resolution, Long resolvedItemId, Long supersedesItemId) {
            return new MergeDecision("superseded", resolution, resolvedItemId, supersedesItemId, true);
        }

        private static MergeDecision conflicted(String resolution, Long resolvedItemId, Long supersedesItemId) {
            return new MergeDecision("conflicted", resolution, resolvedItemId, supersedesItemId, true);
        }

        private static MergeDecision rejected(String resolution, Long resolvedItemId, Long supersedesItemId) {
            return new MergeDecision("rejected", resolution, resolvedItemId, supersedesItemId, true);
        }

        private boolean isResolved() {
            return resolved;
        }
    }
}
