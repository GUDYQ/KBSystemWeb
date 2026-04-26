package org.example.kbsystemproject.ailearning.domain.session;

import org.example.kbsystemproject.ailearning.domain.AgentEvent;
import org.example.kbsystemproject.ailearning.domain.AgentState;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolExecutionTrace(
        int stepIndex,
        String toolCallId,
        String toolName,
        String toolType,
        ToolExecutionStatus status,
        String argumentsJson,
        String resultText,
        String resultSummary,
        String errorMessage,
        Long durationMs,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Map<String, Object> metadata
) {

    public ToolExecutionTrace {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public AgentEvent toAgentEvent(AgentState state) {
        return new AgentEvent(state, resolveEventContent(), toMetadataMap());
    }

    public Map<String, Object> toMetadataMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stepIndex", stepIndex);
        putIfNotBlank(payload, "toolCallId", toolCallId);
        putIfNotBlank(payload, "toolName", toolName);
        putIfNotBlank(payload, "toolType", toolType);
        if (status != null) {
            payload.put("toolStatus", status.name());
        }
        putIfNotBlank(payload, "argumentsJson", argumentsJson);
        putIfNotBlank(payload, "resultText", resultText);
        putIfNotBlank(payload, "resultSummary", resultSummary);
        putIfNotBlank(payload, "errorMessage", errorMessage);
        if (durationMs != null) {
            payload.put("durationMs", durationMs);
        }
        if (startedAt != null) {
            payload.put("startedAt", startedAt.toString());
        }
        if (finishedAt != null) {
            payload.put("finishedAt", finishedAt.toString());
        }
        if (!metadata.isEmpty()) {
            payload.put("traceMetadata", metadata);
        }
        return payload;
    }

    public static ToolExecutionTrace fromAgentEvent(AgentEvent event) {
        if (event == null || event.metadata() == null || event.metadata().isEmpty()) {
            return null;
        }
        Map<String, Object> metadata = event.metadata();
        @SuppressWarnings("unchecked")
        Map<String, Object> traceMetadata = metadata.get("traceMetadata") instanceof Map<?, ?> rawMap
                ? (Map<String, Object>) rawMap
                : Map.of();
        return new ToolExecutionTrace(
                intValue(metadata.get("stepIndex")),
                stringValue(metadata.get("toolCallId")),
                stringValue(metadata.get("toolName")),
                stringValue(metadata.get("toolType")),
                enumValue(metadata.get("toolStatus")),
                stringValue(metadata.get("argumentsJson")),
                stringValue(metadata.get("resultText")),
                stringValue(metadata.get("resultSummary")),
                stringValue(metadata.get("errorMessage")),
                longValue(metadata.get("durationMs")),
                offsetDateTimeValue(metadata.get("startedAt")),
                offsetDateTimeValue(metadata.get("finishedAt")),
                traceMetadata
        );
    }

    private String resolveEventContent() {
        if (status == ToolExecutionStatus.STARTED) {
            return toolName;
        }
        if (resultSummary != null && !resultSummary.isBlank()) {
            return resultSummary;
        }
        if (errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        return toolName;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = stringValue(value);
        return text == null ? 0 : Integer.parseInt(text);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = stringValue(value);
        return text == null ? null : Long.parseLong(text);
    }

    private static OffsetDateTime offsetDateTimeValue(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        String text = stringValue(value);
        return text == null ? null : OffsetDateTime.parse(text);
    }

    private static ToolExecutionStatus enumValue(Object value) {
        String text = stringValue(value);
        return text == null ? null : ToolExecutionStatus.valueOf(text);
    }

    private static void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }
}
