package org.example.kbsystemproject.ailearning.domain;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ToolContext;

import java.util.*;

public record AgentContext(
        List<Message> history,
        int currentStep,
        // 【新增】业务上下文：存储 userId, tenantId 等业务数据
        Map<String, Object> businessContext
) {
    // 常用 Key 定义
    public static final String USER_ID = "userId";
    public static final String TENANT_ID = "tenantId";

    public AgentContext(List<Message> history, int currentStep) {
        this(history, currentStep, Collections.emptyMap());
    }

    public AgentContext(List<Message> history, int currentStep, Map<String, Object> businessContext) {
        this.history = history;
        this.currentStep = currentStep;
        this.businessContext = businessContext != null ? businessContext : Collections.emptyMap();
    }

    // 辅助方法：追加历史，保留 context
    public AgentContext appendHistory(Message message) {
        List<Message> newHistory = new ArrayList<>(this.history);
        newHistory.add(message);
        return new AgentContext(Collections.unmodifiableList(newHistory), this.currentStep, this.businessContext);
    }

    // 辅助方法：步进，保留 context
    public AgentContext nextStep() {
        return new AgentContext(this.history, this.currentStep + 1, this.businessContext);
    }

    // 记录 AssistantMessage (请求)
    public AgentContext appendAssistantMessage(AssistantMessage message) {
        List<Message> newHistory = new ArrayList<>(this.history);
        newHistory.add(message);
        return new AgentContext(newHistory, this.currentStep, this.businessContext);
    }

    // 【关键修改】追加 ToolResponseMessage (工具响应)
    // 必须包含 ID 映射
    public AgentContext appendToolResponses(List<ToolResponseMessage.ToolResponse> responses) {
        List<Message> newHistory = new ArrayList<>(this.history);

        // Spring AI 构造函数：new ToolResponseMessage(List<ToolResponse> responses)
        // 或带有 metadata 的版本
        ToolResponseMessage responseMessage = ToolResponseMessage.builder()
                .responses(responses)
                        .build();

        newHistory.add(responseMessage);
        return new AgentContext(newHistory, this.currentStep, this.businessContext);
    }

    public ToolContext toToolContext() {
        Map<String, Object> contextMap = new HashMap<>();

        // 1. 放入业务上下文
        if (businessContext != null) {
            contextMap.putAll(businessContext);
        }

        // 2. 【关键】遵循 Spring AI 约定，放入历史记录
        // 这样工具内部如果需要知道之前的对话，可以通过 toolContext.getToolCallHistory() 获取
        contextMap.put(ToolContext.TOOL_CALL_HISTORY, this.history);

        return new ToolContext(contextMap);
    }

    // 辅助方法
    public String getUserId() {
        return businessContext != null ? (String) businessContext.get(USER_ID) : null;
    }
}
