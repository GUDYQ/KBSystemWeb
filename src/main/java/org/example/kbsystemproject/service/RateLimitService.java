package org.example.kbsystemproject.service;

import org.example.kbsystemproject.utils.RedisUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class RateLimitService {

    @Autowired
    @Qualifier("reactiveRedisTemplate")
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    public Mono<Boolean> preDeductTokens(String userId, int estimatedTokens) {
        String key = "token_limit:" + userId;
        // 使用 Lua 脚本保证原子性：判断是否足够，足够则扣减
        String script = "local curr = tonumber(redis.call('get', KEYS[1]) or '0') " +
                "if curr >= tonumber(ARGV[1]) then " +
                "    redis.call('decrby', KEYS[1], ARGV[1]) " +
                "    return 1 " +
                "else " +
                "    return 0 " +
                "end";
//                "local curr = tonumber(redis.call('get', KEYS[1]) or '0') " +
//                        "if curr >= tonumber(ARGV[1]) then " +
//                        "    redis.call('decrby', KEYS[1], ARGV[1]) " +
//                        "    return 1 " +
//                        "else " +
//                        "    return 0 " +
//                        "end";

        return reactiveRedisTemplate.execute(RedisScript.of(script, Boolean.class), List.of(key), String.valueOf(estimatedTokens))
                .next()
                .defaultIfEmpty(false);
    }

    public Mono<Void> refundTokens(String userId, int tokensToRefund) {
        if (tokensToRefund <= 0) return Mono.empty();
        String key = "token_limit:" + userId;
        return reactiveRedisTemplate.opsForValue().increment(key, tokensToRefund).then();
    }
}
