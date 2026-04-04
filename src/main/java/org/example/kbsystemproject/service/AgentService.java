package org.example.kbsystemproject.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
//import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentService {

    private static final int MAX_CONTEXT_DOCS = 4;

    private final RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public AgentService(RetrievalAugmentationAdvisor retrievalAugmentationAdvisor, ChatClient chatClient,
                        VectorStore vectorStore) {
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    public Flux<String> chat(String prompt) {
        return chatClient.prompt(prompt)
                .stream()
                .content();
    }

    public Flux<String> chatWithVectorStore(String prompt) {
        return chatClient.prompt(prompt)
                .advisors(retrievalAugmentationAdvisor)
                .stream()
                .content();
    }

}