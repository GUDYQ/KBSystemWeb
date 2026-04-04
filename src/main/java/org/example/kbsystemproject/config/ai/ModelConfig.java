package org.example.kbsystemproject.config.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ModelConfig {
    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel) {
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(Boolean.FALSE)
                .temperature(0.7)
                .maxTokens(2048)
                .build();
        return ChatClient.builder(chatModel).build();
    }
}
