package org.example.kbsystemproject.ailearning.application.session;

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

        return executeStrict(conversationId, action)
                .onErrorResume(error -> {
                    log.warn("Session distributed lock unavailable, falling back to DB-only ordering. conversationId={}", conversationId, error);
                    return action.get();
                });
    }

    // 严格执行会话级互斥；适合摘要压缩这类允许跳过、但不允许并发重入的后台任务。
    public <T> Mono<T> executeStrict(String conversationId, Supplier<Mono<T>> action) {
        if (!memoryProperties.getConcurrency().isEnabled()) {
            return action.get();
        }

        MemoryProperties.Concurrency concurrency = memoryProperties.getConcurrency();
        RLockReactive lock = redissonReactiveClient.getLock(LOCK_KEY_PREFIX + conversationId);
        return Mono.usingWhen(
                lock.tryLock(concurrency.getLockWaitMillis(), concurrency.getLockLeaseMillis(), TimeUnit.MILLISECONDS)
                        .flatMap(acquired -> acquired
                                ? Mono.just(lock)
                                : Mono.error(new IllegalStateException("Failed to acquire session lock: " + conversationId))),
                ignored -> action.get(),
                ignored -> lock.unlock().onErrorResume(IllegalMonitorStateException.class, error -> Mono.empty()).then(),
                (ignored, error) -> lock.unlock().onErrorResume(IllegalMonitorStateException.class, releaseError -> Mono.empty()).then(),
                ignored -> lock.unlock().onErrorResume(IllegalMonitorStateException.class, error -> Mono.empty()).then()
        );
    }
}

