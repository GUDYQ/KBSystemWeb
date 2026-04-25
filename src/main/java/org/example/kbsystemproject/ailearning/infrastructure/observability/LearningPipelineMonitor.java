package org.example.kbsystemproject.ailearning.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.application.chat.LearningChatCommand;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Slf4j
@Service
public class LearningPipelineMonitor {

    private final MeterRegistry meterRegistry;

    public LearningPipelineMonitor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public RequestStageMonitor newRequestMonitor() {
        return new RequestStageMonitor();
    }

    public <T> Mono<T> monitorStageMono(String stage,
                                        LearningChatCommand command,
                                        IntentDecision decision,
                                        RequestStageMonitor requestMonitor,
                                        Mono<T> source) {
        return Mono.defer(() -> {
            long startNanos = System.nanoTime();
            return source.doFinally(signalType -> recordPipelineStage(
                    stage,
                    command,
                    decision,
                    requestMonitor,
                    metricOutcome(signalType),
                    System.nanoTime() - startNanos
            ));
        });
    }

    public <T> T monitorSynchronousStage(String stage,
                                         LearningChatCommand command,
                                         IntentDecision decision,
                                         RequestStageMonitor requestMonitor,
                                         Supplier<T> supplier) {
        long startNanos = System.nanoTime();
        try {
            T result = supplier.get();
            recordPipelineStage(stage, command, decision, requestMonitor, "completed", System.nanoTime() - startNanos);
            return result;
        } catch (RuntimeException error) {
            recordPipelineStage(stage, command, decision, requestMonitor, "failed", System.nanoTime() - startNanos);
            throw error;
        }
    }

    public Flux<String> monitorVisibleStreamStage(String stage,
                                                  LearningChatCommand command,
                                                  IntentDecision decision,
                                                  RequestStageMonitor requestMonitor,
                                                  Flux<String> source) {
        return Flux.defer(() -> {
            long startNanos = System.nanoTime();
            AtomicBoolean firstChunkRecorded = new AtomicBoolean(false);
            return source.doOnNext(chunk -> {
                        if (chunk == null || chunk.isBlank() || !firstChunkRecorded.compareAndSet(false, true)) {
                            return;
                        }
                        recordPipelineStage(
                                "response.first_chunk",
                                command,
                                decision,
                                requestMonitor,
                                "completed",
                                System.nanoTime() - startNanos
                        );
                    })
                    .doFinally(signalType -> {
                        String outcome = metricOutcome(signalType);
                        if (!firstChunkRecorded.get()) {
                            recordPipelineStage(
                                    "response.first_chunk",
                                    command,
                                    decision,
                                    requestMonitor,
                                    outcome,
                                    System.nanoTime() - startNanos
                            );
                        }
                        recordPipelineStage(
                                stage,
                                command,
                                decision,
                                requestMonitor,
                                outcome,
                                System.nanoTime() - startNanos
                        );
                    });
        });
    }

    public <T> Flux<T> monitorResponseStream(String stage,
                                             LearningChatCommand command,
                                             IntentDecision decision,
                                             Flux<T> stream) {
        return Flux.defer(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            AtomicInteger chunkCount = new AtomicInteger();

            return stream.doOnNext(ignored -> chunkCount.incrementAndGet())
                    .doFinally(signalType -> {
                        String outcome = metricOutcome(signalType);
                        List<Tag> tags = responseMetricTags(stage, decision, outcome);

                        Counter.builder("ai.learning.response.requests")
                                .description("Learning chat response stream executions")
                                .tags(tags)
                                .register(meterRegistry)
                                .increment();

                        DistributionSummary.builder("ai.learning.response.chunks")
                                .description("Visible chunks emitted by learning chat responses")
                                .tags(tags)
                                .register(meterRegistry)
                                .record(chunkCount.get());

                        sample.stop(Timer.builder("ai.learning.response.duration")
                                .description("Learning chat response stream duration")
                                .tags(tags)
                                .register(meterRegistry));

                        log.info(
                                "Learning response stream finished. conversationId={}, requestId={}, stage={}, outcome={}, chunks={}",
                                command.conversationId(),
                                command.requestId(),
                                stage,
                                outcome,
                                chunkCount.get()
                        );
                    });
        });
    }

    public <T> Flux<T> monitorPipelineExecution(LearningChatCommand command,
                                                RequestStageMonitor requestMonitor,
                                                Flux<T> stream) {
        return Flux.defer(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            AtomicInteger chunkCount = new AtomicInteger();
            return stream.doOnNext(ignored -> chunkCount.incrementAndGet())
                    .doFinally(signalType -> {
                        IntentDecision decision = requestMonitor.decision();
                        String outcome = metricOutcome(signalType);
                        List<Tag> tags = pipelineMetricTags(decision, outcome);

                        Counter.builder("ai.learning.pipeline.requests")
                                .description("Learning chat pipeline executions")
                                .tags(tags)
                                .register(meterRegistry)
                                .increment();

                        DistributionSummary.builder("ai.learning.pipeline.visible.chunks")
                                .description("Visible chunks emitted by the full learning chat pipeline")
                                .tags(tags)
                                .register(meterRegistry)
                                .record(chunkCount.get());

                        sample.stop(Timer.builder("ai.learning.pipeline.total.duration")
                                .description("Learning chat pipeline total duration")
                                .tags(tags)
                                .register(meterRegistry));

                        log.info(
                                "Learning pipeline finished. conversationId={}, requestId={}, outcome={}, chunks={}, stages=[{}]",
                                command.conversationId(),
                                command.requestId(),
                                outcome,
                                chunkCount.get(),
                                requestMonitor.describeStages()
                        );
                    });
        });
    }

    private void recordPipelineStage(String stage,
                                     LearningChatCommand command,
                                     IntentDecision decision,
                                     RequestStageMonitor requestMonitor,
                                     String outcome,
                                     long durationNanos) {
        List<Tag> tags = pipelineStageMetricTags(stage, decision, outcome);

        Counter.builder("ai.learning.pipeline.stage.requests")
                .description("Learning chat pipeline stage executions")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        Timer.builder("ai.learning.pipeline.stage.duration")
                .description("Learning chat pipeline stage duration")
                .tags(tags)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        requestMonitor.record(stage, outcome, durationNanos);

        log.info(
                "Learning pipeline stage finished. conversationId={}, requestId={}, stage={}, outcome={}, durationMs={}",
                command.conversationId(),
                command.requestId(),
                stage,
                outcome,
                Duration.ofNanos(durationNanos).toMillis()
        );
    }

    private List<Tag> responseMetricTags(String stage, IntentDecision decision, String outcome) {
        List<Tag> tags = new ArrayList<>(baseMetricTags(decision));
        tags.add(Tag.of("stage", stage));
        tags.add(Tag.of("outcome", outcome));
        return List.copyOf(tags);
    }

    private List<Tag> pipelineStageMetricTags(String stage, IntentDecision decision, String outcome) {
        List<Tag> tags = new ArrayList<>(baseMetricTags(decision));
        tags.add(Tag.of("stage", stage));
        tags.add(Tag.of("outcome", outcome));
        return List.copyOf(tags);
    }

    private List<Tag> pipelineMetricTags(IntentDecision decision, String outcome) {
        List<Tag> tags = new ArrayList<>(baseMetricTags(decision));
        tags.add(Tag.of("outcome", outcome));
        return List.copyOf(tags);
    }

    private List<Tag> baseMetricTags(IntentDecision decision) {
        if (decision == null) {
            return List.of(
                    Tag.of("executionMode", "pending"),
                    Tag.of("intentType", "pending"),
                    Tag.of("retrievalEnabled", "unknown")
            );
        }
        return List.of(
                Tag.of("executionMode", decision.executionMode().name()),
                Tag.of("intentType", decision.intentType().name()),
                Tag.of("retrievalEnabled", Boolean.toString(decision.needRetrieval()))
        );
    }

    private String metricOutcome(SignalType signalType) {
        return switch (signalType) {
            case ON_COMPLETE -> "completed";
            case CANCEL -> "canceled";
            case ON_ERROR -> "failed";
            default -> "unknown";
        };
    }

    public static final class RequestStageMonitor {
        private final Map<String, StageMeasurement> stages = new LinkedHashMap<>();
        private volatile IntentDecision decision;

        synchronized void record(String stage, String outcome, long durationNanos) {
            stages.put(stage, new StageMeasurement(outcome, durationNanos));
        }

        public void recordDecision(IntentDecision decision) {
            this.decision = decision;
        }

        IntentDecision decision() {
            return decision;
        }

        synchronized String describeStages() {
            if (stages.isEmpty()) {
                return "none";
            }
            StringJoiner joiner = new StringJoiner(", ");
            stages.forEach((stage, measurement) -> joiner.add(
                    stage + "=" + Duration.ofNanos(measurement.durationNanos()).toMillis() + "ms(" + measurement.outcome() + ")"
            ));
            return joiner.toString();
        }
    }

    private record StageMeasurement(String outcome, long durationNanos) {
    }
}

