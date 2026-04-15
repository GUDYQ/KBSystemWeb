package org.example.kbsystemproject.service.component;

import org.example.kbsystemproject.base.ai.agent.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.BiFunction;
import java.util.function.Function;

public class ReActAgent extends AbstractChatAgent {

    private final ChatClient chatClient;
    private final int maxSteps;
    private final Function<String, Decision> decisionRouter;
    private final BiFunction<String, String, Mono<String>> toolExecutor;
    private final String systemPrompt;

    public ReActAgent(ChatClient chatClient,
                      int maxSteps,
                      String systemPrompt,
                      Function<String, Decision> decisionRouter,
                      BiFunction<String, String, Mono<String>> toolExecutor) {
        this.chatClient = chatClient;
        this.maxSteps = maxSteps;
        this.systemPrompt = systemPrompt;
        this.decisionRouter = decisionRouter;
        this.toolExecutor = toolExecutor;
    }

    protected ChatClient getChatClient() {
        return chatClient;
    }

    @Override
    protected Flux<AgentSignal> handleThinkingResult(AgentContext context, AssistantMessage message) {
        return null;
    }

    @Override
    protected int getMaxSteps() {
        return maxSteps;
    }

    protected String systemPrompt() {
        return systemPrompt;
    }

    protected Flux<AgentSignal> executeToolCall(String toolName, Object args, AgentContext context) {
        return null;
    }

    protected Decision analyzeStepOutput(AssistantMessage message) {
        return null;
    }
}

