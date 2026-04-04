package org.example.kbsystemproject.service.component;

import org.example.kbsystemproject.base.ai.agent.tool.ReactiveTool;
import org.springframework.ai.chat.model.ToolContext;
import reactor.core.publisher.Mono;

public class FinishTool implements ReactiveTool {

    public static final String NAME = "FinishTaskTool";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Finish current task.";
    }

    @Override
    public Class<?> getInputType() {
        return String.class;
    }

    @Override
    public Mono<String> execute(String input, ToolContext context) {
        if (input == null || input.isBlank()) {
            return Mono.just("Task finished.");
        }
        return Mono.just(input);
    }
}
