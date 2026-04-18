package org.example.kbsystemproject.ailearning.application.service;

import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionStreamService {

    private final Map<String, Sinks.Many<String>> sessionSinks = new ConcurrentHashMap<>();
    private final Map<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    public Flux<String> stream(String conversationId) {
        return Flux.defer(() -> {
            Sinks.Many<String> sink = sessionSinks.computeIfAbsent(conversationId, ignored -> Sinks.many().replay().limit(200));
            return sink.asFlux()
                    .doFinally(signalType -> cleanupOrRetain(conversationId, sink, signalType));
        });
    }

    public void start(String conversationId, Flux<String> stream) {
        stop(conversationId);
        Sinks.Many<String> sink = sessionSinks.computeIfAbsent(conversationId, ignored -> Sinks.many().replay().limit(200));
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

    public void stop(String conversationId) {
        Disposable disposable = activeStreams.remove(conversationId);
        if (disposable != null) {
            disposable.dispose();
        }
        Sinks.Many<String> sink = sessionSinks.remove(conversationId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    private void complete(String conversationId, Sinks.Many<String> sink) {
        activeStreams.remove(conversationId);
        sink.tryEmitComplete();
        sessionSinks.remove(conversationId, sink);
    }

    private void cleanupOrRetain(String conversationId, Sinks.Many<String> sink, SignalType signalType) {
        if (signalType != SignalType.CANCEL) {
            sessionSinks.remove(conversationId, sink);
            activeStreams.remove(conversationId);
            return;
        }
        if (sink.currentSubscriberCount() == 0 && !activeStreams.containsKey(conversationId)) {
            sessionSinks.remove(conversationId, sink);
        }
    }
}
