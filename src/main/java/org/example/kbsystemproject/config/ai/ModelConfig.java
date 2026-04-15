package org.example.kbsystemproject.config.ai;

import org.example.kbsystemproject.base.ai.agent.ReActAgent;
import org.example.kbsystemproject.base.ai.agent.tool.ReactiveTool;
import org.example.kbsystemproject.base.ai.agent.tool.ReactiveToolAdapter;
import org.example.kbsystemproject.base.ai.agent.tool.impl.WeatherReactiveTool;
import org.example.kbsystemproject.base.ai.agent.tool.impl.WebSearchReactiveTool;
import org.example.kbsystemproject.service.component.FinishTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

@Configuration
public class ModelConfig {

    @Autowired
    private WeatherReactiveTool  weatherReactiveTool;

    @Autowired
    private WebSearchReactiveTool webSearchReactiveTool;

    @Autowired
    private FinishTool finishTool;

    @Bean
    @Primary
    public ChatClient chatClient(ChatModel chatModel) {
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(Boolean.FALSE)
                .build();
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptions)
                .defaultSystem("你是一个专业的 AI 助手。你可以使用提供的工具来获取信息，以回答用户的问题。\n" +
                        "【极其重要】：分析用户的问题， 回答时必须使用工具， 如果你认为信息不足或者回答完成，请调用结束工具。\n" +
                        "【极其重要】：如果你已经知道答案，或者通过工具已经获得了足够的信息，请直接输出最终答案。**绝对不允许在已经有答案的情况下继续调用工具！**\n")
                .defaultToolCallbacks(
                        new ReactiveToolAdapter(finishTool),
                        new ReactiveToolAdapter(weatherReactiveTool),
                        new ReactiveToolAdapter(webSearchReactiveTool)
                )
                .build();
    }

    @Bean
    public ReActAgent reActAgent(ChatClient chatClient) {
        return new ReActAgent(chatClient, List.of(weatherReactiveTool, finishTool), 3);
    }
}

