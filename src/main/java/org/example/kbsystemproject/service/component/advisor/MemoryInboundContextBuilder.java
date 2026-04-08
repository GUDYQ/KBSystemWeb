package org.example.kbsystemproject.service.component.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.entity.Conversation;
import org.example.kbsystemproject.service.ConversationMemoryService;
import org.example.kbsystemproject.service.ConversationService;
import org.example.kbsystemproject.service.component.RedisShortTermMemory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class MemoryInboundContextBuilder {

    private final ConversationService stateService;
    private final RedisShortTermMemory redisMemory;
    private final ConversationMemoryService memoryService;
    private final CompressionDecider compressionDecider;
    private final org.springframework.ai.chat.model.ChatModel rawChatModel;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public MemoryInboundContextBuilder(ConversationService stateService,
                                       RedisShortTermMemory redisMemory,
                                       ConversationMemoryService memoryService,
                                       CompressionDecider compressionDecider,
                                       org.springframework.ai.chat.model.ChatModel rawChatModel,
                                       EmbeddingModel embeddingModel,
                                       ObjectMapper objectMapper) {
        this.stateService = stateService;
        this.redisMemory = redisMemory;
        this.memoryService = memoryService;
        this.compressionDecider = compressionDecider;
        this.rawChatModel = rawChatModel;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 核心拼装方法 (供 Advisor 直接调用)
     */
    public ChatClientRequest build(ChatClientRequest request) {
        String conversationId = (String) request.context().getOrDefault("conversationId", "default-conv-id");
        List<Message> originalMessages = request.prompt().getInstructions();
        Message currentUserMessage = originalMessages.getLast();

        // 1. 状态与历史获取
        Conversation conv = stateService.getOrCreate(conversationId).block();
        List<Message> redisHistory = redisMemory.getRecent(conversationId).block();
        // 2. 判定与压缩
        boolean needCompress = compressionDecider.decide(
                redisHistory, currentUserMessage, conv.getTurnCount(), conv.getLastActiveAt()
        );
        if (needCompress) {
            executeSyncCompression(conversationId, redisHistory);
        }

        // 3. 组装最终 Prompt
        List<Message> freshRedisHistory = redisMemory.getRecent(conversationId).block();

        float[] queryEmbedding = embeddingModel.embed(currentUserMessage.getText());
        List<Map<String, Object>> summaries = memoryService.searchSummaries(conversationId, queryEmbedding, 3).block();

        List<Message> finalMessages = buildFinalPrompt(summaries, freshRedisHistory, originalMessages);
        Prompt newPrompt = new Prompt(finalMessages, request.prompt().getOptions());

        return ChatClientRequest.builder()
                .context(request.context())
                .prompt(newPrompt)
                .build();
    }

    // --- 私有辅助方法 ---
    private void executeSyncCompression(String conversationId, List<Message> redisHistoryMessages) {
        if (redisHistoryMessages == null || redisHistoryMessages.isEmpty()) return;
        StringBuilder historyText = new StringBuilder("请摘要：\n");
        for (Message msg : redisHistoryMessages) {
            String role = (msg instanceof UserMessage) ? "user" : "assistant";
            historyText.append(role).append(": ").append(msg.getText()).append("\n");
        }
        String summaryText = rawChatModel.call(new Prompt(new UserMessage(historyText.toString()))).getResult().getOutput().getText();
        float[] summaryEmb = embeddingModel.embed(summaryText);
        memoryService.generateRollingSummary(conversationId, summaryText, summaryEmb, null).block();
        redisMemory.clear(conversationId).block();
        stateService.resetTurn(conversationId).block();
    }

    private List<Message> buildFinalPrompt(List<Map<String, Object>> summaries, List<Message> freshRedisHistoryMessages, List<Message> originalMessages) {
        List<Message> finalMessages = new ArrayList<>();
        if (summaries != null && !summaries.isEmpty()) {
            StringBuilder sb = new StringBuilder("历史记忆背景：\n");
            summaries.forEach(row -> sb.append("- ").append(row.get("content")).append("\n"));
            finalMessages.add(new SystemMessage(sb.toString()));
        }
        if (freshRedisHistoryMessages != null) finalMessages.addAll(freshRedisHistoryMessages);
        finalMessages.add(originalMessages.get(originalMessages.size() - 1));
        return finalMessages;
    }

}
