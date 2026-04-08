package org.example.kbsystemproject.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.base.ai.agent.ReActAgent;
import org.example.kbsystemproject.service.component.RedisShortTermMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
//import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentService {

    private static final int MAX_CONTEXT_DOCS = 4;

    private final RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;
    @Getter
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private ReActAgent reActAgent;
    private ConversationMemoryService memoryService;
    private final RedisShortTermMemory redisMemory;
    private final ConversationService stateService;
    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel;


    public AgentService(RetrievalAugmentationAdvisor retrievalAugmentationAdvisor, ChatClient chatClient,
                        VectorStore vectorStore, ReActAgent reActAgent, ConversationMemoryService memoryService, RedisShortTermMemory redisMemory, ConversationService stateService, org.springframework.ai.embedding.EmbeddingModel embeddingModel) {
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.reActAgent = reActAgent;
        this.memoryService = memoryService;
        this.redisMemory = redisMemory;
        this.stateService = stateService;
        this.embeddingModel = embeddingModel;
    }

    public Flux<String> chat(String prompt) {
        return chatClient.prompt(prompt)
                .system("你是一个判断给出题目所处年纪和章节的助手，题目如下：")
                .stream()
                .content();
    }

    public Flux<String> chatWithVectorStore(String prompt) {
        return chatClient.prompt(prompt)
                .advisors(retrievalAugmentationAdvisor)
                .stream()
                .content();
    }

    private void executeAsyncOutbound(String convId, Message userMessage, Message assistantMessage) {
        // 边界提取：仅在与外部 Redis/JDBC 交互时提取 String
        Map<String, String> userMap = Map.of("role", "user", "content", userMessage.getText());
        Map<String, String> assistMap = Map.of("role", "assistant", "content", assistantMessage.getText());

        redisMemory.addTurnAtomically(convId, userMessage, assistantMessage).subscribe();

        stateService.incrementTurn(convId).subscribe();
        stateService.updateLastActiveAt(convId).subscribe();

        // 异步归档
        memoryService.archiveRawMessage(convId, userMessage.getText(), embeddingModel.embed(userMessage.getText()), null).subscribe();
        Mono.fromCallable(() -> embeddingModel.embed(assistantMessage.getText()))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(emb -> memoryService.archiveRawMessage(convId, assistantMessage.getText(), emb, null).subscribe());
    }

    public Flux<String> chatWithAgent(String prompt) {
        return reActAgent.run(prompt)
                .map(agentEvent -> {
                    log.info("Agent Event: {}", agentEvent);
                    return agentEvent.content();
                });
    }

}