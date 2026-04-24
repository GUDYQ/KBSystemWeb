package org.example.kbsystemproject.ailearning.interfaces.http.response;

public record ChatChunkResponse(
        String type,
        String conversationId,
        String requestId,
        String content
) {
    public static ChatChunkResponse status(String conversationId, String requestId, String content) {
        return new ChatChunkResponse("status", conversationId, requestId, content);
    }

    public static ChatChunkResponse token(String conversationId, String requestId, String content) {
        return new ChatChunkResponse("token", conversationId, requestId, content);
    }

    public static ChatChunkResponse error(String conversationId, String requestId, String content) {
        return new ChatChunkResponse("error", conversationId, requestId, content);
    }

    public static ChatChunkResponse finish(String conversationId, String requestId, String content) {
        return new ChatChunkResponse("finish", conversationId, requestId, content);
    }
}
