package org.example.kbsystemproject.base.ai;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.example.kbsystemproject.base.ai.agent.AgentEvent;
import org.example.kbsystemproject.base.ai.agent.BaseAgent;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

public class ResilientAgentWrapper {

    private final CircuitBreaker circuitBreaker;
    // 整体 ReAct 任务的最大允许执行时间
    private static final Duration TASK_GLOBAL_TIMEOUT = Duration.ofSeconds(60);

    public ResilientAgentWrapper(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 包装 BaseAgent 的执行流
     */
    public Flux<AgentEvent> execute(BaseAgent agent, String userPrompt, Map<String, Object> context) {
        return Flux.defer(() -> agent.run(userPrompt, context))
                // 1. 全局防卡死超时：无论是 expand 卡住，还是底层流不吐数据，60秒强制断开
                .timeout(TASK_GLOBAL_TIMEOUT)
                // 2. 熔断装配：使用 transformDeferred 保证并发下的线程安全与状态准确
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                // 3. 保底策略：将所有的异常转化为用户友好的 AgentEvent，防止前端断开连接
                .onErrorResume(io.github.resilience4j.circuitbreaker.CallNotPermittedException.class, e -> {
                    // 熔断器打开时的保底
                    return fallbackFlux("系统当前请求过多，智能体已触发熔断保护，请稍后重试。");
                })
                .onErrorResume(TimeoutException.class, e -> {
                    // 全局超时时的保底
                    return fallbackFlux("智能体思考时间过长（超过60秒），任务已被强制中断。");
                })
                .onErrorResume(Exception.class, e -> {
                    // 其他未知异常（如 JSON 解析错误、底层网络异常等）的保底
                    return fallbackFlux("智能体执行过程中发生内部错误: " + e.getMessage());
                });
    }

    /**
     * 统一的降级响应流
     * 注意：降级也必须返回 Flux<AgentEvent>，保持接口契约一致性
     */
    private Flux<AgentEvent> fallbackFlux(String message) {
        return Flux.just(AgentEvent.error(message));
    }
}