package org.example.kbsystemproject.config.ai;

import org.example.kbsystemproject.ailearning.agent.ReActAgent;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveToolAdapter;
import org.example.kbsystemproject.ailearning.tool.FinishTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.function.Function;

@Configuration
public class ModelConfig {

    private final FinishTool finishTool;

    public ModelConfig(FinishTool finishTool) {
        this.finishTool = finishTool;
    }

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
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(Boolean.FALSE)
                .build();
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptions)
                .build();
    }

    @Bean
    public ChatClient agentChatClient(ChatModel chatModel) {
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(Boolean.FALSE)
                .build();
        ToolCallback finishToolCallback = new ReactiveToolAdapter(finishTool);
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptions)
                .defaultSystem("""
                        你是计算机学习助手的实验性 Agent。
                        主要处理编程、算法、数据结构、操作系统、计算机网络、数据库等学习任务。
                        只有在确实需要结束任务时才调用 FinishTaskTool。
                        如果已经可以直接回答，就直接给出答案，不要额外调用非必要工具。
                        """)
                .defaultToolCallbacks(List.of(finishToolCallback))
                .build();
    }

    @Bean
    public ReActAgent reActAgent(@Qualifier("agentChatClient") ChatClient chatClient,
                                 @Qualifier("agentScheduler") Scheduler agentScheduler) {
        return new ReActAgent(chatClient,
                List.of(finishTool),
                3,
                agentScheduler);
    }
}
