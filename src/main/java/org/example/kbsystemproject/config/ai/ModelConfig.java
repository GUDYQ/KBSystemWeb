package org.example.kbsystemproject.config.ai;

import org.example.kbsystemproject.base.ai.agent.ReActAgent;
import org.example.kbsystemproject.base.ai.agent.tool.ReactiveTool;
import org.example.kbsystemproject.service.component.FinishTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ModelConfig {
    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(Boolean.FALSE)
                .build();
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptions)
                .build();
    }

    @Bean
    public ReActAgent reActAgent(ChatClient chatClient) {
        return new ReActAgent(chatClient, List.of(), 2);
    }
}
