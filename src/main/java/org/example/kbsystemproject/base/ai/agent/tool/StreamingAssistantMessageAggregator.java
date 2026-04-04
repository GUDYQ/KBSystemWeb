package org.example.kbsystemproject.base.ai.agent.tool;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.content.Media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StreamingAssistantMessageAggregator {

    // 全部改为线程安全的容器
    private final StringBuffer contentBuilder = new StringBuffer(); // 改为 StringBuffer
    private final List<Media> mediaList = Collections.synchronizedList(new ArrayList<>()); // 同步 List

    private final ConcurrentHashMap<String, ToolCallBuffer> toolCallBuffers = new ConcurrentHashMap<>();

    /**
     * 使用 synchronized 关键字保证整个累加过程的原子性
     * 这是最简单、最粗暴、也最有效的防御手段
     */
    public synchronized void accumulate(AssistantMessage chunk) {
        if (chunk == null) return;

        if (chunk.getText() != null) {
            contentBuilder.append(chunk.getText());
        }

        mediaList.addAll(chunk.getMedia()); // synchronizedList.addAll 本身安全

        for (ToolCall toolCall : chunk.getToolCalls()) {
            if (toolCall.id().isBlank()) continue;

            toolCallBuffers
                    .computeIfAbsent(toolCall.id(), id ->
                            new ToolCallBuffer(id, toolCall.type(), toolCall.name())
                    )
                    .appendArguments(toolCall.arguments());
        }
    }

    public AssistantMessage buildFinalMessage() {
        // 此时流已结束，不需要加锁，但为了严谨，可以将 Map 转为不可变结构
        List<ToolCall> finalToolCalls = List.copyOf(
                toolCallBuffers.values().stream()
                        .map(ToolCallBuffer::toToolCall)
                        .toList()
        );

        return AssistantMessage.builder()
                .content(contentBuilder.toString())
                .media(List.copyOf(mediaList))
                .toolCalls(finalToolCalls)
                .build();
    }

    // ToolCallBuffer 内部的 StringBuilder 也需要改为 StringBuffer
    private static class ToolCallBuffer {
        private final String id;
        private final String type;
        private final String name;
        private final StringBuffer argumentsBuilder = new StringBuffer(); // 改为 StringBuffer

        private ToolCallBuffer(String id, String type, String name) {
            this.id = id;
            this.type = type;
            this.name = name;
        }


        public void appendArguments(String argChunk) {
            if (argChunk != null) {
                argumentsBuilder.append(argChunk);
            }
        }

        public AssistantMessage.ToolCall toToolCall() {
            return new ToolCall(id, type, name, argumentsBuilder.toString());
        }

    }
}
