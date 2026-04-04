package org.example.kbsystemproject.base.ai.agent;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.base.ai.agent.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class BaseAgent {

    protected abstract int getMaxSteps();

    public Flux<AgentEvent> run(String userPrompt, Map<String, Object> businessContext) {
        AgentContext initialContext = new AgentContext(
                List.of(new UserMessage(userPrompt)),
                0,
                businessContext // 显式存入
        );

        return executeStep(initialContext)
                .expand(signal -> {
                    if (signal instanceof AgentSignal.Next(AgentContext nextContext) && nextContext != null) {
                        if (nextContext.currentStep() >= getMaxSteps()) {
                            return Flux.empty();
                        }
                        return executeStep(nextContext);
                    }
                    return Flux.empty();
                })
                .ofType(AgentSignal.Event.class)
                .map(AgentSignal.Event::event);
    }

    public Flux<AgentEvent> run(String userPrompt) {
        return run(userPrompt, Collections.emptyMap());
    }

    /**
     * 执行单步：由子类决定如何执行这一步
     */
    protected abstract Flux<AgentSignal> executeStep(AgentContext context);

     /**
     * 处理思考结果：由子类决定如何根据 AssistantMessage 做出决策
     */

}
