package org.example.kbsystemproject.ailearning.infrastructure.ai.orchestration;

import org.example.kbsystemproject.ailearning.agent.ReActAgent;
import org.example.kbsystemproject.ailearning.domain.AgentEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Service
public class LearningChatOrchestrator {

    private final ChatClient chatClient;
    private final ReActAgent reActAgent;

    public LearningChatOrchestrator(@Qualifier("chatClient") ChatClient chatClient,
                                    ReActAgent reActAgent) {
        this.chatClient = chatClient;
        this.reActAgent = reActAgent;
    }

    public Flux<String> streamDirect(List<Message> messages) {
        return chatClient.prompt()
                .messages(messages)
                .stream()
                .content();
    }

    public Flux<AgentEvent> streamAgent(List<Message> history,
                                        String userPrompt,
                                        Map<String, Object> businessContext) {
        return reActAgent.run(history, userPrompt, businessContext);
    }
}
