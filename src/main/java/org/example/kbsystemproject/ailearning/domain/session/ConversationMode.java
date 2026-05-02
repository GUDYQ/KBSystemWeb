package org.example.kbsystemproject.ailearning.domain.session;

public enum ConversationMode {
    TEMPORARY,
    MEMORY_ENABLED;

    public static ConversationMode defaultMode() {
        return MEMORY_ENABLED;
    }

    public static ConversationMode normalize(ConversationMode mode) {
        return mode == null ? defaultMode() : mode;
    }

    public static ConversationMode fromValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultMode();
        }
        return ConversationMode.valueOf(rawValue.trim().toUpperCase());
    }
}
