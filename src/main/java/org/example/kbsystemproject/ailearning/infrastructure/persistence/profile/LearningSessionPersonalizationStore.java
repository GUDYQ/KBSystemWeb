package org.example.kbsystemproject.ailearning.infrastructure.persistence.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.profile.LearningSessionPersonalizationRecord;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Repository
public class LearningSessionPersonalizationStore {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public LearningSessionPersonalizationStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    public Mono<LearningSessionPersonalizationRecord> findByConversationId(String conversationId) {
        return databaseClient.sql("""
                        SELECT conversation_id, user_id, current_topic, recent_topics_json, updated_at
                        FROM learning_session_personalization
                        WHERE conversation_id = :conversationId
                        """)
                .bind("conversationId", conversationId)
                .map((row, metadata) -> new LearningSessionPersonalizationRecord(
                        row.get("conversation_id", String.class),
                        row.get("user_id", String.class),
                        row.get("current_topic", String.class),
                        parseTopics(row.get("recent_topics_json", String.class)),
                        row.get("updated_at", OffsetDateTime.class)
                ))
                .one();
    }

    public Mono<Void> upsertTopic(String conversationId, String userId, String currentTopic) {
        return findByConversationId(conversationId)
                .defaultIfEmpty(new LearningSessionPersonalizationRecord(conversationId, userId, null, List.of(), null))
                .flatMap(existing -> {
                    List<String> topics = mergeTopics(existing.recentTopics(), currentTopic);
                    return Mono.fromCallable(() -> objectMapper.writeValueAsString(topics))
                            .flatMap(topicsJson -> bindNullable(databaseClient.sql("""
                                            INSERT INTO learning_session_personalization (
                                                conversation_id, user_id, current_topic, recent_topics_json, updated_at
                                            )
                                            VALUES (
                                                :conversationId, :userId, :currentTopic, CAST(:recentTopicsJson AS jsonb), NOW()
                                            )
                                            ON CONFLICT (conversation_id) DO UPDATE
                                            SET user_id = EXCLUDED.user_id,
                                                current_topic = EXCLUDED.current_topic,
                                                recent_topics_json = EXCLUDED.recent_topics_json,
                                                updated_at = NOW()
                                            """),
                                    "currentTopic",
                                    currentTopic,
                                    String.class)
                                    .flatMap(spec -> spec.bind("conversationId", conversationId)
                                            .bind("userId", userId)
                                            .bind("recentTopicsJson", topicsJson)
                                            .fetch()
                                            .rowsUpdated()
                                            .then()));
                });
    }

    private List<String> mergeTopics(List<String> existingTopics, String currentTopic) {
        ArrayList<String> merged = new ArrayList<>();
        if (currentTopic != null && !currentTopic.isBlank()) {
            merged.add(currentTopic.trim());
        }
        if (existingTopics != null) {
            merged.addAll(existingTopics);
        }
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>(merged);
        return deduplicated.stream().limit(5).toList();
    }

    private List<String> parseTopics(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, LIST_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse learning session personalization topics", error);
        }
    }

    private <T> Mono<DatabaseClient.GenericExecuteSpec> bindNullable(DatabaseClient.GenericExecuteSpec spec,
                                                                     String name,
                                                                     T value,
                                                                     Class<T> type) {
        if (value == null) {
            return Mono.just(spec.bindNull(name, type));
        }
        return Mono.just(spec.bind(name, value));
    }
}
