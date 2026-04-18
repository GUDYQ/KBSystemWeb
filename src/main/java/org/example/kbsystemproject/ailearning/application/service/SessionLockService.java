package org.example.kbsystemproject.ailearning.application.service;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.config.MemoryProperties;
import org.redisson.api.RLockReactive;
import org.redisson.api.RedissonReactiveClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class SessionLockService {

    private static final String LOCK_KEY_PREFIX = "learning:session:lock:";

    private final RedissonReactiveClient redissonReactiveClient;
    private final MemoryProperties memoryProperties;

    public SessionLockService(RedissonReactiveClient redissonReactiveClient,
                              MemoryProperties memoryProperties) {
        this.redissonReactiveClient = redissonReactiveClient;
        this.memoryProperties = memoryProperties;
    }

    public <T> Mono<T> execute(String conversationId, Supplier<Mono<T>> action) {
        if (!memoryProperties.getConcurrency().isEnabled()) {
            return action.get();
        }

        RLockReactive lock = redissonReactiveClient.getLock(lockKey(conversationId));
        return Mono.usingWhen(
                acquire(lock, conversationId),
                ignored -> action.get(),
                ignored -> release(lock),
                (ignored, error) -> release(lock),
                ignored -> release(lock)
        ).onErrorResume(error -> {
            log.warn("Session distributed lock unavailable, falling back to DB-only ordering. conversationId={}", conversationId, error);
            return action.get();
        });
    }

    private Mono<RLockReactive> acquire(RLockReactive lock, String conversationId) {
        MemoryProperties.Concurrency concurrency = memoryProperties.getConcurrency();
        return lock.tryLock(concurrency.getLockWaitMillis(), concurrency.getLockLeaseMillis(), TimeUnit.MILLISECONDS)
                .flatMap(acquired -> acquired
                        ? Mono.just(lock)
                        : Mono.error(new IllegalStateException("Failed to acquire session lock: " + conversationId)));
    }

    private Mono<Void> release(RLockReactive lock) {
        return lock.unlock()
                .onErrorResume(IllegalMonitorStateException.class, error -> Mono.empty())
                .then();
    }

    private String lockKey(String conversationId) {
        return LOCK_KEY_PREFIX + conversationId;
    }
}
