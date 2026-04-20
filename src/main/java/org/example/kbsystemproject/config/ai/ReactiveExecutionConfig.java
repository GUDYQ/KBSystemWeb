package org.example.kbsystemproject.config.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.core.publisher.Hooks;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class ReactiveExecutionConfig {

    static {
        Hooks.enableAutomaticContextPropagation();
    }

    @Bean(name = "aiBlockingExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor aiBlockingExecutor(MeterRegistry meterRegistry) {
        return monitorExecutor(
                meterRegistry,
                "ai.blocking.executor",
                createExecutor(16, 16, 10_000, "ai-blocking"),
                "ai-blocking"
        );
    }

    @Bean(name = "retrievalBlockingExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor retrievalBlockingExecutor(MeterRegistry meterRegistry) {
        return monitorExecutor(
                meterRegistry,
                "ai.retrieval.executor",
                createExecutor(16, 32, 10_000, "retrieval-blocking"),
                "retrieval-blocking"
        );
    }

    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ThreadPoolExecutor agentExecutor(MeterRegistry meterRegistry) {
        return monitorExecutor(
                meterRegistry,
                "ai.agent.executor",
                createExecutor(32, 100, 10_000, "ai-agent-pool"),
                "ai-agent-pool"
        );
    }

    @Bean(name = "aiBlockingScheduler", destroyMethod = "dispose")
    public Scheduler aiBlockingScheduler(@Qualifier("aiBlockingExecutor") ThreadPoolExecutor executor) {
        return Schedulers.fromExecutorService(executor);
    }

    @Bean(name = "retrievalBlockingScheduler", destroyMethod = "dispose")
    public Scheduler retrievalBlockingScheduler(@Qualifier("retrievalBlockingExecutor") ThreadPoolExecutor executor) {
        return Schedulers.fromExecutorService(executor);
    }

    @Bean(name = "agentScheduler", destroyMethod = "dispose")
    public Scheduler agentScheduler(@Qualifier("agentExecutor") ThreadPoolExecutor executor) {
        return Schedulers.fromExecutorService(executor);
    }

    private ThreadPoolExecutor createExecutor(int corePoolSize,
                                              int maxPoolSize,
                                              int queueCapacity,
                                              String threadNamePrefix) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName(threadNamePrefix + "-" + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private ThreadPoolExecutor monitorExecutor(MeterRegistry meterRegistry,
                                               String metricName,
                                               ThreadPoolExecutor executor,
                                               String schedulerName) {
        ExecutorServiceMetrics.monitor(
                meterRegistry,
                executor,
                metricName,
                List.of(Tag.of("scheduler", schedulerName))
        );
        return executor;
    }
}
