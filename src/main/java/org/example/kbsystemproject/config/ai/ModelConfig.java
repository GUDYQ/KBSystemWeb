package org.example.kbsystemproject.config.ai;

import org.example.kbsystemproject.base.ai.agent.ReActAgent;
import org.example.kbsystemproject.base.ai.agent.tool.ReactiveTool;
import org.example.kbsystemproject.base.ai.agent.tool.ReactiveToolAdapter;
import org.example.kbsystemproject.base.ai.agent.tool.impl.WeatherReactiveTool;
import org.example.kbsystemproject.base.ai.agent.tool.impl.WebSearchReactiveTool;
import org.example.kbsystemproject.service.component.FinishTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

@Configuration
public class ModelConfig {

    @Autowired
    private WeatherReactiveTool  weatherReactiveTool;

    @Autowired
    private WebSearchReactiveTool webSearchReactiveTool;

    @Autowired
    private FinishTool finishTool;

    @Autowired(required = false)
    private SyncMcpToolCallbackProvider mcpToolCallbackProvider;

    public static class CalculatorTool implements Function<CalculatorTool.Request, CalculatorTool.Response> {
        public record Request(int a, int b) {}
        public record Response(int result) {}

        @Override
        public Response apply(Request request) {
            System.out.println(">>> 正在调用阻塞式工具 CalculatorTool: a=" + request.a + ", b=" + request.b);
            try {
                Thread.sleep(20000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return new Response(request.a + request.b);
        }
    }

    @Bean
    @Primary
    public ChatClient chatClient(ChatModel chatModel) {
//        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
//                .internalToolExecutionEnabled(Boolean.TRUE)
//                .build();

//
//        return ChatClient.builder(chatModel)
//                .defaultOptions(chatOptions)
//                .defaultSystem("你是一个有用的 AI 助手，请尽量使用提供的工具来获取信息并回答用户的问题。在计算时请优先调用你提供的计算器工具。")
//                .defaultToolCallbacks(FunctionToolCallback.builder("calculatorTool", new CalculatorTool())
//                        .description("计算两个整数的加法")
//                        .inputType(CalculatorTool.Request.class)
//                        .build())
//                .build();
        List<ToolCallback> toolDefines = new ArrayList<>(List.of(
                new ReactiveToolAdapter(weatherReactiveTool),
                new ReactiveToolAdapter(webSearchReactiveTool),
                new ReactiveToolAdapter(finishTool)
        ));
        if(mcpToolCallbackProvider != null) {
            Collections.addAll(toolDefines, mcpToolCallbackProvider.getToolCallbacks());
        }
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(Boolean.FALSE)
                .build();
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptions)
                .defaultSystem("你是一个专业的 AI 助手。你可以使用提供的工具来获取信息，以回答用户的问题。\n" +
                        "【极其重要】：分析用户的问题， 回答时必须使用工具， 如果你认为信息不足或者回答完成，请调用结束工具。\n" +
                        "【极其重要】：如果你已经知道答案，或者通过工具已经获得了足够的信息，请直接输出最终答案。**绝对不允许在已经有答案的情况下继续调用工具！**\n")
                .defaultToolCallbacks(toolDefines)
                .build();
    }

    @Bean
    public ReActAgent reActAgent(ChatClient chatClient) {
//        List<org.springframework.ai.tool.ToolCallback> mcpTools =
//            toolCallbackProvider != null ? List.of(toolCallbackProvider.getToolCallbacks()) : null;

        return new ReActAgent(chatClient,
            List.of(weatherReactiveTool, finishTool),
               List.of(mcpToolCallbackProvider.getToolCallbacks()),
//            mcpTools,
            3);
    }
}
