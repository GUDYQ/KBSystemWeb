package org.example.kbsystemproject.ailearning.interfaces.adapter;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ReactiveToolRegistry {

    private final Map<String, ReactiveTool> tools = new ConcurrentHashMap<>();
    private final List<ToolCallback> callbacks = new ArrayList<>();

    public void register(ReactiveTool tool) {
        tools.put(tool.getName(), tool);
        callbacks.add(new ReactiveToolAdapter(tool));
    }

    public void register(ToolCallback callback) {
        callbacks.add(callback);
        tools.put(callback.getToolDefinition().name(), new ReactiveTool() {
            @Override
            public String getName() {
                return callback.getToolDefinition().name();
            }

            @Override
            public String getDescription() {
                return callback.getToolDefinition().description();
            }

            @Override
            public Class<?> getInputType() {
                return String.class; // fallback as schema is driven by toolCallback itself
            }

            @Override
            public Mono<String> execute(String toolInput, ToolContext context) {
                return Mono.fromCallable(() -> callback.call(toolInput));
            }
        });
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
