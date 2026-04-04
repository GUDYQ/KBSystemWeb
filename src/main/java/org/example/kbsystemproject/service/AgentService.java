package org.example.kbsystemproject.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.base.ai.agent.ReActAgent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
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
    @Getter
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private ReActAgent reActAgent;


    public AgentService(RetrievalAugmentationAdvisor retrievalAugmentationAdvisor, ChatClient chatClient,
                        VectorStore vectorStore, ReActAgent reActAgent) {
        this.retrievalAugmentationAdvisor = retrievalAugmentationAdvisor;
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.reActAgent = reActAgent;
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

    public Flux<String> chatWithAgent(String prompt) {
        return reActAgent.run(prompt)
                .map(agentEvent -> {
                    log.info("Agent Event: {}", agentEvent);
                    return agentEvent.content();
                });
    }

}