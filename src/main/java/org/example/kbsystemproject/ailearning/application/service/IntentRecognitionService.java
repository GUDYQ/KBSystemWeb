package org.example.kbsystemproject.ailearning.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.domain.intent.ExecutionMode;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.example.kbsystemproject.ailearning.domain.intent.IntentSource;
import org.example.kbsystemproject.ailearning.domain.intent.IntentType;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.config.IntentRecognitionProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;

@Service
public class IntentRecognitionService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final IntentRecognitionProperties properties;

    public IntentRecognitionService(@Qualifier("chatClient") ChatClient chatClient,
                                    ObjectMapper objectMapper,
                                    IntentRecognitionProperties properties) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Mono<IntentDecision> recognize(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        if (!properties.isEnabled()) {
            return Mono.just(disabledDecision(command, snapshot));
        }

        IntentDecision ruleDecision = resolveByRules(command, snapshot);
        if (ruleDecision != null) {
            return Mono.just(ruleDecision);
        }

        IntentDecision hintDecision = resolveByUserHint(command, snapshot);
        if (hintDecision != null) {
            return Mono.just(hintDecision);
        }

        if (!properties.isLlmFallbackEnabled()) {
            return Mono.just(fallbackDecision(command, snapshot, "default-general-qa"));
        }

        return classifyWithLlm(command, snapshot)
                .filter(decision -> decision.confidence() >= properties.getLlmConfidenceThreshold())
                .onErrorResume(error -> Mono.empty())
                .switchIfEmpty(Mono.just(fallbackDecision(command, snapshot, "llm-fallback-default")));
    }

    private IntentDecision resolveByRules(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        String normalizedPrompt = normalize(command.prompt());
        if (normalizedPrompt.isBlank()) {
            return null;
        }

        if (matchesWrongQuestionAnalysis(normalizedPrompt)) {
            return decision(
                    IntentType.WRONG_QUESTION_ANALYSIS,
                    resolveSessionType(command, snapshot, LearningSessionType.REVIEW),
                    ExecutionMode.AGENT,
                    0.95D,
                    IntentSource.RULE,
                    true,
                    true,
                    "wrong-question-analysis-keyword"
            );
        }

        if (matchesExerciseGeneration(normalizedPrompt)) {
            return decision(
                    IntentType.GENERATE_EXERCISE,
                    resolveSessionType(command, snapshot, LearningSessionType.EXERCISE),
                    ExecutionMode.AGENT,
                    0.96D,
                    IntentSource.RULE,
                    true,
                    true,
                    "exercise-generation-keyword"
            );
        }

        if (matchesReviewSummary(normalizedPrompt)) {
            return decision(
                    IntentType.REVIEW_SUMMARY,
                    resolveSessionType(command, snapshot, LearningSessionType.REVIEW),
                    ExecutionMode.AGENT,
                    0.92D,
                    IntentSource.RULE,
                    true,
                    true,
                    "review-summary-keyword"
            );
        }

        if (matchesStudyPlan(normalizedPrompt)) {
            return decision(
                    IntentType.STUDY_PLAN,
                    resolveSessionType(command, snapshot, LearningSessionType.REVIEW),
                    ExecutionMode.AGENT,
                    0.9D,
                    IntentSource.RULE,
                    true,
                    true,
                    "study-plan-keyword"
            );
        }

        if (matchesQuestionExplanation(normalizedPrompt)) {
            return decision(
                    IntentType.QUESTION_EXPLANATION,
                    resolveSessionType(command, snapshot, LearningSessionType.QA),
                    ExecutionMode.DIRECT,
                    0.88D,
                    IntentSource.RULE,
                    true,
                    false,
                    "question-explanation-keyword"
            );
        }

        if (matchesFollowUp(normalizedPrompt, snapshot.shortTermMemory())) {
            return decision(
                    IntentType.FOLLOW_UP,
                    resolveSessionType(command, snapshot, LearningSessionType.QA),
                    ExecutionMode.DIRECT,
                    0.82D,
                    IntentSource.RULE,
                    true,
                    false,
                    "follow-up-pattern"
            );
        }

        return null;
    }

    private IntentDecision resolveByUserHint(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        LearningSessionType hint = command.sessionType();
        if (hint == null) {
            return null;
        }
        return switch (hint) {
            case EXERCISE -> decision(
                    IntentType.GENERATE_EXERCISE,
                    LearningSessionType.EXERCISE,
                    ExecutionMode.AGENT,
                    0.72D,
                    IntentSource.USER_HINT,
                    true,
                    true,
                    "session-type-hint"
            );
            case REVIEW -> decision(
                    IntentType.REVIEW_SUMMARY,
                    LearningSessionType.REVIEW,
                    ExecutionMode.AGENT,
                    0.72D,
                    IntentSource.USER_HINT,
                    true,
                    true,
                    "session-type-hint"
            );
            case QA -> decision(
                    IntentType.GENERAL_QA,
                    LearningSessionType.QA,
                    ExecutionMode.DIRECT,
                    0.7D,
                    IntentSource.USER_HINT,
                    true,
                    false,
                    "session-type-hint"
            );
        };
    }

    private Mono<IntentDecision> classifyWithLlm(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        String history = summarizeHistory(snapshot.shortTermMemory());
        String rawSessionType = command.sessionType() == null
                ? snapshot.session().sessionType().name()
                : command.sessionType().name();

        return Mono.fromCallable(() -> chatClient.prompt()
                        .system("""
                                你是一个意图识别器。
                                任务：根据学习场景问题识别本轮用户意图，并只返回 JSON。
                                可选 intentType:
                                GENERAL_QA, QUESTION_EXPLANATION, GENERATE_EXERCISE,
                                WRONG_QUESTION_ANALYSIS, REVIEW_SUMMARY, STUDY_PLAN,
                                FOLLOW_UP, OTHER
                                可选 executionMode: DIRECT, AGENT
                                可选 resolvedSessionType: QA, EXERCISE, REVIEW
                                只输出单个 JSON 对象，不要输出 markdown，不要解释。
                                """)
                        .user("""
                                sessionTypeHint: %s
                                subject: %s
                                currentTopic: %s
                                prompt: %s
                                recentHistory:
                                %s

                                返回格式:
                                {
                                  "intentType": "...",
                                  "resolvedSessionType": "...",
                                  "executionMode": "...",
                                  "confidence": 0.0,
                                  "needRetrieval": true,
                                  "needToolCall": false,
                                  "reason": "..."
                                }
                                """.formatted(
                                rawSessionType,
                                safeText(command.subject()),
                                safeText(command.currentTopic()),
                                safeText(command.prompt()),
                                history
                        ))
                        .call()
                        .content())
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::parseLlmDecision)
                .map(decision -> ensureResolvedSessionType(decision, command, snapshot));
    }

    private IntentDecision parseLlmDecision(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalStateException("Intent classifier returned empty content");
        }
        try {
            JsonNode root = objectMapper.readTree(rawContent);
            return decision(
                    parseIntentType(root.path("intentType").asText(null)),
                    parseSessionType(root.path("resolvedSessionType").asText(null), LearningSessionType.QA),
                    parseExecutionMode(root.path("executionMode").asText(null)),
                    normalizeConfidence(root.path("confidence").asDouble(0.0D)),
                    IntentSource.LLM,
                    root.path("needRetrieval").asBoolean(true),
                    root.path("needToolCall").asBoolean(false),
                    root.path("reason").asText("llm")
            );
        } catch (Exception error) {
            throw new IllegalStateException("Failed to parse intent decision JSON", error);
        }
    }

    private IntentDecision ensureResolvedSessionType(IntentDecision decision,
                                                     LearningChatCommand command,
                                                     SessionMemorySnapshot snapshot) {
        LearningSessionType sessionType = decision.resolvedSessionType();
        if (sessionType != null) {
            return decision;
        }
        return new IntentDecision(
                decision.intentType(),
                resolveSessionType(command, snapshot, LearningSessionType.QA),
                decision.executionMode(),
                decision.confidence(),
                decision.source(),
                decision.needRetrieval(),
                decision.needToolCall(),
                decision.reason()
        );
    }

    private IntentDecision fallbackDecision(LearningChatCommand command,
                                            SessionMemorySnapshot snapshot,
                                            String reason) {
        return decision(
                IntentType.GENERAL_QA,
                resolveSessionType(command, snapshot, LearningSessionType.QA),
                ExecutionMode.DIRECT,
                0.55D,
                IntentSource.FALLBACK,
                true,
                false,
                reason
        );
    }

    private IntentDecision disabledDecision(LearningChatCommand command,
                                            SessionMemorySnapshot snapshot) {
        return decision(
                IntentType.GENERAL_QA,
                resolveSessionType(command, snapshot, LearningSessionType.QA),
                ExecutionMode.DIRECT,
                1.0D,
                IntentSource.FALLBACK,
                false,
                false,
                "intent-recognition-disabled"
        );
    }

    private IntentDecision decision(IntentType intentType,
                                    LearningSessionType sessionType,
                                    ExecutionMode executionMode,
                                    double confidence,
                                    IntentSource source,
                                    boolean needRetrieval,
                                    boolean needToolCall,
                                    String reason) {
        return new IntentDecision(
                intentType,
                sessionType,
                executionMode,
                normalizeConfidence(confidence),
                source,
                needRetrieval,
                needToolCall,
                reason
        );
    }

    private boolean matchesWrongQuestionAnalysis(String prompt) {
        return containsAny(prompt, "错题", "做错", "为什么错", "错因", "错误原因", "帮我分析这题为什么错", "错在哪里");
    }

    private boolean matchesExerciseGeneration(String prompt) {
        return containsAny(prompt, "出题", "练习题", "习题", "测试我", "来几道题", "帮我生成题", "刷题", "模拟题");
    }

    private boolean matchesReviewSummary(String prompt) {
        return containsAny(prompt, "复习", "总结", "帮我回顾", "知识梳理", "考前回顾", "整理重点", "复盘");
    }

    private boolean matchesStudyPlan(String prompt) {
        return containsAny(prompt, "学习计划", "学习路径", "怎么学", "复习计划", "安排一下", "规划");
    }

    private boolean matchesQuestionExplanation(String prompt) {
        return containsAny(prompt, "讲解", "解析", "这道题", "这题", "怎么做", "思路", "推导", "步骤");
    }

    private boolean matchesFollowUp(String prompt, List<ConversationTurn> shortTermMemory) {
        if (shortTermMemory == null || shortTermMemory.isEmpty()) {
            return false;
        }
        return prompt.length() <= properties.getFollowUpPromptLengthThreshold()
                || containsAny(prompt, "继续", "展开", "详细一点", "再说一下", "上一题", "上一步", "这个", "那个");
    }

    private LearningSessionType resolveSessionType(LearningChatCommand command,
                                                   SessionMemorySnapshot snapshot,
                                                   LearningSessionType defaultValue) {
        if (command.sessionType() != null) {
            return command.sessionType();
        }
        if (snapshot != null && snapshot.session() != null && snapshot.session().sessionType() != null) {
            return snapshot.session().sessionType();
        }
        return defaultValue;
    }

    private IntentType parseIntentType(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return IntentType.OTHER;
        }
        try {
            return IntentType.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return IntentType.OTHER;
        }
    }

    private LearningSessionType parseSessionType(String rawValue, LearningSessionType defaultValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }
        try {
            return LearningSessionType.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return defaultValue;
        }
    }

    private ExecutionMode parseExecutionMode(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return ExecutionMode.DIRECT;
        }
        try {
            return ExecutionMode.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return ExecutionMode.DIRECT;
        }
    }

    private double normalizeConfidence(double confidence) {
        if (confidence < 0.0D) {
            return 0.0D;
        }
        if (confidence > 1.0D) {
            return 1.0D;
        }
        return confidence;
    }

    private String summarizeHistory(List<ConversationTurn> shortTermMemory) {
        if (shortTermMemory == null || shortTermMemory.isEmpty()) {
            return "none";
        }
        return shortTermMemory.stream()
                .skip(Math.max(0, shortTermMemory.size() - 6))
                .map(turn -> turn.role().name() + ": " + turn.content())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("none");
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }
}
