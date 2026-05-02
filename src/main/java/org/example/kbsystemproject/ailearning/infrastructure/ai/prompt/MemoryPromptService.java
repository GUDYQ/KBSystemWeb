package org.example.kbsystemproject.ailearning.infrastructure.ai.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionRecord;
import org.example.kbsystemproject.ailearning.domain.session.MemoryCandidate;
import org.example.kbsystemproject.ailearning.domain.session.SessionWhiteboard;
import org.example.kbsystemproject.ailearning.domain.session.ToolMemoryEntry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemoryPromptService {

    private final ChatClient chatClient;
    private final Scheduler aiBlockingScheduler;
    private final ObjectMapper objectMapper;

    public MemoryPromptService(@Qualifier("chatClient") ChatClient chatClient,
                               @Qualifier("aiBlockingScheduler") Scheduler aiBlockingScheduler,
                               ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.aiBlockingScheduler = aiBlockingScheduler;
        this.objectMapper = objectMapper;
    }

    public CompressionPromptInput buildCompressionInput(LearningSessionRecord session,
                                                        int turnIndex,
                                                        List<ConversationTurn> recentTurns,
                                                        SessionWhiteboard existingWhiteboard,
                                                        List<ToolMemoryEntry> recentToolMemories) {
        return new CompressionPromptInput(
                session,
                turnIndex,
                recentTurns == null ? List.of() : recentTurns,
                existingWhiteboard,
                recentToolMemories == null ? List.of() : recentToolMemories
        );
    }

    public Mono<CompressionResult> compress(CompressionPromptInput input) {
        return Mono.fromCallable(() -> chatClient.prompt()
                        .system("""
                                你是会话长期记忆整理器。
                                你需要基于最近对话、已有 whiteboard 和工具结果，输出一份 JSON。
                                规则：
                                1. 只输出一个 JSON 对象，不要加解释。
                                2. 只保留当前会话连续性真正需要的信息。
                                3. memory_candidates 只保留未来新会话仍然值得保留的稳定信息：明确目标、稳定偏好、长期约束、已确认决定、强结构化事实。
                                4. 普通追问、一次性问题、临时困惑、当前主题细节、阶段性展开、可以从近期上下文恢复的信息，不要放进 memory_candidates。
                                5. behavioral_pattern 和 compression_inferred 只有在 evidence 至少有 2 条独立证据时才允许输出；否则宁可不输出。
                                6. memory_candidates 最多输出 2 条；拿不准就输出空数组。
                                7. soft_hints 字段表示软提示/倾向，不是必须硬执行的系统规则。
                                8. 不确定时宁可留空，也不要编造。
                                9. confidence 打分必须非常保守：
                                   - 0.95~1.00: 用户明确表达，或工具/系统结构化结果，且几乎不会变化
                                   - 0.90~0.94: 高确定的稳定偏好或长期约束，证据充分
                                   - 0.80~0.89: 有一定价值但还不够稳定，通常不要进入 memory_candidates
                                   - 0.00~0.79: 视为不应沉淀长期记忆
                                """)
                        .user("""
                                conversationId: %s
                                userId: %s
                                subject: %s
                                learningGoal: %s
                                currentTopic: %s
                                conversationMode: %s
                                turnIndex: %d

                                existingWhiteboard:
                                %s

                                recentToolMemories:
                                %s

                                recentTurns:
                                %s

                                输出 JSON 结构：
                                {
                                  "current_focus": "",
                                  "user_goal": "",
                                  "soft_hints": [],
                                  "decisions": [],
                                  "open_questions": [],
                                  "recent_tool_findings": [],
                                  "continuity_state": "CONTINUE|BRANCH|SHIFT|UNCERTAIN",
                                  "continuity_confidence": 0.0,
                                  "raw_summary": "",
                                  "memory_candidates": [
                                    {
                                      "scope": "session|user|subject",
                                      "type": "goal|preference|constraint|fact|plan|decision",
                                      "memory_key": "",
                                      "memory_value": "",
                                      "confidence": 0.0,
                                      "source_type": "user_explicit|tool/system_structured|behavioral_pattern|compression_inferred",
                                      "evidence": []
                                    }
                                  ]
                                }
                                """.formatted(
                                input.session().conversationId(),
                                defaultText(input.session().userId()),
                                defaultText(input.session().subject()),
                                defaultText(input.session().learningGoal()),
                                defaultText(input.session().currentTopic()),
                                input.session().normalizedConversationMode().name(),
                                input.turnIndex(),
                                formatWhiteboard(input.existingWhiteboard()),
                                formatToolMemories(input.recentToolMemories()),
                                formatRecentTurns(input.recentTurns())
                        ))
                        .call()
                        .content())
                .subscribeOn(aiBlockingScheduler)
                .map(this::parseCompressionResult)
                .onErrorReturn(fallbackResult(input));
    }

    public CompressionResult parseCompressionResult(String modelOutput) {
        if (modelOutput == null || modelOutput.isBlank()) {
            return CompressionResult.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(modelOutput));
            return new CompressionResult(
                    readText(root, "current_focus"),
                    readText(root, "user_goal"),
                    readStringList(readFirstPresent(root, "soft_hints", "constraints")),
                    readStringList(root.get("decisions")),
                    readStringList(root.get("open_questions")),
                    readStringList(root.get("recent_tool_findings")),
                    readText(root, "continuity_state"),
                    readDouble(root, "continuity_confidence"),
                    readText(root, "raw_summary"),
                    readCandidates(root.get("memory_candidates"))
            );
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse memory compression result", error);
        }
    }

    public CompressionResult fallbackResult(CompressionPromptInput input) {
        String currentFocus = input.existingWhiteboard() != null && input.existingWhiteboard().currentFocus() != null
                ? input.existingWhiteboard().currentFocus()
                : input.session().currentTopic();
        String rawSummary = input.recentTurns().isEmpty()
                ? null
                : input.recentTurns().stream()
                .skip(Math.max(0, input.recentTurns().size() - 2))
                .map(turn -> turn.role().name() + ": " + limit(turn.content(), 100))
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
        return new CompressionResult(
                currentFocus,
                input.session().learningGoal(),
                List.of(),
                List.of(),
                List.of(),
                input.recentToolMemories().stream().map(this::formatToolMemory).toList(),
                "CONTINUE",
                0.3D,
                rawSummary,
                List.of()
        );
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return text;
        }
        return text.substring(start, end + 1);
    }

    private String readText(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Double readDouble(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asDouble();
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = item == null ? null : item.asText();
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        });
        return values.stream().distinct().limit(20).toList();
    }

    private List<CandidateDraft> readCandidates(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<CandidateDraft> drafts = new ArrayList<>();
        node.forEach(item -> {
            if (item == null || !item.isObject()) {
                return;
            }
            String memoryValue = readText(item, "memory_value");
            if (memoryValue == null || memoryValue.isBlank()) {
                return;
            }
            drafts.add(new CandidateDraft(
                    readText(item, "scope"),
                    readText(item, "type"),
                    readText(item, "memory_key"),
                    memoryValue,
                    readDouble(item, "confidence"),
                    readText(item, "source_type"),
                    readStringList(item.get("evidence")),
                    Map.of()
            ));
        });
        return List.copyOf(drafts);
    }

    private String formatWhiteboard(SessionWhiteboard whiteboard) {
        if (whiteboard == null) {
            return "{}";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("currentFocus", whiteboard.currentFocus());
        data.put("userGoal", whiteboard.userGoal());
        data.put("softHints", whiteboard.constraints());
        data.put("decisions", whiteboard.decisions());
        data.put("openQuestions", whiteboard.openQuestions());
        data.put("recentToolFindings", whiteboard.recentToolFindings());
        data.put("continuityState", whiteboard.continuityState());
        data.put("continuityConfidence", whiteboard.continuityConfidence());
        data.put("rawSummary", whiteboard.rawSummary());
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception error) {
            return "{}";
        }
    }

    private String formatToolMemories(List<ToolMemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "[]";
        }
        return entries.stream()
                .map(this::formatToolMemory)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("[]");
    }

    private String formatToolMemory(ToolMemoryEntry entry) {
        StringBuilder builder = new StringBuilder();
        if (entry.turnIndex() != null) {
            builder.append("turn=").append(entry.turnIndex()).append(' ');
        }
        builder.append(defaultText(entry.toolName()));
        if (entry.summary() != null && !entry.summary().isBlank()) {
            builder.append(": ").append(limit(entry.summary(), 120));
        }
        return builder.toString();
    }

    private String formatRecentTurns(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : turns) {
            builder.append(turn.role().name())
                    .append(": ")
                    .append(limit(turn.content(), 300))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private String defaultText(String value) {
        return value == null || value.isBlank() ? "N/A" : value.trim();
    }

    private JsonNode readFirstPresent(JsonNode root, String preferredField, String fallbackField) {
        JsonNode preferred = root.get(preferredField);
        if (preferred != null && !preferred.isNull()) {
            return preferred;
        }
        return root.get(fallbackField);
    }

    private String limit(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    public record CompressionPromptInput(
            LearningSessionRecord session,
            int turnIndex,
            List<ConversationTurn> recentTurns,
            SessionWhiteboard existingWhiteboard,
            List<ToolMemoryEntry> recentToolMemories
    ) {
    }

    public record CompressionResult(
            String currentFocus,
            String userGoal,
            List<String> softHints,
            List<String> decisions,
            List<String> openQuestions,
            List<String> recentToolFindings,
            String continuityState,
            Double continuityConfidence,
            String rawSummary,
            List<CandidateDraft> memoryCandidates
    ) {
        public static CompressionResult empty() {
            return new CompressionResult(null, null, List.of(), List.of(), List.of(), List.of(), null, null, null, List.of());
        }

        public List<MemoryCandidate> toMemoryCandidates(String userId, String conversationId, int turnIndex) {
            if (memoryCandidates == null || memoryCandidates.isEmpty()) {
                return List.of();
            }
            OffsetDateTime now = OffsetDateTime.now();
            return memoryCandidates.stream()
                    .map(candidate -> new MemoryCandidate(
                            null,
                            userId,
                            conversationId,
                            turnIndex,
                            candidate.scope(),
                            candidate.type(),
                            candidate.memoryKey(),
                            candidate.memoryValue(),
                            candidate.confidence(),
                            candidate.sourceType(),
                            null,
                            "pending",
                            candidate.evidence(),
                            candidate.metadata(),
                            now
                    ))
                    .toList();
        }
    }

    public record CandidateDraft(
            String scope,
            String type,
            String memoryKey,
            String memoryValue,
            Double confidence,
            String sourceType,
            List<String> evidence,
            Map<String, Object> metadata
    ) {
    }
}
