package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.session.SessionWhiteboard;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class SessionWhiteboardStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public SessionWhiteboardStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    public Mono<SessionWhiteboard> findByConversationId(String conversationId) {
        return databaseClient.sql("""
                        SELECT conversation_id, version, current_focus, user_goal, constraints_json, decisions_json,
                               open_questions_json, recent_tool_findings_json, continuity_state, continuity_confidence,
                               raw_summary, whiteboard_metadata, updated_at
                        FROM learning_session_whiteboard
                        WHERE conversation_id = :conversationId
                        """)
                .bind("conversationId", conversationId)
                .map((row, metadata) -> new SessionWhiteboard(
                        row.get("conversation_id", String.class),
                        row.get("version", Integer.class),
                        row.get("current_focus", String.class),
                        row.get("user_goal", String.class),
                        parseStringList(row.get("constraints_json", String.class)),
                        parseStringList(row.get("decisions_json", String.class)),
                        parseStringList(row.get("open_questions_json", String.class)),
                        parseStringList(row.get("recent_tool_findings_json", String.class)),
                        row.get("continuity_state", String.class),
                        row.get("continuity_confidence", Double.class),
                        row.get("raw_summary", String.class),
                        parseMetadata(row.get("whiteboard_metadata", String.class)),
                        row.get("updated_at", OffsetDateTime.class)
                ))
                .one();
    }

    public Mono<Void> upsert(SessionWhiteboard whiteboard) {
        return Mono.zip(
                        toJson(normalizeList(whiteboard.constraints())),
                        toJson(normalizeList(whiteboard.decisions())),
                        toJson(normalizeList(whiteboard.openQuestions())),
                        toJson(normalizeList(whiteboard.recentToolFindings())),
                        toJson(normalizeMetadata(whiteboard.metadata()))
                )
                .flatMap(tuple -> {
                    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                                    INSERT INTO learning_session_whiteboard (
                                        conversation_id, version, current_focus, user_goal, constraints_json, decisions_json,
                                        open_questions_json, recent_tool_findings_json, continuity_state, continuity_confidence,
                                        raw_summary, whiteboard_metadata, updated_at
                                    )
                                    VALUES (
                                        :conversationId, :version, :currentFocus, :userGoal, CAST(:constraintsJson AS jsonb), CAST(:decisionsJson AS jsonb),
                                        CAST(:openQuestionsJson AS jsonb), CAST(:recentToolFindingsJson AS jsonb), :continuityState, :continuityConfidence,
                                        :rawSummary, CAST(:metadataJson AS jsonb), NOW()
                                    )
                                    ON CONFLICT (conversation_id) DO UPDATE
                                    SET version = EXCLUDED.version,
                                        current_focus = EXCLUDED.current_focus,
                                        user_goal = EXCLUDED.user_goal,
                                        constraints_json = EXCLUDED.constraints_json,
                                        decisions_json = EXCLUDED.decisions_json,
                                        open_questions_json = EXCLUDED.open_questions_json,
                                        recent_tool_findings_json = EXCLUDED.recent_tool_findings_json,
                                        continuity_state = EXCLUDED.continuity_state,
                                        continuity_confidence = EXCLUDED.continuity_confidence,
                                        raw_summary = EXCLUDED.raw_summary,
                                        whiteboard_metadata = EXCLUDED.whiteboard_metadata,
                                        updated_at = NOW()
                                    """)
                            .bind("conversationId", whiteboard.conversationId())
                            .bind("version", whiteboard.version() == null || whiteboard.version() <= 0 ? 1 : whiteboard.version())
                            .bind("constraintsJson", tuple.getT1())
                            .bind("decisionsJson", tuple.getT2())
                            .bind("openQuestionsJson", tuple.getT3())
                            .bind("recentToolFindingsJson", tuple.getT4())
                            .bind("metadataJson", tuple.getT5());
                    spec = bindNullable(spec, "currentFocus", whiteboard.currentFocus(), String.class);
                    spec = bindNullable(spec, "userGoal", whiteboard.userGoal(), String.class);
                    spec = bindNullable(spec, "continuityState", whiteboard.continuityState(), String.class);
                    spec = bindNullable(spec, "continuityConfidence", whiteboard.continuityConfidence(), Double.class);
                    spec = bindNullable(spec, "rawSummary", whiteboard.rawSummary(), String.class);
                    return spec.fetch().rowsUpdated().then();
                });
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

    private List<String> parseStringList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, STRING_LIST_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse session whiteboard list field", error);
        }
    }

    private Map<String, Object> parseMetadata(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse session whiteboard metadata", error);
        }
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(20)
                .toList();
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        return metadata == null ? Map.of() : metadata;
    }
}
