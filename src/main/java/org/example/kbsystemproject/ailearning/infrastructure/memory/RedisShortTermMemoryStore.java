package org.example.kbsystemproject.ailearning.infrastructure.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.example.kbsystemproject.config.MemoryProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisShortTermMemoryStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String MEMORY_KEY_PREFIX = "learning:session:short-term:";
    private static final String TURN_COUNTER_KEY_PREFIX = "learning:session:turns:";

    private static final String PUSH_TURN_SCRIPT = """
            local key = KEYS[1]
            local counterKey = KEYS[2]
            local maxLen = tonumber(ARGV[1])
            local ttlSeconds = tonumber(ARGV[2])
            local totalTurns = ARGV[3]
            local firstMessage = ARGV[4]
            local secondMessage = ARGV[5]

            redis.call('RPUSH', key, firstMessage, secondMessage)

            local currentLen = redis.call('LLEN', key)
            if currentLen > maxLen then
                redis.call('LTRIM', key, currentLen - maxLen, -1)
            end

            redis.call('SET', counterKey, totalTurns)

            if ttlSeconds > 0 then
                redis.call('EXPIRE', key, ttlSeconds)
                redis.call('EXPIRE', counterKey, ttlSeconds)
            end

            return 1
            """;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MemoryProperties memoryProperties;
    private final RedisScript<Long> pushTurnRedisScript = RedisScript.of(PUSH_TURN_SCRIPT, Long.class);

    public RedisShortTermMemoryStore(@Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
                                     ObjectMapper objectMapper,
                                     MemoryProperties memoryProperties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.memoryProperties = memoryProperties;
    }

    public Mono<Void> appendTurnPair(String conversationId, SessionTurnPair turnPair, long totalTurns) {
        String key = memoryKey(conversationId);
        String counterKey = counterKey(conversationId);
        String maxMessages = String.valueOf(maxMessages());
        String ttlSeconds = String.valueOf(ttlSeconds());

        return Mono.zip(
                        Mono.fromCallable(() -> serializeTurn(turnPair.userTurn())),
                        Mono.fromCallable(() -> serializeTurn(turnPair.assistantTurn()))
                )
                .flatMap(tuple -> redisTemplate.execute(
                                pushTurnRedisScript,
                                List.of(key, counterKey),
                                List.of(maxMessages, ttlSeconds, String.valueOf(totalTurns), tuple.getT1(), tuple.getT2())
                        )
                        .next()
                        .then());
    }

    public Mono<List<ConversationTurn>> getRecentTurns(String conversationId) {
        return redisTemplate.opsForList()
                .range(memoryKey(conversationId), 0, -1)
                .concatMap(this::deserializeTurn)
                .collectList();
    }

    public Mono<Long> getTotalTurns(String conversationId) {
        return redisTemplate.opsForValue()
                .get(counterKey(conversationId))
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }

    public Mono<Void> clear(String conversationId) {
        return redisTemplate.delete(memoryKey(conversationId), counterKey(conversationId)).then();
    }

    public Mono<Void> rebuild(String conversationId, List<ConversationTurn> turns, long totalTurns) {
        if (turns == null || turns.isEmpty()) {
            return clear(conversationId);
        }

        List<ConversationTurn> turnsToStore = keepLatest(turns, maxMessages());
        String memoryKey = memoryKey(conversationId);
        String counterKey = counterKey(conversationId);
        Duration ttl = ttlDuration();

        return Mono.fromCallable(() -> turnsToStore.stream()
                        .map(this::serializeTurnUnchecked)
                        .toList())
                .flatMap(serializedTurns -> clear(conversationId)
                        .thenMany(Flux.fromIterable(serializedTurns)
                                .concatMap(serialized -> redisTemplate.opsForList().rightPush(memoryKey, serialized)))
                        .then(redisTemplate.opsForValue().set(counterKey, String.valueOf(totalTurns))))
                .then(applyTtl(memoryKey, counterKey, ttl));
    }

    private Mono<Void> applyTtl(String memoryKey, String counterKey, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            return Mono.empty();
        }
        return redisTemplate.expire(memoryKey, ttl)
                .then(redisTemplate.expire(counterKey, ttl))
                .then();
    }

    private List<ConversationTurn> keepLatest(List<ConversationTurn> turns, int maxMessages) {
        if (turns.size() <= maxMessages) {
            return turns;
        }
        return turns.subList(turns.size() - maxMessages, turns.size());
    }

    private Mono<ConversationTurn> deserializeTurn(String raw) {
        return Mono.fromCallable(() -> {
            Map<String, Object> data = objectMapper.readValue(raw, MAP_TYPE);
            SessionMessageRole role = SessionMessageRole.valueOf(String.valueOf(data.getOrDefault("role", SessionMessageRole.USER.name())));
            String content = String.valueOf(data.getOrDefault("content", ""));
            OffsetDateTime createdAt = OffsetDateTime.parse(String.valueOf(data.get("createdAt")));
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = data.get("metadata") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            return new ConversationTurn(role, content, createdAt, metadata);
        });
    }

    private String serializeTurnUnchecked(ConversationTurn turn) {
        try {
            return serializeTurn(turn);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize turn", e);
        }
    }

    private String serializeTurn(ConversationTurn turn) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("role", turn.role().name());
        data.put("content", turn.content());
        data.put("createdAt", turn.createdAt());
        data.put("metadata", turn.metadata());
        return objectMapper.writeValueAsString(data);
    }

    private int maxMessages() {
        return Math.max(2, memoryProperties.getShortTerm().getMaxTurns() * 2);
    }

    private long ttlSeconds() {
        return ttlDuration().toSeconds();
    }

    private Duration ttlDuration() {
        long ttlHours = memoryProperties.getShortTerm().getTtlHours();
        if (ttlHours <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofHours(ttlHours);
    }

    private String memoryKey(String conversationId) {
        return MEMORY_KEY_PREFIX + conversationId;
    }

    private String counterKey(String conversationId) {
        return TURN_COUNTER_KEY_PREFIX + conversationId;
    }
}
