package org.example.kbsystemproject.entity;

import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.OffsetDateTime;

@Getter
@Table("conversation")
public class Conversation {
    // Getters and Setters
    @Id
    private Long id;
    private String conversationId;
    private Integer turnCount;
    private OffsetDateTime lastActiveAt;

    public void setId(Long id) { this.id = id; }

    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }

    public void setLastActiveAt(OffsetDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }
}
