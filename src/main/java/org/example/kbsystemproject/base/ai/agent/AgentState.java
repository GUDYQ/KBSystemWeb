package org.example.kbsystemproject.base.ai.agent;
// 1. 状态枚举
public enum AgentState {
    TOKEN,              // 流式 Token
    TOOL_START,         // 工具开始调用
    TOOL_RESULT,        // 工具调用结果
    ITERATION_COMPLETE, // 单轮迭代完成
    TERMINAL,           // 任务终止（未完成）
    FINISHED,           // 任务完成
    ERROR               // 发生错误
}
