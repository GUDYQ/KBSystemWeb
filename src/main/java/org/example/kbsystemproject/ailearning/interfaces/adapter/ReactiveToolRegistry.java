package org.example.kbsystemproject.ailearning.interfaces.adapter;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.AsyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ReactiveToolRegistry {

    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();
    private final List<ToolCallback> callbacks = new ArrayList<>();

    public void register(ReactiveTool tool) {
        tools.put(tool.getName(), new RegisteredTool(tool, "reactive_tool", Map.of()));
        callbacks.add(new ReactiveToolAdapter(tool));
    }

    public void register(ToolCallback callback) {
        callbacks.add(callback);
        Map<String, Object> metadata = resolveCallbackMetadata(callback);
        String sourceType = callback instanceof AsyncMcpToolCallback ? "mcp_async" : "tool_callback";
        tools.put(callback.getToolDefinition().name(), new RegisteredTool(new ReactiveTool() {
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
                return Mono.fromCallable(() -> callback.call(toolInput, context));
            }
        }, sourceType, metadata));
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
        RegisteredTool registeredTool = tools.get(toolName);
        if (registeredTool == null) {
            return Mono.error(new IllegalArgumentException("Tool not found: " + toolName));
        }
        return registeredTool.tool().execute(arguments, context);
    }

    public ToolRegistration resolve(String toolName) {
        RegisteredTool registeredTool = tools.get(toolName);
        if (registeredTool == null) {
            return ToolRegistration.missing(toolName);
        }
        return new ToolRegistration(toolName, registeredTool.sourceType(), registeredTool.metadata());
    }

    private Map<String, Object> resolveCallbackMetadata(ToolCallback callback) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("callbackClass", callback.getClass().getName());
        if (callback instanceof AsyncMcpToolCallback asyncMcpToolCallback) {
            String originalToolName = asyncMcpToolCallback.getOriginalToolName();
            if (originalToolName != null && !originalToolName.isBlank()) {
                metadata.put("originalToolName", originalToolName);
            }
        }
        return Map.copyOf(metadata);
    }

    private record RegisteredTool(ReactiveTool tool, String sourceType, Map<String, Object> metadata) {
    }

    public record ToolRegistration(String toolName, String sourceType, Map<String, Object> metadata) {
        public ToolRegistration {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public static ToolRegistration missing(String toolName) {
            return new ToolRegistration(toolName, "unknown", Map.of());
        }
    }
}
