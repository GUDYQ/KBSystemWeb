package org.example.kbsystemproject.ailearning.interfaces.http.request;

import jakarta.validation.constraints.NotBlank;

public record LearningProfileUpdateRequest(
        @NotBlank(message = "userId 不能为空")
        String userId,
        String subject,
        String learningGoal,
        String preferredStyle,
        String preferredLanguage
) {
}
