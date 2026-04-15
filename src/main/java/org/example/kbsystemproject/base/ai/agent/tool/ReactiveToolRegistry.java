package org.example.kbsystemproject.base.ai.agent.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ReactiveToolRegistry {

    private final Map<String, ReactiveTool> tools = new ConcurrentHashMap<>();
    private final List<ToolCallback> callbacks = new ArrayList<>();

    public void register(ReactiveTool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * 获取 Spring AI 需要的 ToolDefinitions
     */
    public List<ToolCallback> getToolCallbacks() {
        return callbacks;
    }

    /**
     * 响应式执行工具
     */
    public Mono<String> execute(String toolName, String arguments, ToolContext context) {
        ReactiveTool tool = tools.get(toolName);
        if (tool == null) {
            return Mono.error(new IllegalArgumentException("Tool not found: " + toolName));
        }
        return tool.execute(arguments, context);
    }
}
