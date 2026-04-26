package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.session.ToolExecutionStatus;
import org.example.kbsystemproject.ailearning.domain.session.ToolExecutionTrace;
import org.example.kbsystemproject.ailearning.domain.session.ToolMemoryEntry;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class LearningSessionToolTraceStore {

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public LearningSessionToolTraceStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> saveTraces(String conversationId,
                                 String requestId,
                                 int turnIndex,
                                 List<ToolExecutionTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(traces)
                .filter(this::isPersistableTrace)
                .concatMap(trace -> saveTrace(conversationId, requestId, turnIndex, trace))
                .then();
    }

    public Mono<List<ToolMemoryEntry>> loadRecentToolMemories(String conversationId, int limit) {
        if (limit <= 0) {
            return Mono.just(List.of());
        }
        return databaseClient.sql("""
                        SELECT turn_index, step_index, tool_name, result_summary, tool_status, created_at
                        FROM learning_session_tool_trace
                        WHERE conversation_id = :conversationId
                          AND result_summary IS NOT NULL
                          AND result_summary <> ''
                        ORDER BY turn_index DESC, step_index DESC, created_at DESC
                        LIMIT :limit
                        """)
                .bind("conversationId", conversationId)
                .bind("limit", limit)
                .map((row, metadata) -> new ToolMemoryEntry(
                        row.get("turn_index", Integer.class),
                        row.get("step_index", Integer.class),
                        row.get("tool_name", String.class),
                        row.get("result_summary", String.class),
                        resolveStatus(row.get("tool_status", String.class)),
                        row.get("created_at", OffsetDateTime.class)
                ))
                .all()
                .collectList()
                .map(entries -> {
                    Collections.reverse(entries);
                    return entries;
                });
    }

    private Mono<Void> saveTrace(String conversationId,
                                 String requestId,
                                 int turnIndex,
                                 ToolExecutionTrace trace) {
        return Mono.fromCallable(() -> TracePayload.from(trace, objectMapper))
                .flatMap(payload -> {
                    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                                    INSERT INTO learning_session_tool_trace (
                                        conversation_id,
                                        request_id,
                                        turn_index,
                                        step_index,
                                        tool_call_id,
                                        tool_name,
                                        tool_status,
                                        tool_type,
                                        arguments_json,
                                        result_text,
                                        result_summary,
                                        error_message,
                                        duration_ms,
                                        started_at,
                                        finished_at,
                                        trace_metadata
                                    )
                                    VALUES (
                                        :conversationId,
                                        :requestId,
                                        :turnIndex,
                                        :stepIndex,
                                        :toolCallId,
                                        :toolName,
                                        :toolStatus,
                                        :toolType,
                                        CAST(:argumentsJson AS jsonb),
                                        :resultText,
                                        :resultSummary,
                                        :errorMessage,
                                        :durationMs,
                                        :startedAt,
                                        :finishedAt,
                                        CAST(:traceMetadata AS jsonb)
                                    )
                                    ON CONFLICT (conversation_id, request_id, tool_call_id) DO NOTHING
                                    """)
                            .bind("conversationId", conversationId)
                            .bind("requestId", requestId)
                            .bind("turnIndex", turnIndex)
                            .bind("stepIndex", trace.stepIndex())
                            .bind("toolCallId", trace.toolCallId())
                            .bind("toolName", trace.toolName())
                            .bind("toolStatus", trace.status() == null ? ToolExecutionStatus.SKIPPED.name() : trace.status().name())
                            .bind("argumentsJson", payload.argumentsJson())
                            .bind("traceMetadata", payload.traceMetadata());
                    spec = bindNullable(spec, "toolType", trace.toolType(), String.class);
                    spec = bindNullable(spec, "resultText", trace.resultText(), String.class);
                    spec = bindNullable(spec, "resultSummary", trace.resultSummary(), String.class);
                    spec = bindNullable(spec, "errorMessage", trace.errorMessage(), String.class);
                    spec = bindNullable(spec, "durationMs", trace.durationMs(), Long.class);
                    spec = bindNullable(spec, "startedAt", trace.startedAt(), OffsetDateTime.class);
                    spec = bindNullable(spec, "finishedAt", trace.finishedAt(), OffsetDateTime.class);
                    return spec.fetch().rowsUpdated().then();
                });
    }

    private <T> DatabaseClient.GenericExecuteSpec bindNullable(DatabaseClient.GenericExecuteSpec spec,
                                                               String name,
                                                               T value,
                                                               Class<T> type) {
        if (value == null) {
            return spec.bindNull(name, type);
        }
        return spec.bind(name, value);
    }

    private ToolExecutionStatus resolveStatus(String value) {
        if (value == null || value.isBlank()) {
            return ToolExecutionStatus.SKIPPED;
        }
        return ToolExecutionStatus.valueOf(value);
    }

    private boolean isPersistableTrace(ToolExecutionTrace trace) {
        return trace != null
                && trace.toolCallId() != null
                && !trace.toolCallId().isBlank()
                && trace.toolName() != null
                && !trace.toolName().isBlank();
    }

    private record TracePayload(String argumentsJson, String traceMetadata) {
        private static TracePayload from(ToolExecutionTrace trace, ObjectMapper objectMapper) throws JsonProcessingException {
            return new TracePayload(
                    normalizeArguments(trace.argumentsJson(), objectMapper),
                    objectMapper.writeValueAsString(normalizeMetadata(trace.metadata()))
            );
        }

        private static String normalizeArguments(String rawArguments, ObjectMapper objectMapper) throws JsonProcessingException {
            if (rawArguments == null || rawArguments.isBlank()) {
                return "null";
            }
            try {
                return objectMapper.writeValueAsString(objectMapper.readTree(rawArguments));
            } catch (Exception ignored) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("raw", rawArguments);
                return objectMapper.writeValueAsString(fallback);
            }
        }

        private static Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
            return metadata == null ? Map.of() : metadata;
        }
    }
}
