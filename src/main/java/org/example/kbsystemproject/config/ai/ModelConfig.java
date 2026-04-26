package org.example.kbsystemproject.config.ai;

import org.example.kbsystemproject.ailearning.agent.ReActAgent;
import org.example.kbsystemproject.ailearning.interfaces.adapter.ReactiveToolAdapter;
import org.example.kbsystemproject.ailearning.tool.FinishTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.AsyncMcpToolCallback;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.scheduler.Scheduler;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public ChatClient agentChatClient(ChatModel chatModel,
                                      ObjectProvider<ToolCallbackProvider> toolCallbackProviders) {
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(Boolean.FALSE)
                .build();
        ToolCallback finishToolCallback = new ReactiveToolAdapter(finishTool);
        List<ToolCallback> asyncMcpCallbacks = resolveAsyncMcpToolCallbacks(toolCallbackProviders);
        List<ToolCallback> agentCallbacks = mergeToolCallbacks(finishToolCallback, asyncMcpCallbacks);
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptions)
                .defaultSystem("""
                        你是计算机学习助手的实验性 Agent。
                        主要处理编程、算法、数据结构、操作系统、计算机网络、数据库等学习任务。
                        只有在确实需要结束任务时才调用 FinishTaskTool。
                        如果已经可以直接回答，就直接给出答案，不要额外调用非必要工具。
                        """)
                .defaultToolCallbacks(agentCallbacks)
                .build();
    }

    @Bean
    public ReActAgent reActAgent(@Qualifier("agentChatClient") ChatClient chatClient,
                                 @Qualifier("agentScheduler") Scheduler agentScheduler,
                                 ObjectProvider<ToolCallbackProvider> toolCallbackProviders) {
        List<ToolCallback> asyncMcpCallbacks = resolveAsyncMcpToolCallbacks(toolCallbackProviders);
        return new ReActAgent(chatClient,
                List.of(finishTool),
                asyncMcpCallbacks,
                3,
                agentScheduler);
    }

    private List<ToolCallback> resolveAsyncMcpToolCallbacks(ObjectProvider<ToolCallbackProvider> toolCallbackProviders) {
        return toolCallbackProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .filter(AsyncMcpToolCallback.class::isInstance)
                .toList();
    }

    private List<ToolCallback> mergeToolCallbacks(ToolCallback primaryCallback, List<ToolCallback> extraCallbacks) {
        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        callbacks.put(primaryCallback.getToolDefinition().name(), primaryCallback);
        if (extraCallbacks != null) {
            extraCallbacks.forEach(callback -> callbacks.putIfAbsent(callback.getToolDefinition().name(), callback));
        }
        return List.copyOf(callbacks.values());
    }
}
