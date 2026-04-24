package org.example.kbsystemproject.ailearning.application.service;

import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class SessionStreamService {

    private static final int REPLAY_LIMIT = 200;

    private final Map<String, Sinks.Many<String>> sessionSinks = new ConcurrentHashMap<>();
    private final Map<String, Disposable> activeStreams = new ConcurrentHashMap<>();
    private final Map<String, Disposable> cleanupTasks = new ConcurrentHashMap<>();
    private final Duration completedStreamRetention;

    public SessionStreamService() {
        this(Duration.ofMinutes(5));
    }

    SessionStreamService(Duration completedStreamRetention) {
        this.completedStreamRetention = completedStreamRetention;
    }

    public Flux<String> stream(String conversationId) {
        return Flux.defer(() -> sinkFor(conversationId).asFlux());
    }

    public Flux<String> startAndStream(String conversationId, Flux<String> stream) {
        return Flux.defer(() -> {
            stop(conversationId);
            Sinks.Many<String> sink = sinkFor(conversationId);
            subscribe(conversationId, sink, stream);
            return sink.asFlux();
        });
    }

    public void start(String conversationId, Flux<String> stream) {
        stop(conversationId);
        Sinks.Many<String> sink = sinkFor(conversationId);
        subscribe(conversationId, sink, stream);
    }

    public void stop(String conversationId) {
        cancelCleanup(conversationId);
        Disposable disposable = activeStreams.remove(conversationId);
        if (disposable != null) {
            disposable.dispose();
        }
        Sinks.Many<String> sink = sessionSinks.remove(conversationId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    private void subscribe(String conversationId, Sinks.Many<String> sink, Flux<String> stream) {
        cancelCleanup(conversationId);
        Disposable disposable = stream.subscribe(
                chunk -> sink.tryEmitNext(chunk),
                error -> {
                    sink.tryEmitNext("[ERROR] " + error.getMessage());
                    complete(conversationId, sink);
                },
                () -> complete(conversationId, sink)
        );
        activeStreams.put(conversationId, disposable);
    }

    private void complete(String conversationId, Sinks.Many<String> sink) {
        activeStreams.remove(conversationId);
        sink.tryEmitComplete();
        scheduleCleanup(conversationId, sink);
    }

    private Sinks.Many<String> sinkFor(String conversationId) {
        return sessionSinks.computeIfAbsent(conversationId, ignored -> Sinks.many().replay().limit(REPLAY_LIMIT));
    }

    private void scheduleCleanup(String conversationId, Sinks.Many<String> sink) {
        cancelCleanup(conversationId);
        Disposable cleanup = Schedulers.parallel().schedule(() -> {
            if (!activeStreams.containsKey(conversationId)) {
                sessionSinks.remove(conversationId, sink);
            }
            cleanupTasks.remove(conversationId);
        }, completedStreamRetention.toMillis(), TimeUnit.MILLISECONDS);
        cleanupTasks.put(conversationId, cleanup);
    }

    private void cancelCleanup(String conversationId) {
        Disposable cleanup = cleanupTasks.remove(conversationId);
        if (cleanup != null) {
            cleanup.dispose();
        }
    }
}
