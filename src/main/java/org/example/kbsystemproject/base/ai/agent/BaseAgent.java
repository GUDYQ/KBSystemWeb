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
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public abstract class BaseAgent {

    // 将刚才我们在 Service 中定义的隔离线程池下沉到 BaseAgent，作为统一的 Agent 运转专有池
    private final Scheduler agentScheduler = Schedulers.newBoundedElastic(100, 10000, "ai-agent-pool");

    // 暴露给外部或者子类使用
    public Scheduler getScheduler() {
        return agentScheduler;
    }

    protected abstract int getMaxSteps();

    public Flux<AgentEvent> run(String userPrompt, Map<String, Object> businessContext) {
        return run(Collections.emptyList(), userPrompt, businessContext);
    }

    public Flux<AgentEvent> run(List<Message> history, String userPrompt, Map<String, Object> businessContext) {
        List<Message> allMessages = new java.util.ArrayList<>(history);
        allMessages.add(new UserMessage(userPrompt));

        AgentContext initialContext = new AgentContext(
                Collections.unmodifiableList(allMessages),
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
                // 规范：整个 Agent 内部运转逻辑强制与外层解耦
                // 统一使用 Agent 专属池，防堵塞全局
                .subscribeOn(this.agentScheduler)
                .ofType(AgentSignal.Event.class)
                .map(AgentSignal.Event::event);
    }

    public Flux<AgentEvent> run(String userPrompt) {
        return run(Collections.emptyList(), userPrompt, Collections.emptyMap());
    }

    public Flux<AgentEvent> run(List<Message> history, String userPrompt) {
        return run(history, userPrompt, Collections.emptyMap());
    }

    /**
     * 执行单步：由子类决定如何执行这一步
     */
    protected abstract Flux<AgentSignal> executeStep(AgentContext context);

     /**
     * 处理思考结果：由子类决定如何根据 AssistantMessage 做出决策
     */

}
