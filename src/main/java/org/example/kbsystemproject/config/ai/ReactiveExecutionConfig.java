package org.example.kbsystemproject.config.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration
public class ReactiveExecutionConfig {

    @Bean(name = "aiBlockingScheduler", destroyMethod = "dispose")
    public Scheduler aiBlockingScheduler() {
        return Schedulers.newBoundedElastic(16, 10000, "ai-blocking");
    }
}
