package org.example.kbsystemproject.service;

import org.example.kbsystemproject.entity.Conversation;
import org.example.kbsystemproject.reposity.ConversationRepository;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.OffsetDateTime;

@Service
public class ConversationService {

    private final ConversationRepository repository;

    public ConversationService(ConversationRepository repository) {
        this.repository = repository;
    }

    public Mono<Conversation> getOrCreate(String conversationId) {
        return repository.findFirstByConversationId(conversationId)
                .switchIfEmpty(Mono.defer(() -> {
                    Conversation newConv = new Conversation();
                    newConv.setConversationId(conversationId);
                    newConv.setTurnCount(0);
                    newConv.setLastActiveAt(OffsetDateTime.now());
                    return repository.save(newConv);
                }));
    }

    public Mono<Void> incrementTurn(String conversationId) {
        return repository.incrementTurnCountRaw(conversationId);
    }

    public Mono<Void> resetTurn(String conversationId) {
        return repository.resetTurnCountRaw(conversationId);
    }

    public Mono<Void> updateLastActiveAt(String conversationId) {
        return repository.updateLastActiveAt(conversationId);
    }
}
