package org.example.kbsystemproject.ToolTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.function.Function;

@SpringBootTest
@org.springframework.context.annotation.Import(AutoToolChatClientTest.ToolConfig.class)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
public class AutoToolChatClientTest {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.openai-sdk.api-key",
                () -> System.getenv("OPENAI_API_KEY"));
    }

    @Autowired(required = false)
    private ChatModel chatModel;

    @Test
    void testInject() {
        assert chatModel != null;
        System.out.println(chatModel.call("hello"));
    }

    // 自定义一个简单的阻塞式函数工具
    public static class CalculatorTool implements Function<CalculatorTool.Request, CalculatorTool.Response> {
        public record Request(int a, int b) {}
        public record Response(int result) {}

        @Override
        public Response apply(Request request) {
            System.out.println(">>> 正在调用阻塞式工具 CalculatorTool: a=" + request.a + ", b=" + request.b);
            return new Response(request.a + request.b);
        }
    }

    @Test
    public void testAutoToolExecution() {
        // 创建一个支持内部工具执行（自动调用）的 ChatClient 实例
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(Boolean.TRUE)
                .build();


        ChatClient autoChatClient = ChatClient.builder(chatModel)
                .defaultOptions(chatOptions)
                .defaultSystem("你是一个有用的 AI 助手，请尽量使用提供的工具来获取信息并回答用户的问题。在计算时请优先调用你提供的计算器工具。")
                .defaultToolCallbacks(FunctionToolCallback.builder("calculatorTool", new CalculatorTool())
                                .description("计算两个整数的加法")
                                .inputType(CalculatorTool.Request.class)
                                .build())
                .build();

        System.out.println("开始提问：123 加 456 等于多少？");
        autoChatClient.prompt()
                .user("请计算一下：123 加 456 等于多少？")
                        .stream().content()
                .subscribe(System.out::println);
    }

    @Configuration
    static class ToolConfig {
        @Bean
        public ToolCallback calculatorTool() {
            return FunctionToolCallback.builder("calculatorTool", new CalculatorTool())
                    .description("计算两个整数的加法")
                    .inputType(CalculatorTool.Request.class)
                    .build();
        }
    }
}
