package org.example.kbsystemproject.ailearning.domain;

/**
 * 内部信号：用于驱动循环
 * sealed 确保只有这两种信号
 */
public sealed interface AgentSignal permits AgentSignal.Event, AgentSignal.Next {

    // 对外暴露的事件
    record Event(AgentEvent event) implements AgentSignal {}

    // 内部循环指令：告诉 BaseAgent 用新的 Context 继续循环
    // 如果 nextContext 为 null，则终止循环
    record Next(AgentContext nextContext) implements AgentSignal {}

    // 工厂方法
    static AgentSignal event(AgentEvent e) { return new Event(e); }
    static AgentSignal next(AgentContext ctx) { return new Next(ctx); }
}