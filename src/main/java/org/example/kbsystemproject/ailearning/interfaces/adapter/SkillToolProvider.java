package org.example.kbsystemproject.ailearning.interfaces.adapter;

import org.springframework.ai.tool.ToolCallbackProvider;

public interface SkillToolProvider extends ToolCallbackProvider {

    String getSkillName();
}
