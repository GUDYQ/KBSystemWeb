package org.example.kbsystemproject.ailearning.domain.intent;

import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;

import java.util.LinkedHashMap;
import java.util.Map;

public record IntentDecision(
        IntentType intentType,
        LearningSessionType resolvedSessionType,
        ExecutionMode executionMode,
        double confidence,
        IntentSource source,
        boolean needRetrieval,
        boolean needToolCall,
        String reason
) {

    public Map<String, Object> toMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intentType", intentType.name());
        metadata.put("resolvedSessionType", resolvedSessionType.name());
        metadata.put("executionMode", executionMode.name());
        metadata.put("intentConfidence", confidence);
        metadata.put("intentSource", source.name());
        metadata.put("needRetrieval", needRetrieval);
        metadata.put("needToolCall", needToolCall);
        if (reason != null && !reason.isBlank()) {
            metadata.put("intentReason", reason);
        }
        return metadata;
    }
}
