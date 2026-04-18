package org.example.kbsystemproject.ailearning.interfaces.http.request;

import jakarta.validation.constraints.NotBlank;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;

public record ChatRequest(
        String conversationId,
        String userId,
        String subject,
        LearningSessionType sessionType,
        String learningGoal,
        String currentTopic,
        @NotBlank(message = "prompt 不能为空")
        String prompt
) {
}
