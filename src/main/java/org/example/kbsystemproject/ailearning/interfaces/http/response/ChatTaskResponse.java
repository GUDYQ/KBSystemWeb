package org.example.kbsystemproject.ailearning.interfaces.http.response;

public record ChatTaskResponse(
        String conversationId,
        String requestId,
        String status
) {
}
