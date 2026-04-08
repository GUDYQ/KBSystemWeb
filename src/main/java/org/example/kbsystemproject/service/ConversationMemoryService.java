package org.example.kbsystemproject.service;

import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Service
public class ConversationMemoryService {

    private final JdbcTemplate jdbcTemplate;

    public ConversationMemoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 冷归档原始对话 (Fire-and-Forget)
     */
    public Mono<Void> archiveRawMessage(String conversationId, String content, float[] embedding, Map<String, Object> metadata) {
        return Mono.fromCallable(() -> {
            String sql = "INSERT INTO vector_store_conversation (content, metadata, conversation_id, type, embedding) " +
                    "VALUES (?, ?::jsonb, ?, 'MESSAGE', ?)";
            jdbcTemplate.update(sql, content, mapToJsonStr(metadata), conversationId, createPgVector(embedding));
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then(); // 绝对红线：隔离阻塞
    }

    /**
     * 落盘滚动摘要
     */
    public Mono<Void> generateRollingSummary(String conversationId, String summaryText, float[] embedding, Map<String, Object> metadata) {
        return Mono.fromCallable(() -> {
            String sql = "INSERT INTO vector_store_conversation (content, metadata, conversation_id, type, embedding) " +
                    "VALUES (?, ?::jsonb, ?, 'SUMMARY', ?)";
            jdbcTemplate.update(sql, summaryText, mapToJsonStr(metadata), conversationId, createPgVector(embedding));
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * 查询长期摘要 (SQL内时间衰减)
     * 绝对红线：利用物理列 created_at，严禁 metadata->>'xxx'，严禁内存计算衰减
     */
    public Mono<List<Map<String, Object>>> searchSummaries(String conversationId, float[] queryEmbedding, int topK) {
        return Mono.fromCallable(() -> {
            // 核心规范：(1 - 余弦距离) * EXP时间衰减
            String sql = """
                SELECT content, metadata, 
                       (1 - (embedding <=> ?)) * EXP(-0.1 * EXTRACT(EPOCH FROM (NOW() - created_at)) / 86400) AS final_score
                FROM vector_store_conversation
                WHERE conversation_id = ? AND type = 'SUMMARY'
                ORDER BY final_score DESC
                LIMIT ?
                """;
            return jdbcTemplate.queryForList(sql, createPgVector(queryEmbedding), conversationId, topK);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // --- 私有辅助方法 ---

    private PGobject createPgVector(float[] vector) {
        try {
            PGobject pgObject = new PGobject();
            pgObject.setType("vector");
            pgObject.setValue(floatArrayToVectorString(vector));
            return pgObject;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create PGvector object", e);
        }
    }

    private String floatArrayToVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private String mapToJsonStr(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return "{}";
        // 实际项目中建议替换为 ObjectMapper.writeValueAsString(metadata)
        // 此处为避免引入多余依赖展示核心逻辑，做简化字符串拼接
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\",");
        }
        if (sb.length() > 1) sb.deleteCharAt(sb.length() - 1);
        sb.append("}");
        return sb.toString();
    }
}
