package org.example.kbsystemproject.ailearning.interfaces.http.request;

import jakarta.validation.constraints.NotBlank;
import org.example.kbsystemproject.ailearning.domain.session.ConversationMode;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;

public record ChatRequest(
        String conversationId,
        String requestId,
        String userId,
        String subject,
        LearningSessionType sessionType,
        String learningGoal,
        String currentTopic,
        ConversationMode conversationMode,
        @NotBlank(message = "prompt 不能为空")
        String prompt
) {
}
