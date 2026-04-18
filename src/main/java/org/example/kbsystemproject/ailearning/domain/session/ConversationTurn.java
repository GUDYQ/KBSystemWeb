package org.example.kbsystemproject.ailearning.domain.session;

import java.time.OffsetDateTime;
import java.util.Map;

public record ConversationTurn(
        SessionMessageRole role,
        String content,
        OffsetDateTime createdAt,
        Map<String, Object> metadata
) {
    public ConversationTurn(SessionMessageRole role, String content) {
        this(role, content, OffsetDateTime.now(), Map.of());
    }
}
