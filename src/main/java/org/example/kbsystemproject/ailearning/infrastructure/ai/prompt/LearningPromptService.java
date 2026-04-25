package org.example.kbsystemproject.ailearning.infrastructure.ai.prompt;

import org.example.kbsystemproject.ailearning.application.chat.LearningChatCommand;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.example.kbsystemproject.ailearning.domain.profile.LearningProfileContext;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionStatus;
import org.example.kbsystemproject.ailearning.domain.session.LongTermMemoryEntry;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionTopicBlock;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LearningPromptService {

    private static final int RAG_MAX_DOC_CONTENT_LENGTH = 500;

    private static final String LEARNING_ASSISTANT_SYSTEM_PROMPT = """
            你是一个通用学习助手。
            回答时优先基于当前会话上下文、历史对话和长期记忆。
            信息不足时直接说明缺失点，不要编造。
            输出尽量清晰、可执行，并与用户当前任务保持一致。
            """;

    public List<Message> buildAgentHistory(LearningChatCommand command,
                                           SessionMemorySnapshot snapshot,
                                           IntentDecision decision,
                                           List<Document> retrievedDocs,
                                           LearningProfileContext profileContext) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage(buildSystemPrompt(command, snapshot, decision, retrievedDocs, profileContext)));
        for (ConversationTurn turn : snapshot.shortTermMemory()) {
            history.add(toSpringMessage(turn));
        }
        return history;
    }

    public List<Message> buildDirectMessages(LearningChatCommand command,
                                             SessionMemorySnapshot snapshot,
                                             IntentDecision decision,
                                             List<Document> retrievedDocs,
                                             LearningProfileContext profileContext) {
        List<Message> messages = new ArrayList<>(buildAgentHistory(command, snapshot, decision, retrievedDocs, profileContext));
        messages.add(new UserMessage(command.prompt()));
        return messages;
    }

    public Map<String, Object> buildAgentBusinessContext(LearningChatCommand command,
                                                         SessionMemorySnapshot snapshot,
                                                         IntentDecision decision,
                                                         List<Document> retrievedDocs,
                                                         LearningProfileContext profileContext) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("conversationId", command.conversationId());
        context.put("requestId", command.requestId());
        context.put("userId", command.userId());
        context.put("subject", command.subject());
        context.put("sessionType", decision.resolvedSessionType().name());
        context.put("learningGoal", command.learningGoal());
        context.put("currentTopic", command.currentTopic());
        context.put("turnCount", snapshot.session().turnCount());
        context.put("intentType", decision.intentType().name());
        context.put("executionMode", decision.executionMode().name());
        context.put("longTermMemory", snapshot.longTermMemory().stream()
                .map(LongTermMemoryEntry::content)
                .toList());
        context.put("retrievedDocCount", retrievedDocs.size());
        context.put("retrievedKnowledge", retrievedDocs.stream()
                .map(this::formatDocumentForContext)
                .toList());
        context.put("learningProfileGoal", profileContext.learningGoal());
        context.put("learningProfileStyle", profileContext.preferredStyle());
        context.put("sessionFocusTopic", profileContext.currentTopic());
        return context;
    }

    public Map<String, Object> buildUserTurnMetadata(LearningChatCommand command, IntentDecision decision) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", command.requestId());
        if (command.subject() != null && !command.subject().isBlank()) {
            metadata.put("subject", command.subject());
        }
        if (command.currentTopic() != null && !command.currentTopic().isBlank()) {
            metadata.put("currentTopic", command.currentTopic());
        }
        metadata.putAll(decision.toMetadata());
        return metadata;
    }

    public Map<String, Object> buildAssistantTurnMetadata(LearningChatCommand command, IntentDecision decision) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", command.requestId());
        metadata.putAll(decision.toMetadata());
        return metadata;
    }

    public Map<String, Object> buildSessionMetadata(LearningChatCommand command,
                                                    SessionMemorySnapshot snapshot,
                                                    IntentDecision decision,
                                                    List<Document> retrievedDocs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("requestId", command.requestId());
        metadata.put("subject", defaultText(command.subject()));
        metadata.put("sessionType", decision.resolvedSessionType().name());
        metadata.put("currentTopic", defaultText(command.currentTopic()));
        metadata.put("sessionStatus", snapshot.session().status() == null
                ? LearningSessionStatus.ACTIVE.name()
                : snapshot.session().status().name());
        metadata.put("retrievedDocCount", retrievedDocs.size());
        if (!retrievedDocs.isEmpty()) {
            metadata.put("retrievedSources", retrievedDocs.stream()
                    .map(this::formatDocumentSource)
                    .toList());
        }
        metadata.putAll(decision.toMetadata());
        return metadata;
    }

    private Message toSpringMessage(ConversationTurn turn) {
        return switch (turn.role()) {
            case USER -> new UserMessage(turn.content());
            case ASSISTANT -> new AssistantMessage(turn.content());
            case SYSTEM -> new SystemMessage(turn.content());
        };
    }

    private String buildSystemPrompt(LearningChatCommand command,
                                     SessionMemorySnapshot snapshot,
                                     IntentDecision decision,
                                     List<Document> retrievedDocs,
                                     LearningProfileContext profileContext) {
        StringBuilder builder = new StringBuilder(LEARNING_ASSISTANT_SYSTEM_PROMPT);
        builder.append("\n当前会话信息:\n");
        builder.append("- conversationId: ").append(command.conversationId()).append('\n');
        builder.append("- requestId: ").append(command.requestId()).append('\n');
        builder.append("- userId: ").append(defaultText(command.userId())).append('\n');
        builder.append("- subject: ").append(defaultText(command.subject())).append('\n');
        builder.append("- sessionType: ").append(decision.resolvedSessionType().name()).append('\n');
        builder.append("- intentType: ").append(decision.intentType().name()).append('\n');
        builder.append("- executionMode: ").append(decision.executionMode().name()).append('\n');
        builder.append("- learningGoal: ").append(defaultText(command.learningGoal())).append('\n');
        builder.append("- currentTopic: ").append(defaultText(command.currentTopic())).append('\n');
        builder.append("- status: ").append(snapshot.session().status() == null
                ? LearningSessionStatus.ACTIVE.name()
                : snapshot.session().status().name()).append('\n');
        builder.append("\n任务要求:\n");
        builder.append(buildIntentInstruction(decision));
        builder.append("\n用户个性化信息:\n");
        builder.append(formatLearningProfileContext(profileContext));
        builder.append("\n当前主题块摘要:\n");
        builder.append(formatActiveTopicBlock(snapshot.activeTopicBlock()));
        builder.append("\n长期记忆:\n");
        builder.append(formatLongTermMemory(snapshot.longTermMemory()));
        builder.append("\n检索资料:\n");
        builder.append(formatRetrievedKnowledge(retrievedDocs, decision.needRetrieval()));
        return builder.toString();
    }

    private String buildIntentInstruction(IntentDecision decision) {
        return switch (decision.intentType()) {
            case GENERAL_QA -> "- 当前任务是一般问答，请先澄清问题，再给出结论和必要解释。\n";
            case QUESTION_EXPLANATION -> "- 当前任务是内容讲解，请给出思路、步骤、关键点和简短总结。\n";
            case GENERATE_EXERCISE -> "- 当前任务是生成练习，请输出题目、参考答案、解析和考察点。\n";
            case WRONG_QUESTION_ANALYSIS -> "- 当前任务是问题分析，请指出原因、正确思路和改进建议。\n";
            case REVIEW_SUMMARY -> "- 当前任务是复习总结，请提炼重点、关联关系和建议顺序。\n";
            case STUDY_PLAN -> "- 当前任务是学习规划，请给出分阶段目标、步骤和检查点。\n";
            case FOLLOW_UP -> "- 当前任务是追问补充，请延续上文，优先回答当前未解决的问题。\n";
            case OTHER -> "- 当前任务未明确，请保持中性、清晰和可执行的回答风格。\n";
        };
    }

    private String formatLearningProfileContext(LearningProfileContext profileContext) {
        if (profileContext == null || profileContext.isEmpty()) {
            return "无\n";
        }
        StringBuilder builder = new StringBuilder();
        if (profileContext.learningGoal() != null && !profileContext.learningGoal().isBlank()) {
            builder.append("- 学习目标: ").append(profileContext.learningGoal()).append('\n');
        }
        if (profileContext.preferredStyle() != null && !profileContext.preferredStyle().isBlank()) {
            builder.append("- 讲解偏好: ").append(profileContext.preferredStyle()).append('\n');
        }
        if (profileContext.preferredLanguage() != null && !profileContext.preferredLanguage().isBlank()) {
            builder.append("- 常用语言: ").append(profileContext.preferredLanguage()).append('\n');
        }
        if (profileContext.currentTopic() != null && !profileContext.currentTopic().isBlank()) {
            builder.append("- 当前主题: ").append(profileContext.currentTopic()).append('\n');
        }
        if (profileContext.weakPoints() != null && !profileContext.weakPoints().isEmpty()) {
            builder.append("- 已记录薄弱点: ").append(String.join(", ", profileContext.weakPoints())).append('\n');
        }
        if (profileContext.recentTopics() != null && !profileContext.recentTopics().isEmpty()) {
            builder.append("- 最近主题: ").append(String.join(", ", profileContext.recentTopics())).append('\n');
        }
        return builder.isEmpty() ? "无\n" : builder.toString();
    }

    private String formatActiveTopicBlock(SessionTopicBlock activeTopicBlock) {
        if (activeTopicBlock == null || activeTopicBlock.topic() == null || activeTopicBlock.topic().isBlank()) {
            return "无\n";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("- 主题: ").append(activeTopicBlock.topic()).append('\n');
        builder.append("- 覆盖轮次: ").append(activeTopicBlock.startTurn()).append('-').append(activeTopicBlock.lastTurn()).append('\n');
        if (activeTopicBlock.summary() != null && !activeTopicBlock.summary().isBlank()) {
            builder.append(activeTopicBlock.summary()).append('\n');
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

    private String formatRetrievedKnowledge(List<Document> retrievedDocs, boolean retrievalRequested) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return retrievalRequested
                    ? "未检索到可用知识库片段；回答时可结合会话上下文，但要明确说明不确定处。\n"
                    : "当前任务未启用知识库检索。\n";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document document = retrievedDocs.get(i);
            builder.append("- 资料").append(i + 1).append(" | 来源: ")
                    .append(formatDocumentSource(document))
                    .append('\n');
            builder.append("  内容: ")
                    .append(limitText(document.getText(), RAG_MAX_DOC_CONTENT_LENGTH))
                    .append('\n');
        }
        return builder.toString();
    }

    private String formatDocumentForContext(Document document) {
        return "来源: " + formatDocumentSource(document) + "\n内容: "
                + limitText(document.getText(), RAG_MAX_DOC_CONTENT_LENGTH);
    }

    private String formatDocumentSource(Document document) {
        if (document.getMetadata() == null || document.getMetadata().isEmpty()) {
            return document.getId() == null ? "unknown" : document.getId();
        }
        Object filename = document.getMetadata().get("filename");
        if (filename != null) {
            return String.valueOf(filename);
        }
        Object source = document.getMetadata().get("source");
        if (source != null) {
            return String.valueOf(source);
        }
        Object subject = document.getMetadata().get("subject");
        Object chapter = document.getMetadata().get("chapter");
        if (subject != null || chapter != null) {
            return "%s/%s".formatted(
                    subject == null ? "unknown-subject" : subject,
                    chapter == null ? "unknown-chapter" : chapter
            );
        }
        return document.getId() == null ? "unknown" : document.getId();
    }

    private String limitText(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "无";
        }
        String normalizedText = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalizedText.length() <= maxLength) {
            return normalizedText;
        }
        return normalizedText.substring(0, maxLength) + "...";
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }
}

