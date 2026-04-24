package org.example.kbsystemproject.ailearning.infrastructure.persistence.session;

import org.example.kbsystemproject.ailearning.domain.session.SessionTopicBlock;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

@Repository
public class LearningSessionTopicBlockStore {

    private final DatabaseClient databaseClient;

    public LearningSessionTopicBlockStore(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    // 读取当前会话仍处于 ACTIVE 的主题块。
    public Mono<SessionTopicBlock> findActiveByConversationId(String conversationId) {
        return databaseClient.sql("""
                        SELECT id, conversation_id, topic, status, start_turn, last_turn, turn_count,
                               stable_score, unresolved_count, low_info_ratio, summary, updated_at
                        FROM learning_session_topic_block
                        WHERE conversation_id = :conversationId
                          AND status = 'ACTIVE'
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """)
                .bind("conversationId", conversationId)
                .map((row, metadata) -> new SessionTopicBlock(
                        row.get("id", Long.class),
                        row.get("conversation_id", String.class),
                        row.get("topic", String.class),
                        row.get("status", String.class),
                        row.get("start_turn", Integer.class),
                        row.get("last_turn", Integer.class),
                        row.get("turn_count", Integer.class),
                        row.get("stable_score", Integer.class),
                        row.get("unresolved_count", Integer.class),
                        row.get("low_info_ratio", Double.class),
                        row.get("summary", String.class),
                        row.get("updated_at", OffsetDateTime.class)
                ))
                .one();
    }

    // 创建一个新的 ACTIVE 主题块，作为当前短期主题窗口。
    public Mono<SessionTopicBlock> createActiveBlock(String conversationId,
                                                     String topic,
                                                     int turnIndex,
                                                     int unresolvedCount,
                                                     double lowInfoRatio,
                                                     String summary) {
        return databaseClient.sql("""
                        INSERT INTO learning_session_topic_block (
                            conversation_id, topic, status, start_turn, last_turn, turn_count,
                            stable_score, unresolved_count, low_info_ratio, summary, updated_at
                        )
                        VALUES (
                            :conversationId, :topic, 'ACTIVE', :turnIndex, :turnIndex, 1,
                            :stableScore, :unresolvedCount, :lowInfoRatio, :summary, NOW()
                        )
                        RETURNING id, conversation_id, topic, status, start_turn, last_turn, turn_count,
                                  stable_score, unresolved_count, low_info_ratio, summary, updated_at
                        """)
                .bind("conversationId", conversationId)
                .bind("topic", topic)
                .bind("turnIndex", turnIndex)
                .bind("stableScore", 1)
                .bind("unresolvedCount", unresolvedCount)
                .bind("lowInfoRatio", lowInfoRatio)
                .bind("summary", summary)
                .map((row, metadata) -> new SessionTopicBlock(
                        row.get("id", Long.class),
                        row.get("conversation_id", String.class),
                        row.get("topic", String.class),
                        row.get("status", String.class),
                        row.get("start_turn", Integer.class),
                        row.get("last_turn", Integer.class),
                        row.get("turn_count", Integer.class),
                        row.get("stable_score", Integer.class),
                        row.get("unresolved_count", Integer.class),
                        row.get("low_info_ratio", Double.class),
                        row.get("summary", String.class),
                        row.get("updated_at", OffsetDateTime.class)
                ))
                .one();
    }

    // 更新当前 ACTIVE 主题块的轮次、稳定度、摘要等统计字段。
    public Mono<SessionTopicBlock> updateActiveBlock(SessionTopicBlock block,
                                                     int lastTurn,
                                                     int stableScore,
                                                     int unresolvedCount,
                                                     double lowInfoRatio,
                                                     String summary) {
        return databaseClient.sql("""
                        UPDATE learning_session_topic_block
                        SET last_turn = :lastTurn,
                            turn_count = :turnCount,
                            stable_score = :stableScore,
                            unresolved_count = :unresolvedCount,
                            low_info_ratio = :lowInfoRatio,
                            summary = :summary,
                            updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, conversation_id, topic, status, start_turn, last_turn, turn_count,
                                  stable_score, unresolved_count, low_info_ratio, summary, updated_at
                        """)
                .bind("lastTurn", lastTurn)
                .bind("turnCount", block.turnCount() + 1)
                .bind("stableScore", stableScore)
                .bind("unresolvedCount", unresolvedCount)
                .bind("lowInfoRatio", lowInfoRatio)
                .bind("summary", summary)
                .bind("id", block.id())
                .map((row, metadata) -> new SessionTopicBlock(
                        row.get("id", Long.class),
                        row.get("conversation_id", String.class),
                        row.get("topic", String.class),
                        row.get("status", String.class),
                        row.get("start_turn", Integer.class),
                        row.get("last_turn", Integer.class),
                        row.get("turn_count", Integer.class),
                        row.get("stable_score", Integer.class),
                        row.get("unresolved_count", Integer.class),
                        row.get("low_info_ratio", Double.class),
                        row.get("summary", String.class),
                        row.get("updated_at", OffsetDateTime.class)
                ))
                .one();
    }

    // 结束当前主题块，把状态切成 FINALIZED。
    public Mono<SessionTopicBlock> finalizeBlock(SessionTopicBlock block, String summary) {
        return databaseClient.sql("""
                        UPDATE learning_session_topic_block
                        SET status = 'FINALIZED',
                            summary = :summary,
                            updated_at = NOW()
                        WHERE id = :id
                        RETURNING id, conversation_id, topic, status, start_turn, last_turn, turn_count,
                                  stable_score, unresolved_count, low_info_ratio, summary, updated_at
                        """)
                .bind("summary", summary)
                .bind("id", block.id())
                .map((row, metadata) -> new SessionTopicBlock(
                        row.get("id", Long.class),
                        row.get("conversation_id", String.class),
                        row.get("topic", String.class),
                        row.get("status", String.class),
                        row.get("start_turn", Integer.class),
                        row.get("last_turn", Integer.class),
                        row.get("turn_count", Integer.class),
                        row.get("stable_score", Integer.class),
                        row.get("unresolved_count", Integer.class),
                        row.get("low_info_ratio", Double.class),
                        row.get("summary", String.class),
                        row.get("updated_at", OffsetDateTime.class)
                ))
                .one();
    }
}
