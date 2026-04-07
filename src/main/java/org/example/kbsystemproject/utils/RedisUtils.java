package org.example.kbsystemproject.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RedisUtils {

    public static class RedisKeyGenerator {
        public static String userRefreshToken(String userId) {
            return String.join("user:refresh-token:", userId);
        }
    }

    @Qualifier("reactiveRedisTemplate")
    @Autowired
    private ReactiveRedisTemplate<String, String> operations;

    public Mono<String> get(String key) {
        return operations.opsForValue().get(key);
    }

    public Mono<Boolean> set(String key, String value) {
        return operations.opsForValue().set(key, value);
    }

    public Mono<Boolean> setExpire(String key, String value, long expire) {
        return operations.opsForValue().set(key, value, Duration.ofMinutes(expire));
    }

    public Mono<Boolean> addZSet(String key, String value, double score) {
        return operations.opsForZSet().add(key, value, score);
    }
}