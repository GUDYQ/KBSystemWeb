package org.example.kbsystemproject.ailearning.domain;

// 2. 事件对象 (不可变)
public record AgentEvent(AgentState state, String content) {
    public static AgentEvent token(String content) { return new AgentEvent(AgentState.TOKEN, content); }
    public static AgentEvent finished(String content) { return new AgentEvent(AgentState.FINISHED, content); }
    public static AgentEvent error(String content) { return new AgentEvent(AgentState.ERROR, content); }
}