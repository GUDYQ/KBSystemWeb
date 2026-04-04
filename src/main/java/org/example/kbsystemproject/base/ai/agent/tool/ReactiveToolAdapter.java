package org.example.kbsystemproject.base.ai.agent.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.JsonParser;

public class ReactiveToolAdapter implements ToolCallback {

    private final ReactiveTool reactiveTool;

    public ReactiveToolAdapter(ReactiveTool reactiveTool) {
        this.reactiveTool = reactiveTool;
    }

    public String getName() {
        return reactiveTool.getName();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        // 将 ReactiveTool 的元数据转换为 Spring AI 能识别的 ToolDefinition
        return ToolDefinition.builder()
                .name(reactiveTool.getName())
                .description(reactiveTool.getDescription())
                .inputSchema(JsonParser.toJson(reactiveTool.getInputType())) // 自动生成 JSON Schema
                .build();
    }

    @Override
    public String call(String toolInput) {
        throw new UnsupportedOperationException("Please use ReactiveToolRegistry for reactive execution");
    }
}
