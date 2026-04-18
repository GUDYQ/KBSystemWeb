package org.example.kbsystemproject.config.ai;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.example.kbsystemproject.ailearning.agent.ResilientAgentWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilientAgentConfig {

    @Bean
    public CircuitBreaker reactAgentCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20) // 评估最近 20 次完整任务
                .minimumNumberOfCalls(5) // 至少 5 次才开始计算
                .failureRateThreshold(50) // 50% 失败率打开熔断
                // 【关键】慢调用阈值 65s，必须大于上面的 TASK_GLOBAL_TIMEOUT (60s)
                // 目的：让 Timeout 兜底异常走 failure 逻辑，而不是走 slowCall 逻辑，避免双重惩罚
                .slowCallDurationThreshold(Duration.ofSeconds(65))
                .slowCallRateThreshold(100) // 100% 都慢才判定为慢（实际上基本不会触发了，被 timeout 拦截）
                .waitDurationInOpenState(Duration.ofSeconds(30)) // 熔断恢复等待 30 秒
                .permittedNumberOfCallsInHalfOpenState(2) // 半开状态放行 2 个请求探测
                .build();

        return CircuitBreaker.of("reactAgentTaskBreaker", config);
    }

    @Bean
    public ResilientAgentWrapper resilientAgentWrapper(CircuitBreaker reactAgentCircuitBreaker) {
        return new ResilientAgentWrapper(reactAgentCircuitBreaker);
    }
}