package org.example.kbsystemproject.ailearning.infrastructure.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LongTermMemoryEntry;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ConversationArchiveStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public ConversationArchiveStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    // 把一轮 user/assistant 消息作为长期记忆原文归档到向量表。
    public Mono<Void> archiveTurnPair(String conversationId,
                                      SessionTurnPair turnPair,
                                      float[] userEmbedding,
                                      float[] assistantEmbedding,
                                      Map<String, Object> sessionMetadata,
                                      int turnIndex) {
        return archiveMessage(conversationId, turnPair.userTurn(), userEmbedding, "MESSAGE", sessionMetadata, turnIndex, 0)
                .then(archiveMessage(conversationId, turnPair.assistantTurn(), assistantEmbedding, "MESSAGE", sessionMetadata, turnIndex, 1));
    }

    // 把摘要类内容作为 SUMMARY 记忆写入向量表。
    public Mono<Void> archiveSummary(String conversationId,
                                     String summary,
                                     float[] embedding,
                                     Map<String, Object> metadata) {
        ConversationTurn turn = new ConversationTurn(SessionMessageRole.SYSTEM, summary);
        return archiveMessage(conversationId, turn, embedding, "SUMMARY", metadata, null, null);
    }

    // 在向量表中检索当前会话的长期记忆，按相似度倒序返回。
    public Mono<List<LongTermMemoryEntry>> searchRelevantMemories(String conversationId,
                                                                  float[] queryEmbedding,
                                                                  List<String> memoryTypes,
                                                                  int topK) {
        if (memoryTypes == null || memoryTypes.isEmpty()) {
            return Mono.just(List.of());
        }

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < memoryTypes.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append(":type").append(i);
        }

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
                SELECT id, conversation_id, memory_type, content, created_at, metadata,
                       (1 - (embedding <=> CAST(:queryEmbedding AS vector))) AS score
                FROM vector_store_conversation
                WHERE conversation_id = :conversationId
                  AND memory_type IN (%s)
                ORDER BY score DESC, created_at DESC
                LIMIT :topK
                """.formatted(placeholders))
                .bind("queryEmbedding", floatArrayToVectorString(queryEmbedding))
                .bind("conversationId", conversationId)
                .bind("topK", topK);

        for (int i = 0; i < memoryTypes.size(); i++) {
            spec = spec.bind("type" + i, memoryTypes.get(i));
        }

        return spec.map((row, metadata) -> new LongTermMemoryEntry(
                        readIdentifier(row.get("id")),
                        row.get("conversation_id", String.class),
                        row.get("memory_type", String.class),
                        row.get("content", String.class),
                        row.get("score", Double.class),
                        row.get("created_at", OffsetDateTime.class),
                        parseMetadata(row.get("metadata", String.class))
                ))
                .all()
                .collectList();
    }

    // 兼容 UUID/文本等不同主键类型的统一读取方式。
    private String readIdentifier(Object rawId) {
        return rawId == null ? null : rawId.toString();
    }

    // 从向量归档表读取最近原始消息，通常作为长期归档回查能力。
    public Mono<List<ConversationTurn>> loadRecentTurns(String conversationId, int limit) {
        return databaseClient.sql("""
                        SELECT content, metadata, created_at, turn_index, message_index
                        FROM vector_store_conversation
                        WHERE conversation_id = :conversationId
                          AND memory_type = 'MESSAGE'
                        ORDER BY turn_index DESC NULLS LAST, message_index DESC NULLS LAST, created_at DESC
                        LIMIT :limit
                        """)
                .bind("conversationId", conversationId)
                .bind("limit", limit)
                .map((row, metadata) -> {
                    Map<String, Object> data = parseMetadata(row.get("metadata", String.class));
                    String role = String.valueOf(data.getOrDefault("role", SessionMessageRole.USER.name()));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> messageMetadata = data.get("messageMetadata") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map
                            : Map.of();
                    Map<String, Object> mergedMetadata = new LinkedHashMap<>(messageMetadata);
                    Integer turnIndex = row.get("turn_index", Integer.class);
                    Integer messageIndex = row.get("message_index", Integer.class);
                    if (turnIndex != null) {
                        mergedMetadata.put("turnIndex", turnIndex);
                    }
                    if (messageIndex != null) {
                        mergedMetadata.put("messageIndex", messageIndex);
                    }
                    return new ConversationTurn(
                            SessionMessageRole.valueOf(role),
                            row.get("content", String.class),
                            row.get("created_at", OffsetDateTime.class),
                            mergedMetadata
                    );
                })
                .all()
                .collectList()
                .map(turns -> {
                    java.util.Collections.reverse(turns);
                    return turns;
                });
    }

    // 按轮次范围从向量归档表回放原始消息。
    public Mono<List<ConversationTurn>> loadTurnsByTurnRange(String conversationId, int startTurnInclusive, int endTurnInclusive) {
        return databaseClient.sql("""
                        SELECT content, metadata, created_at, turn_index, message_index
                        FROM vector_store_conversation
                        WHERE conversation_id = :conversationId
                          AND memory_type = 'MESSAGE'
                          AND turn_index BETWEEN :startTurn AND :endTurn
                        ORDER BY turn_index ASC, message_index ASC, created_at ASC
                        """)
                .bind("conversationId", conversationId)
                .bind("startTurn", startTurnInclusive)
                .bind("endTurn", endTurnInclusive)
                .map((row, metadata) -> {
                    Map<String, Object> data = parseMetadata(row.get("metadata", String.class));
                    String role = String.valueOf(data.getOrDefault("role", SessionMessageRole.USER.name()));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> messageMetadata = data.get("messageMetadata") instanceof Map<?, ?> map
                            ? (Map<String, Object>) map
                            : Map.of();
                    Map<String, Object> mergedMetadata = new LinkedHashMap<>(messageMetadata);
                    Integer turnIndex = row.get("turn_index", Integer.class);
                    Integer messageIndex = row.get("message_index", Integer.class);
                    if (turnIndex != null) {
                        mergedMetadata.put("turnIndex", turnIndex);
                    }
                    if (messageIndex != null) {
                        mergedMetadata.put("messageIndex", messageIndex);
                    }
                    return new ConversationTurn(
                            SessionMessageRole.valueOf(role),
                            row.get("content", String.class),
                            row.get("created_at", OffsetDateTime.class),
                            mergedMetadata
                    );
                })
                .all()
                .collectList();
    }

    // 向向量表写入一条归档消息；带 turnIndex 时可做幂等去重。
    private Mono<Void> archiveMessage(String conversationId,
                                      ConversationTurn turn,
                                      float[] embedding,
                                      String memoryType,
                                      Map<String, Object> sessionMetadata,
                                      Integer turnIndex,
                                      Integer messageIndex) {
        String summaryKey = resolveSummaryKey(memoryType, sessionMetadata);
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(buildMetadata(turn, sessionMetadata)))
                .flatMap(metadataJson -> bindNullable(databaseClient.sql("""
                                INSERT INTO vector_store_conversation (
                                    content, metadata, conversation_id, memory_type, embedding, created_at, turn_index, message_index, summary_key
                                )
                                VALUES (
                                    :content, CAST(:metadata AS jsonb), :conversationId, :memoryType,
                                    CAST(:embedding AS vector), :createdAt, :turnIndex, :messageIndex, :summaryKey
                                )
                                ON CONFLICT DO NOTHING
                                """)
                                .bind("content", turn.content())
                                .bind("metadata", metadataJson)
                                .bind("conversationId", conversationId)
                                .bind("memoryType", memoryType)
                                .bind("embedding", floatArrayToVectorString(embedding))
                                .bind("createdAt", turn.createdAt()),
                        "turnIndex",
                        turnIndex,
                        Integer.class))
                .flatMap(spec -> bindNullable(spec, "messageIndex", messageIndex, Integer.class))
                .flatMap(spec -> bindNullable(spec, "summaryKey", summaryKey, String.class))
                .flatMap(spec -> spec.fetch().rowsUpdated().then());
    }

    // 给可空字段统一做 bind/bindNull 处理。
    private <T> Mono<DatabaseClient.GenericExecuteSpec> bindNullable(DatabaseClient.GenericExecuteSpec spec,
                                                                     String name,
                                                                     T value,
                                                                     Class<T> type) {
        if (value == null) {
            return Mono.just(spec.bindNull(name, type));
        }
        return Mono.just(spec.bind(name, value));
    }

    // 构造归档入库时的 JSON 元数据。
    private Map<String, Object> buildMetadata(ConversationTurn turn, Map<String, Object> sessionMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("role", turn.role().name());
        metadata.put("createdAt", turn.createdAt().toString());
        metadata.put("messageMetadata", turn.metadata());
        if (sessionMetadata != null && !sessionMetadata.isEmpty()) {
            metadata.putAll(sessionMetadata);
        }
        return metadata;
    }

    private String resolveSummaryKey(String memoryType, Map<String, Object> sessionMetadata) {
        if (!"SUMMARY".equalsIgnoreCase(memoryType) || sessionMetadata == null || sessionMetadata.isEmpty()) {
            return null;
        }

        Object summaryType = sessionMetadata.get("summaryType");
        String normalizedSummaryType = summaryType == null
                ? "SUMMARY"
                : String.valueOf(summaryType).trim().toUpperCase(Locale.ROOT);

        Integer startTurn = toInteger(sessionMetadata.get("summaryStartTurn"));
        if (startTurn == null) {
            startTurn = toInteger(sessionMetadata.get("startTurn"));
        }
        Integer endTurn = toInteger(sessionMetadata.get("summaryEndTurn"));
        if (endTurn == null) {
            endTurn = toInteger(sessionMetadata.get("endTurn"));
        }

        if (startTurn != null && endTurn != null) {
            return normalizedSummaryType + ":" + startTurn + "-" + endTurn;
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Integer integerValue) {
            return integerValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue();
        }
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // 解析向量表里存储的 JSON 元数据。
    private Map<String, Object> parseMetadata(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse memory metadata", e);
        }
    }

    // 把 float[] 转成 pgvector 可识别的文本格式。
    private String floatArrayToVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
