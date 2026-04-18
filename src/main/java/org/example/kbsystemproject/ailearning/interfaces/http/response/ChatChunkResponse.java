package org.example.kbsystemproject.ailearning.interfaces.http.response;

public record ChatChunkResponse(
        String type,
        String conversationId,
        String content
) {
    public static ChatChunkResponse token(String conversationId, String content) {
        return new ChatChunkResponse("token", conversationId, content);
    }

    public static ChatChunkResponse finish(String conversationId, String content) {
        return new ChatChunkResponse("finish", conversationId, content);
    }
}
