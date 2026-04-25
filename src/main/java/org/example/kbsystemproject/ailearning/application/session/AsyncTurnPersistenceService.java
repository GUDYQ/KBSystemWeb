package org.example.kbsystemproject.ailearning.application.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class AsyncTurnPersistenceService {

    private static final String TURN_PERSIST_QUEUE_KEY = "ai-learning:turn-persist:queue";

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final SessionStorageService sessionStorageService;
    private final SessionRequestService sessionRequestService;
    private final MemoryProperties memoryProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AsyncTurnPersistenceService(ReactiveRedisTemplate<String, String> redisTemplate,
                                       ObjectMapper objectMapper,
                                       SessionStorageService sessionStorageService,
                                       SessionRequestService sessionRequestService,
                                       MemoryProperties memoryProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.sessionStorageService = sessionStorageService;
        this.sessionRequestService = sessionRequestService;
        this.memoryProperties = memoryProperties;
    }

    public Mono<Void> enqueue(String conversationId,
                              String requestId,
                              SessionTurnPair turnPair,
                              String currentTopic,
                              Map<String, Object> sessionMetadata) {
        QueuedTurnPersistence command = new QueuedTurnPersistence(
                conversationId,
                requestId,
                currentTopic,
                turnPair,
                sessionMetadata == null ? Map.of() : new LinkedHashMap<>(sessionMetadata)
        );
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(command))
                .flatMap(payload -> redisTemplate.opsForList().rightPush(TURN_PERSIST_QUEUE_KEY, payload))
                .then();
    }

    @Scheduled(fixedDelayString = "${memory.async.fixed-delay-millis:3000}")
    public void schedule() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        runOnce()
                .doFinally(signalType -> running.set(false))
                .subscribe(
                        ignored -> {
                        },
                        error -> log.warn("Async turn persistence loop failed", error)
                );
    }

    public Mono<Void> runOnce() {
        int batchSize = Math.max(1, memoryProperties.getAsync().getBatchSize());
        return Flux.range(0, batchSize)
                .concatMap(ignored -> redisTemplate.opsForList().leftPop(TURN_PERSIST_QUEUE_KEY))
                .concatMap(this::persistQueuedTurn)
                .then();
    }

    private Mono<Void> persistQueuedTurn(String rawPayload) {
        return Mono.fromCallable(() -> objectMapper.readValue(rawPayload, QueuedTurnPersistence.class))
                .flatMap(command -> sessionStorageService.appendTurn(
                                command.conversationId(),
                                command.requestId(),
                                command.turnPair(),
                                command.currentTopic(),
                                command.sessionMetadata()
                        )
                        .onErrorResume(error -> sessionRequestService.markFailed(
                                        command.conversationId(),
                                        command.requestId(),
                                        error
                                )
                                .then(Mono.error(error))))
                .onErrorResume(JsonProcessingException.class, error -> {
                    log.warn("Skipped invalid queued turn persistence payload", error);
                    return Mono.empty();
                })
                .onErrorResume(error -> {
                    log.warn("Async turn persistence failed", error);
                    return Mono.empty();
                });
    }

    private record QueuedTurnPersistence(String conversationId,
                                         String requestId,
                                         String currentTopic,
                                         SessionTurnPair turnPair,
                                         Map<String, Object> sessionMetadata) {
    }
}
