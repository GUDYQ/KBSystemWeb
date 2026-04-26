package org.example.kbsystemproject.ailearning.domain;

import java.util.Map;

// 2. 事件对象 (不可变)
public record AgentEvent(AgentState state, String content, Map<String, Object> metadata) {
    public AgentEvent {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static AgentEvent token(String content) { return new AgentEvent(AgentState.TOKEN, content, Map.of()); }
    public static AgentEvent finished(String content) { return new AgentEvent(AgentState.FINISHED, content, Map.of()); }
    public static AgentEvent error(String content) { return new AgentEvent(AgentState.ERROR, content, Map.of()); }
}
