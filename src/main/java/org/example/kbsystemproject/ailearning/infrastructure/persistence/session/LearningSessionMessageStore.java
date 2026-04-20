package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class LearningSessionMessageStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public LearningSessionMessageStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> saveTurnPair(String conversationId, String requestId, SessionTurnPair turnPair, int turnIndex) {
        return saveMessage(conversationId, requestId, turnPair.userTurn(), turnIndex, 0)
                .then(saveMessage(conversationId, requestId, turnPair.assistantTurn(), turnIndex, 1));
    }

    public Mono<List<ConversationTurn>> loadRecentTurns(String conversationId, int limit) {
        return databaseClient.sql("""
                        SELECT role, content, message_metadata, created_at, turn_index, message_index
                        FROM learning_session_message
                        WHERE conversation_id = :conversationId
                        ORDER BY turn_index DESC, message_index DESC, created_at DESC
                        LIMIT :limit
                        """)
                .bind("conversationId", conversationId)
                .bind("limit", limit)
                .map((row, metadata) -> toTurn(
                        row.get("role", String.class),
                        row.get("content", String.class),
                        row.get("message_metadata", String.class),
                        row.get("created_at", OffsetDateTime.class),
                        row.get("turn_index", Integer.class),
                        row.get("message_index", Integer.class)
                ))
                .all()
                .collectList()
                .map(turns -> {
                    java.util.Collections.reverse(turns);
                    return turns;
                });
    }

    public Mono<List<ConversationTurn>> loadTurnsByTurnRange(String conversationId, int startTurnInclusive, int endTurnInclusive) {
        return databaseClient.sql("""
                        SELECT role, content, message_metadata, created_at, turn_index, message_index
                        FROM learning_session_message
                        WHERE conversation_id = :conversationId
                          AND turn_index BETWEEN :startTurn AND :endTurn
                        ORDER BY turn_index ASC, message_index ASC, created_at ASC
                        """)
                .bind("conversationId", conversationId)
                .bind("startTurn", startTurnInclusive)
                .bind("endTurn", endTurnInclusive)
                .map((row, metadata) -> toTurn(
                        row.get("role", String.class),
                        row.get("content", String.class),
                        row.get("message_metadata", String.class),
                        row.get("created_at", OffsetDateTime.class),
                        row.get("turn_index", Integer.class),
                        row.get("message_index", Integer.class)
                ))
                .all()
                .collectList();
    }

    public Mono<String> findAssistantContent(String conversationId, String requestId) {
        return databaseClient.sql("""
                        SELECT content
                        FROM learning_session_message
                        WHERE conversation_id = :conversationId
                          AND request_id = :requestId
                          AND message_index = 1
                        ORDER BY turn_index DESC
                        LIMIT 1
                        """)
                .bind("conversationId", conversationId)
                .bind("requestId", requestId)
                .map((row, metadata) -> row.get("content", String.class))
                .one();
    }

    private Mono<Void> saveMessage(String conversationId,
                                   String requestId,
                                   ConversationTurn turn,
                                   int turnIndex,
                                   int messageIndex) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(turn.metadata()))
                .flatMap(metadataJson -> databaseClient.sql("""
                                INSERT INTO learning_session_message (
                                    conversation_id, request_id, turn_index, message_index, role, content, message_metadata, created_at
                                )
                                VALUES (
                                    :conversationId, :requestId, :turnIndex, :messageIndex, :role, :content, CAST(:messageMetadata AS jsonb), :createdAt
                                )
                                ON CONFLICT (conversation_id, turn_index, message_index) DO NOTHING
                                """)
                        .bind("conversationId", conversationId)
                        .bind("requestId", requestId)
                        .bind("turnIndex", turnIndex)
                        .bind("messageIndex", messageIndex)
                        .bind("role", turn.role().name())
                        .bind("content", turn.content())
                        .bind("messageMetadata", metadataJson)
                        .bind("createdAt", turn.createdAt())
                        .fetch()
                        .rowsUpdated()
                        .then());
    }

    private ConversationTurn toTurn(String role,
                                    String content,
                                    String rawMetadata,
                                    OffsetDateTime createdAt,
                                    Integer turnIndex,
                                    Integer messageIndex) {
        Map<String, Object> metadata = parseMetadata(rawMetadata);
        Map<String, Object> mergedMetadata = new LinkedHashMap<>(metadata);
        if (turnIndex != null) {
            mergedMetadata.put("turnIndex", turnIndex);
        }
        if (messageIndex != null) {
            mergedMetadata.put("messageIndex", messageIndex);
        }
        return new ConversationTurn(SessionMessageRole.valueOf(role), content, createdAt, mergedMetadata);
    }

    private Map<String, Object> parseMetadata(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(rawJson, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse session message metadata", e);
        }
    }
}
