package org.example.kbsystemproject.ailearning.interfaces.adapter;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public final class SkillToolCallback implements ToolCallback {

    private final String skillName;
    private final ToolCallback delegate;

    public SkillToolCallback(String skillName, ToolCallback delegate) {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("skillName must not be blank");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.skillName = skillName;
        this.delegate = delegate;
    }

    public String getSkillName() {
        return skillName;
    }

    public ToolCallback getDelegate() {
        return delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
