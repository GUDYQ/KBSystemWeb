package org.example.kbsystemproject.base.ai.agent.tool;

import java.util.Map;

/**
 * 工具执行器接口
 */
public interface ToolExecutor {
    /**
     * 执行工具
     * @param toolName 工具名称
     * @param arguments LLM 生成的参数
     * @param context 运行时上下文 (由 Agent 框架传入，包含 userId, sessionId 等)
     * @return 执行结果
     */
    String execute(String toolName, String arguments, Map<String, Object> context);
}
