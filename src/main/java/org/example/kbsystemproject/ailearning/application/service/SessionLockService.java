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

    // 对同一 conversationId 的写操作做串行化，避免并发写乱 turnIndex 顺序。
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

    // 获取分布式锁；获取失败时抛错，由上层决定是否降级。
    private Mono<RLockReactive> acquire(RLockReactive lock, String conversationId) {
        MemoryProperties.Concurrency concurrency = memoryProperties.getConcurrency();
        return lock.tryLock(concurrency.getLockWaitMillis(), concurrency.getLockLeaseMillis(), TimeUnit.MILLISECONDS)
                .flatMap(acquired -> acquired
                        ? Mono.just(lock)
                        : Mono.error(new IllegalStateException("Failed to acquire session lock: " + conversationId)));
    }

    // 释放锁；如果锁已经过期或不归当前线程持有，则静默忽略。
    private Mono<Void> release(RLockReactive lock) {
        return lock.unlock()
                .onErrorResume(IllegalMonitorStateException.class, error -> Mono.empty())
                .then();
    }

    // 生成会话级分布式锁的 Redis key。
    private String lockKey(String conversationId) {
        return LOCK_KEY_PREFIX + conversationId;
    }
}
