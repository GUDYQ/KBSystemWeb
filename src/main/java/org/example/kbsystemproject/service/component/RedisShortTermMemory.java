package org.example.kbsystemproject.service.component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.List;

/**
 * Redis 短期记忆组件
 *
 * 核心解决痛点：高并发下防止 USER 和 ASSISTANT 消息交叉错乱。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisShortTermMemory {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // 最大保留条数 (从配置读取，这里简化写死，实际请注入 MemoryProperties)
    private static final int MAX_MESSAGES = 20;

    /**
     * ⭐ 核心：原子化写入一轮对话的 Lua 脚本
     *
     * KEYS[1] = Redis Key (List)
     * KEYS[2] = Redis Key for global turn counter
     * ARGV[1] = 最大长度
     * ARGV[2] = USER 消息 JSON
     * ARGV[3] = ASSISTANT 消消息 JSON
     */
    private static final String PUSH_TURN_SCRIPT = """
            local key = KEYS[1]
            local counterKey = KEYS[2]
            local maxLen = tonumber(ARGV[1])
            local userMsg = ARGV[2]
            local assistantMsg = ARGV[3]
            
            -- 1. 原子性追加一问一答
            redis.call('RPUSH', key, userMsg, assistantMsg)
            
            -- 2. 防止内存溢出，裁剪保留最新的 N 条
            local currentLen = redis.call('LLEN', key)
            if currentLen > maxLen then
                redis.call('LTRIM', key, currentLen - maxLen, -1)
            end
            
            -- 3. 增加总轮数计数
            redis.call('INCR', counterKey)
            
            return 1
            """;

    private final RedisScript<Long> pushTurnRedisScript = RedisScript.of(PUSH_TURN_SCRIPT, Long.class);

    /**
     * 原子追加一轮完整对话
     * 只有在 LLM 完全回复后，才调用此方法。保证 Redis 里的数据永远是成对的。
     */
    public Mono<Void> addTurnAtomically(String conversationId, Message userMessage, Message assistantReply) {
        String key = "chat:memory:" + conversationId;
        String counterKey = "chat:memory:turns:" + conversationId;

        try {
            // 使用 Jackson ObjectMapper 序列化为 JSON 字符串
            ObjectNode userNode = objectMapper.createObjectNode();
            userNode.put("role", userMessage.getMessageType().getValue());
            userNode.put("content", userMessage.getText());
            String userJson = objectMapper.writeValueAsString(userNode);

            ObjectNode assistantNode = objectMapper.createObjectNode();
            assistantNode.put("role", assistantReply.getMessageType().getValue());
            assistantNode.put("content", assistantReply.getText());
            String assistantJson = objectMapper.writeValueAsString(assistantNode);

            return redisTemplate.execute(
                    pushTurnRedisScript,
                    List.of(key, counterKey), // KEYS
                    List.of(String.valueOf(MAX_MESSAGES), userJson, assistantJson) // ARGVS
            ).then();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    /**
     * 获取最近的短期记忆 (按时间正序)
     */
    public Mono<List<Message>> getRecent(String conversationId) {
        String key = "chat:memory:" + conversationId;
        // LRANGE 0 -1 获取全部，因为我们已经在 Lua 里控制了长度
        return redisTemplate.opsForList().range(key, 0, -1)
                .<Message>map(json -> {
                    try {
                        JsonNode node = objectMapper.readTree(json);
                        String role = node.get("role").asText();
                        String content = node.get("content").asText();

                        if ("user".equalsIgnoreCase(role) || "USER".equalsIgnoreCase(role)) {
                            return new UserMessage(content);
                        } else if ("assistant".equalsIgnoreCase(role) || "ASSISTANT".equalsIgnoreCase(role)) {
                            return new AssistantMessage(content);
                        } else if ("system".equalsIgnoreCase(role) || "SYSTEM".equalsIgnoreCase(role)) {
                            return new SystemMessage(content);
                        }
                        return new UserMessage(content);
                    } catch (Exception e) {
                        throw new RuntimeException("Deserialize Redis memory error", e);
                    }
                })
                .collectList();
    }

    /**
     * 摘要后清空该会话的短期记忆
     */
    public Mono<Void> clear(String conversationId) {
        String key = "chat:memory:" + conversationId;
        String counterKey = "chat:memory:turns:" + conversationId;
        return redisTemplate.delete(key, counterKey).then();
    }

    /**
     * 获取会话的总轮数
     */
    public Mono<Long> getTotalTurnsMono(String conversationId) {
        String counterKey = "chat:memory:turns:" + conversationId;
        return redisTemplate.opsForValue().get(counterKey)
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }
}
