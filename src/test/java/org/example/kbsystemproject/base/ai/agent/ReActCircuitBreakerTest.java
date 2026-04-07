package org.example.kbsystemproject.base.ai.agent;

import org.example.kbsystemproject.base.ai.ResilientAgentWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

class ReActCircuitBreakerPureTest {

    private ConcreteReActAgent agent;
    private ResilientAgentWrapper wrapper;
    private FakeChatClient fakeClient;

    @BeforeEach
    void setUp() {
        // 默认给一个正常返回的 FakeClient
        fakeClient = new FakeChatClient(Flux.just(
                new FakeChatClient.FakeChatResponse("任务完成")
        ));

        agent = new ConcreteReActAgent(fakeClient);

        // 手动构建一个测试用 Wrapper：全局超时 2秒，熔断阈值 3秒
        var breaker = io.github.resilience4j.circuitbreaker.CircuitBreaker.of("test",
                io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .slidingWindowSize(5).minimumNumberOfCalls(2).failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(1)).build());

        wrapper = new ResilientAgentWrapper(breaker);
    }

    @Test
    void test_NormalReActFlow_ShouldCompleteSuccessfully() {
        Flux<AgentEvent> result = wrapper.execute(agent, "正常提问", Map.of());

        StepVerifier.create(result)
                .expectNextMatches(event ->  AgentState.TOKEN.equals(event.state()))
                .verifyComplete();
    }

    @Test
    void test_StreamHangs_ShouldTriggerTimeoutAndFallback() {
        // 1. 动态替换为"永远卡死"的流
        fakeClient = new FakeChatClient(Flux.never());
        agent = new ConcreteReActAgent(fakeClient);

        // 2. 测试卡死场景 (利用虚拟时间，不用真等2秒)
        StepVerifier.withVirtualTime(() -> wrapper.execute(agent, "导致卡死的问题", Map.of()))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(2)) // 虚拟时间推进 2 秒，触发 timeout
                // 3. 验证保底策略是否生效
                .expectNextMatches(event ->
                        AgentState.ERROR.equals(event.state()))
                .verifyComplete();
    }

    @Test
    void test_MidStreamApiError_ShouldCatchAndFallback() {
        // 1. 动态替换为"中途报错"的流 (模拟大模型 429 限流)
        fakeClient = new FakeChatClient(
                Flux.just(new FakeChatClient.FakeChatResponse("思考"))
                        .concatWith(Flux.error(new RuntimeException("429 Too Many Requests")))
        );
        agent = new ConcreteReActAgent(fakeClient);

        // 2. 测试报错场景
        StepVerifier.create(wrapper.execute(agent, "导致报错的问题", Map.of()))
                // 3. 验证能否收到报错前的数据，并且最终触发保底策略不断开连接
                .expectNextMatches(event -> AgentState.TOKEN.equals(event.state()))
                .expectNextMatches(event ->
                        AgentState.ERROR.equals(event.state()))
                .verifyComplete();
    }

    @Test
    void test_ReActMaxSteps_ShouldStopGracefully() {
        // 1. 模拟一个永远输出 "继续思考" 导致不断触发 expand 的流
        fakeClient = new FakeChatClient(Flux.just(
                new FakeChatClient.FakeChatResponse("继续思考")
        ));
        agent = new ConcreteReActAgent(fakeClient);

        // 2. 测试 BaseAgent 的 maxSteps 限制
        StepVerifier.create(wrapper.execute(agent, "无限循环问题", Map.of()))
                // 因为 getMaxSteps() = 3，所以应该最多收到 3 次 "继续思考"
                .expectNextCount(3)
                .verifyComplete();
    }
}
