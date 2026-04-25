package org.example.kbsystemproject.config.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

final class ExecutorMetricsSupport {

    private ExecutorMetricsSupport() {
    }

    static ThreadPoolExecutor monitorExecutor(MeterRegistry meterRegistry,
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
