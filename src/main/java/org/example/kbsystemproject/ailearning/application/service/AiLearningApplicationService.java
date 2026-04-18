package org.example.kbsystemproject.ailearning.application.service;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.agent.ReActAgent;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionStatus;
import org.example.kbsystemproject.ailearning.domain.session.LongTermMemoryEntry;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionTurnPair;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class AiLearningApplicationService {

    private static final String LEARNING_ASSISTANT_SYSTEM_PROMPT = """
            你是学习助手。
            你必须优先依据当前会话上下文、历史对话和检索到的长期记忆回答。
            信息不足时直接说明缺失点，不要编造。
            回答以学习场景为中心，尽量给出结论、步骤、易错点和总结。
            """;

    private final ChatClient chatClient;
    private final VectorStore pgVectorStore;
    private final ReActAgent reActAgent;
    private final SessionStorageService sessionStorageService;
    private final SessionStreamService sessionStreamService;

    public AiLearningApplicationService(@Qualifier("chatClient") ChatClient chatClient,
                                        VectorStore pgVectorStore,
                                        ReActAgent reActAgent,
                                        SessionStorageService sessionStorageService,
                                        SessionStreamService sessionStreamService) {
        this.chatClient = chatClient;
        this.pgVectorStore = pgVectorStore;
        this.reActAgent = reActAgent;
        this.sessionStorageService = sessionStorageService;
        this.sessionStreamService = sessionStreamService;
    }

    public Flux<String> chat(LearningChatCommand command) {
        return chatWithAgent(command);
    }

    public Mono<Void> startChat(LearningChatCommand command) {
        return Mono.fromRunnable(() -> sessionStreamService.start(command.conversationId(), chatWithAgent(command)));
    }

    public Mono<Void> stopChat(String conversationId) {
        return Mono.fromRunnable(() -> sessionStreamService.stop(conversationId));
    }

    public Flux<String> getSessionLiveStream(String conversationId) {
        return sessionStreamService.stream(conversationId);
    }

    public Flux<String> chat(String prompt) {
        return chatClient.prompt()
                .system(LEARNING_ASSISTANT_SYSTEM_PROMPT)
                .user(prompt)
                .stream()
                .content();
    }

    public Flux<String> chatTest(String prompt) {
        return chatClient.prompt(prompt)
                .stream()
                .content();
    }

    public Flux<Document> searchSimilarity(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(10)
                .build();
        return Mono.fromCallable(() -> pgVectorStore.similaritySearch(request))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    public Flux<String> chatWithAgent(String prompt) {
        return reActAgent.run(prompt)
                .map(agentEvent -> {
                    log.info("Agent Event: {}", agentEvent);
                    return agentEvent.content();
                });
    }

    public Flux<String> chatWithAgent(LearningChatCommand command) {
        return sessionStorageService.openSession(command.toSessionOpenCommand())
                .flatMapMany(ignored -> sessionStorageService.loadSnapshot(command.conversationId(), command.prompt()))
                .flatMap(snapshot -> runAgentWithSession(command, snapshot));
    }

    private Flux<String> runAgentWithSession(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        ConversationTurn userTurn = new ConversationTurn(
                SessionMessageRole.USER,
                command.prompt(),
                OffsetDateTime.now(),
                buildUserTurnMetadata(command)
        );
        AtomicReference<String> finalAssistantContent = new AtomicReference<>("");
        StringBuilder fallbackAssistantContent = new StringBuilder();

        return reActAgent.run(buildAgentHistory(command, snapshot), command.prompt(), buildAgentBusinessContext(command, snapshot))
                .doOnNext(event -> collectAssistantContent(event.state(), event.content(), finalAssistantContent, fallbackAssistantContent))
                .map(agentEvent -> {
                    log.info("Agent Event: {}", agentEvent);
                    return agentEvent.content();
                })
                .filter(content -> content != null && !content.isBlank())
                .concatWith(Mono.defer(() -> persistTurn(
                        command,
                        userTurn,
                        resolveAssistantContent(finalAssistantContent.get(), fallbackAssistantContent.toString()),
                        snapshot
                )));
    }

    private Mono<String> persistTurn(LearningChatCommand command,
                                     ConversationTurn userTurn,
                                     String assistantContent,
                                     SessionMemorySnapshot snapshot) {
        String normalizedAssistantContent = assistantContent == null ? "" : assistantContent.trim();
        if (normalizedAssistantContent.isEmpty()) {
            return Mono.empty();
        }

        ConversationTurn assistantTurn = new ConversationTurn(
                SessionMessageRole.ASSISTANT,
                normalizedAssistantContent,
                OffsetDateTime.now(),
                Map.of()
        );

        return sessionStorageService.appendTurn(
                        command.conversationId(),
                        new SessionTurnPair(userTurn, assistantTurn),
                        command.currentTopic(),
                        buildSessionMetadata(command, snapshot)
                )
                .then(Mono.empty());
    }

    private List<org.springframework.ai.chat.messages.Message> buildAgentHistory(LearningChatCommand command,
                                                                                 SessionMemorySnapshot snapshot) {
        List<org.springframework.ai.chat.messages.Message> history = new java.util.ArrayList<>();
        history.add(new org.springframework.ai.chat.messages.SystemMessage(buildSystemPrompt(command, snapshot)));
        for (ConversationTurn turn : snapshot.shortTermMemory()) {
            history.add(toSpringMessage(turn));
        }
        return history;
    }

    private Map<String, Object> buildAgentBusinessContext(LearningChatCommand command,
                                                          SessionMemorySnapshot snapshot) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("conversationId", command.conversationId());
        context.put("userId", command.userId());
        context.put("subject", command.subject());
        context.put("sessionType", command.sessionType() == null ? "QA" : command.sessionType().name());
        context.put("learningGoal", command.learningGoal());
        context.put("currentTopic", command.currentTopic());
        context.put("turnCount", snapshot.session().turnCount());
        context.put("longTermMemory", snapshot.longTermMemory().stream()
                .map(LongTermMemoryEntry::content)
                .toList());
        return context;
    }

    private org.springframework.ai.chat.messages.Message toSpringMessage(ConversationTurn turn) {
        return switch (turn.role()) {
            case USER -> new org.springframework.ai.chat.messages.UserMessage(turn.content());
            case ASSISTANT -> new AssistantMessage(turn.content());
            case SYSTEM -> new org.springframework.ai.chat.messages.SystemMessage(turn.content());
        };
    }

    private void collectAssistantContent(org.example.kbsystemproject.ailearning.domain.AgentState state,
                                         String content,
                                         AtomicReference<String> finalAssistantContent,
                                         StringBuilder fallbackAssistantContent) {
        if (content == null || content.isBlank()) {
            return;
        }
        switch (state) {
            case FINISHED -> finalAssistantContent.set(content);
            case TOKEN -> fallbackAssistantContent.append(content);
            default -> {
            }
        }
    }

    private String resolveAssistantContent(String finalAssistantContent, String fallbackAssistantContent) {
        if (finalAssistantContent != null && !finalAssistantContent.isBlank()) {
            return finalAssistantContent;
        }
        return fallbackAssistantContent == null ? "" : fallbackAssistantContent.trim();
    }

    private String buildSystemPrompt(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        StringBuilder builder = new StringBuilder(LEARNING_ASSISTANT_SYSTEM_PROMPT);
        builder.append("\n当前会话信息：\n");
        builder.append("- conversationId: ").append(command.conversationId()).append('\n');
        builder.append("- userId: ").append(defaultText(command.userId())).append('\n');
        builder.append("- subject: ").append(defaultText(command.subject())).append('\n');
        builder.append("- sessionType: ").append(command.sessionType() == null ? "QA" : command.sessionType().name()).append('\n');
        builder.append("- learningGoal: ").append(defaultText(command.learningGoal())).append('\n');
        builder.append("- currentTopic: ").append(defaultText(command.currentTopic())).append('\n');
        builder.append("- status: ").append(snapshot.session().status() == null ? LearningSessionStatus.ACTIVE.name() : snapshot.session().status().name()).append('\n');
        builder.append("\n近期对话：\n");
        builder.append(formatShortTermMemory(snapshot.shortTermMemory()));
        builder.append("\n长期记忆：\n");
        builder.append(formatLongTermMemory(snapshot.longTermMemory()));
        return builder.toString();
    }

    private String formatShortTermMemory(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "无\n";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : turns) {
            builder.append("- ")
                    .append(turn.role().name())
                    .append(": ")
                    .append(turn.content())
                    .append('\n');
        }
        return builder.toString();
    }

    private String formatLongTermMemory(List<LongTermMemoryEntry> memories) {
        if (memories == null || memories.isEmpty()) {
            return "无\n";
        }
        StringBuilder builder = new StringBuilder();
        for (LongTermMemoryEntry memory : memories) {
            builder.append("- [")
                    .append(memory.memoryType())
                    .append("] ")
                    .append(memory.content())
                    .append('\n');
        }
        return builder.toString();
    }

    private Map<String, Object> buildSessionMetadata(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subject", defaultText(command.subject()));
        metadata.put("sessionType", command.sessionType() == null ? "QA" : command.sessionType().name());
        metadata.put("currentTopic", defaultText(command.currentTopic()));
        metadata.put("sessionStatus", snapshot.session().status().name());
        return metadata;
    }

    private Map<String, Object> buildUserTurnMetadata(LearningChatCommand command) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (command.subject() != null && !command.subject().isBlank()) {
            metadata.put("subject", command.subject());
        }
        if (command.currentTopic() != null && !command.currentTopic().isBlank()) {
            metadata.put("currentTopic", command.currentTopic());
        }
        return metadata;
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }
}
