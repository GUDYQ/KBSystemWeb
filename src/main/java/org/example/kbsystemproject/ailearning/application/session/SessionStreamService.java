package org.example.kbsystemproject.ailearning.application.session;

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
        return Flux.defer(() -> sessionSinks
                .computeIfAbsent(conversationId, ignored -> Sinks.many().replay().limit(REPLAY_LIMIT))
                .asFlux());
    }

    public Flux<String> startAndStream(String conversationId, Flux<String> stream) {
        return Flux.defer(() -> {
            stop(conversationId);
            Sinks.Many<String> sink = bindStream(conversationId, stream);
            return sink.asFlux();
        });
    }

    public void start(String conversationId, Flux<String> stream) {
        stop(conversationId);
        bindStream(conversationId, stream);
    }

    public void stop(String conversationId) {
        Disposable cleanup = cleanupTasks.remove(conversationId);
        if (cleanup != null) {
            cleanup.dispose();
        }
        Disposable disposable = activeStreams.remove(conversationId);
        if (disposable != null) {
            disposable.dispose();
        }
        Sinks.Many<String> sink = sessionSinks.remove(conversationId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    private Sinks.Many<String> bindStream(String conversationId, Flux<String> stream) {
        Sinks.Many<String> sink = sessionSinks.computeIfAbsent(conversationId, ignored -> Sinks.many().replay().limit(REPLAY_LIMIT));
        Disposable cleanup = cleanupTasks.remove(conversationId);
        if (cleanup != null) {
            cleanup.dispose();
        }
        activeStreams.put(conversationId, stream.subscribe(
                sink::tryEmitNext,
                error -> {
                    sink.tryEmitNext("[ERROR] " + error.getMessage());
                    activeStreams.remove(conversationId);
                    sink.tryEmitComplete();
                    Disposable delayedCleanup = Schedulers.parallel().schedule(() -> {
                        if (!activeStreams.containsKey(conversationId)) {
                            sessionSinks.remove(conversationId, sink);
                        }
                        cleanupTasks.remove(conversationId);
                    }, completedStreamRetention.toMillis(), TimeUnit.MILLISECONDS);
                    cleanupTasks.put(conversationId, delayedCleanup);
                },
                () -> {
                    activeStreams.remove(conversationId);
                    sink.tryEmitComplete();
                    Disposable delayedCleanup = Schedulers.parallel().schedule(() -> {
                        if (!activeStreams.containsKey(conversationId)) {
                            sessionSinks.remove(conversationId, sink);
                        }
                        cleanupTasks.remove(conversationId);
                    }, completedStreamRetention.toMillis(), TimeUnit.MILLISECONDS);
                    cleanupTasks.put(conversationId, delayedCleanup);
                }
        ));
        return sink;
    }
}

