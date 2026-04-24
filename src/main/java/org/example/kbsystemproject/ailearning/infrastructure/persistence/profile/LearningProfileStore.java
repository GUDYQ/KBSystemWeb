package org.example.kbsystemproject.ailearning.infrastructure.persistence.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileRecord;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;

@Repository
public class LearningProfileStore {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public LearningProfileStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    // 按用户和学科读取结构化学习画像。
    public Mono<LearningProfileRecord> findByUserIdAndSubject(String userId, String subject) {
        return databaseClient.sql("""
                        SELECT id, user_id, subject, learning_goal, preferred_style, preferred_language, weak_points_json, updated_at
                        FROM learning_profile
                        WHERE user_id = :userId AND subject = :subject
                        """)
                .bind("userId", userId)
                .bind("subject", subject)
                .map((row, metadata) -> new LearningProfileRecord(
                        row.get("id", Long.class),
                        row.get("user_id", String.class),
                        row.get("subject", String.class),
                        row.get("learning_goal", String.class),
                        row.get("preferred_style", String.class),
                        row.get("preferred_language", String.class),
                        parseWeakPoints(row.get("weak_points_json", String.class)),
                        row.get("updated_at", OffsetDateTime.class)
                ))
                .one();
    }

    // 对学习画像做 upsert；弱项列表先标准化，再做合并更新。
    public Mono<Void> upsert(String userId,
                             String subject,
                             String learningGoal,
                             String preferredStyle,
                             String preferredLanguage,
                             List<String> weakPoints) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(normalizeWeakPoints(weakPoints)))
                .flatMap(weakPointsJson -> databaseClient.sql("""
                                INSERT INTO learning_profile (
                                    user_id, subject, learning_goal, preferred_style, preferred_language, weak_points_json, updated_at
                                )
                                VALUES (
                                    :userId, :subject, :learningGoal, :preferredStyle, :preferredLanguage,
                                    CAST(:weakPointsJson AS jsonb), NOW()
                                )
                                ON CONFLICT (user_id, subject) DO UPDATE
                                SET learning_goal = COALESCE(EXCLUDED.learning_goal, learning_profile.learning_goal),
                                    preferred_style = COALESCE(EXCLUDED.preferred_style, learning_profile.preferred_style),
                                    preferred_language = COALESCE(EXCLUDED.preferred_language, learning_profile.preferred_language),
                                    weak_points_json = CASE
                                        WHEN EXCLUDED.weak_points_json = '[]'::jsonb THEN learning_profile.weak_points_json
                                        ELSE EXCLUDED.weak_points_json
                                    END,
                                    updated_at = NOW()
                                """)
                        .bind("userId", userId)
                        .bind("subject", subject)
                        .bindNull("learningGoal", String.class)
                        .bindNull("preferredStyle", String.class)
                        .bindNull("preferredLanguage", String.class)
                        .bind("weakPointsJson", weakPointsJson)
                        .fetch()
                        .rowsUpdated()
                        .then())
                .then(applyPartialUpdate(userId, subject, learningGoal, preferredStyle, preferredLanguage, weakPoints));
    }

    // 在已有画像基础上做局部字段覆盖和弱项集合合并。
    private Mono<Void> applyPartialUpdate(String userId,
                                          String subject,
                                          String learningGoal,
                                          String preferredStyle,
                                          String preferredLanguage,
                                          List<String> weakPoints) {
        return findByUserIdAndSubject(userId, subject)
                .defaultIfEmpty(new LearningProfileRecord(null, userId, subject, null, null, null, List.of(), null))
                .flatMap(existing -> Mono.fromCallable(() -> objectMapper.writeValueAsString(
                                normalizeWeakPoints(mergeWeakPoints(existing.weakPoints(), weakPoints))
                        ))
                        .flatMap(weakPointsJson -> {
                            DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                                            UPDATE learning_profile
                                            SET learning_goal = :learningGoal,
                                                preferred_style = :preferredStyle,
                                                preferred_language = :preferredLanguage,
                                                weak_points_json = CAST(:weakPointsJson AS jsonb),
                                                updated_at = NOW()
                                            WHERE user_id = :userId AND subject = :subject
                                            """);
                            String resolvedGoal = coalesce(learningGoal, existing.learningGoal());
                            String resolvedStyle = coalesce(preferredStyle, existing.preferredStyle());
                            String resolvedLanguage = coalesce(preferredLanguage, existing.preferredLanguage());
                            spec = resolvedGoal == null ? spec.bindNull("learningGoal", String.class) : spec.bind("learningGoal", resolvedGoal);
                            spec = resolvedStyle == null ? spec.bindNull("preferredStyle", String.class) : spec.bind("preferredStyle", resolvedStyle);
                            spec = resolvedLanguage == null ? spec.bindNull("preferredLanguage", String.class) : spec.bind("preferredLanguage", resolvedLanguage);
                            return spec.bind("weakPointsJson", weakPointsJson)
                                    .bind("userId", userId)
                                    .bind("subject", subject)
                                    .fetch()
                                    .rowsUpdated()
                                    .then();
                        }));
    }

    // 空值不覆盖旧值。
    private String coalesce(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    // 合并弱项列表并去重，保持原有顺序优先。
    private List<String> mergeWeakPoints(List<String> existing, List<String> incoming) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            merged.addAll(existing);
        }
        if (incoming != null) {
            merged.addAll(incoming);
        }
        return List.copyOf(merged);
    }

    // 对弱项列表做清洗、去重和数量限制。
    private List<String> normalizeWeakPoints(List<String> weakPoints) {
        if (weakPoints == null || weakPoints.isEmpty()) {
            return List.of();
        }
        return weakPoints.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(10)
                .toList();
    }

    // 把 JSONB 中的弱项数组解析回 Java 列表。
    private List<String> parseWeakPoints(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, LIST_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse learning profile weak points", error);
        }
    }
}
