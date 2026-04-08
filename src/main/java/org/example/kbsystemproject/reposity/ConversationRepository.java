package org.example.kbsystemproject.reposity;

import org.example.kbsystemproject.entity.Conversation;
import org.example.kbsystemproject.entity.User;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface ConversationRepository extends ReactiveCrudRepository<Conversation, Long> {
    Mono<Conversation> findFirstByConversationId(String conversationId);

    @Modifying
    @Query("UPDATE conversation SET turn_count = turn_count + 1, updated_at = NOW() WHERE conversation_id = :conversationId")
    Mono<Void> incrementTurnCountRaw(String conversationId);

    /**
     * 自定义修改：重置轮次 (摘要完成后调用)
     */
    @Modifying
    @Query("UPDATE conversation SET turn_count = 0, updated_at = NOW() WHERE conversation_id = :conversationId")
    Mono<Void> resetTurnCountRaw(String conversationId);

    @Modifying
    @Query("UPDATE conversation SET last_active_at = NOW() WHERE conversation_id = :id")
    Mono<Void> updateLastActiveAt(String conversationId);
}
