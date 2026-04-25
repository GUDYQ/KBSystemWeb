package org.example.kbsystemproject.ailearning.application.chat;

import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionTopicBlock;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TopicInferenceService {

    public String inferTopic(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        return inferTopic(
                command.currentTopic(),
                snapshot == null || snapshot.session() == null ? null : snapshot.session().currentTopic(),
                snapshot == null ? null : snapshot.activeTopicBlock(),
                snapshot == null ? List.of() : snapshot.shortTermMemory(),
                command.prompt()
        );
    }

    public String inferTopic(String explicitTopic,
                             String sessionCurrentTopic,
                             SessionTopicBlock activeTopicBlock,
                             List<ConversationTurn> recentTurns,
                             String prompt) {
        if (explicitTopic != null && !explicitTopic.isBlank()) {
            return explicitTopic.trim();
        }
        if (activeTopicBlock != null && activeTopicBlock.topic() != null && !activeTopicBlock.topic().isBlank()) {
            return activeTopicBlock.topic().trim();
        }
        if (sessionCurrentTopic != null && !sessionCurrentTopic.isBlank()) {
            return sessionCurrentTopic.trim();
        }
        if (recentTurns == null || recentTurns.isEmpty()) {
            return null;
        }
        for (int i = recentTurns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = recentTurns.get(i);
            if (turn.role() != SessionMessageRole.USER || turn.metadata() == null) {
                continue;
            }
            Object currentTopic = turn.metadata().get("currentTopic");
            if (currentTopic instanceof String value && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

