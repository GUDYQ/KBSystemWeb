package org.example.kbsystemproject.ailearning.interfaces.adapter;

import org.springaicommunity.mcp.method.tool.utils.JsonSchemaGenerator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

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
        // 直接调用官方的 JsonSchema 工具类生成
        String schema = JsonSchemaGenerator.generateFromClass(reactiveTool.getInputType());

        return ToolDefinition.builder()
                .name(reactiveTool.getName())
                .description(reactiveTool.getDescription())
                .inputSchema(schema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        throw new UnsupportedOperationException("Please use ReactiveToolRegistry for reactive execution");
    }
}
