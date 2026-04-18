package org.example.kbsystemproject.ailearning.domain;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

public sealed interface Decision permits
        Decision.Continue, Decision.CallTool, Decision.Stop, Decision.Finish {

    // 继续下一轮
    record Continue() implements Decision {}

    // 调用工具
    record CallTool(List<AssistantMessage.ToolCall> calls) implements Decision {}
    // 停止（异常或中断）
    record Stop(String reason) implements Decision {}

    // 完成（正常结束）
    record Finish(String answer) implements Decision {}
}