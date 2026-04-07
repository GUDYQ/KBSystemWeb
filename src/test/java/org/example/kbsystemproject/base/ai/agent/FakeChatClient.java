package org.example.kbsystemproject.base.ai.agent;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 纯手工 Fake 类，不实现任何臃肿的接口，只保证你指定的调用链编译通过：
 * chatClient.prompt().messages(messages).stream().chatResponse()
 */
public class FakeChatClient {

    private final Flux<FakeChatResponse> behaviorStream;

    public FakeChatClient(Flux<FakeChatResponse> behaviorStream) {
        this.behaviorStream = behaviorStream;
    }

    public FakePromptSpec prompt() {
        return new FakePromptSpec(behaviorStream);
    }

    public static class FakePromptSpec {
        private final Flux<FakeChatResponse> stream;

        public FakePromptSpec(Flux<FakeChatResponse> stream) {
            this.stream = stream;
        }

        // 匹配你的调用: .messages(messages)
        public FakePromptSpec messages(List<?> messages) {
            return this;
        }

        // 匹配你的调用: .stream()
        public FakeStreamSpec stream() {
            return new FakeStreamSpec(stream);
        }
    }

    public static class FakeStreamSpec {
        private final Flux<FakeChatResponse> stream;

        public FakeStreamSpec(Flux<FakeChatResponse> stream) {
            this.stream = stream;
        }

        // 匹配你的调用: .chatResponse()
        public Flux<FakeChatResponse> chatResponse() {
            return stream;
        }
    }

    // 模拟返回结构
    public record FakeChatResponse(String content) {}
}
