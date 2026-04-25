package org.example.kbsystemproject.ailearning.infrastructure.rag;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.application.chat.LearningChatCommand;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.example.kbsystemproject.ailearning.domain.intent.IntentType;
import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.config.QueryEnhancementProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class QueryEnhancementService {

    private static final PromptTemplate REWRITE_PROMPT_TEMPLATE = new PromptTemplate("""
            You are an expert at rewriting search queries for semantic retrieval.
            Rewrite the query so it works better for searching a {target}.
            Preserve the user's real intent, entities, constraints, and domain terms.
            Keep the query concise, retrieval-friendly, and faithful to the original request.
            Do not invent facts or broaden the scope.

            Original query:
            {query}

            Rewritten query:
            """);

    private static final PromptTemplate MULTI_QUERY_PROMPT_TEMPLATE = new PromptTemplate("""
            You are an expert at information retrieval and search optimization.
            Generate {number} alternative search queries for the same information need.
            Each query should emphasize a different retrieval angle only when it is relevant,
            such as definition, cause, process, troubleshooting, comparison, examples, or constraints.
            Keep every variant faithful to the original intent.
            Output only the queries, one per line.

            Original query: {query}

            Query variants:
            """);

    private final ChatModel chatModel;
    private final QueryEnhancementProperties properties;
    private final Scheduler aiBlockingScheduler;

    public QueryEnhancementService(ChatModel chatModel,
                                   QueryEnhancementProperties properties,
                                   @Qualifier("aiBlockingScheduler") Scheduler aiBlockingScheduler) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.aiBlockingScheduler = aiBlockingScheduler;
    }

    public Mono<EnhancedQueryPlan> enhance(LearningChatCommand command,
                                           IntentDecision decision,
                                           SessionMemorySnapshot snapshot) {
        String originalQuery = normalizeQuery(command.prompt());
        String contextualQuery = buildContextualQuery(command, decision, snapshot);
        if (originalQuery.isBlank() && contextualQuery.isBlank()) {
            return Mono.just(new EnhancedQueryPlan(
                    "",
                    "",
                    List.of(),
                    Map.of("enhancementMode", "empty-query")
            ));
        }
        if (!properties.isEnabled()) {
            List<String> fallbackQueries = distinctQueries(List.of(originalQuery, contextualQuery));
            return Mono.just(new EnhancedQueryPlan(
                    originalQuery,
                    contextualQuery,
                    fallbackQueries,
                    Map.of("enhancementMode", "disabled")
            ));
        }

        return Mono.fromCallable(() -> enhanceInternal(command, decision, originalQuery, contextualQuery))
                .subscribeOn(aiBlockingScheduler)
                .onErrorResume(error -> {
                    log.warn("Query enhancement failed, falling back to safe retrieval queries. conversationId={}, requestId={}",
                            command.conversationId(),
                            command.requestId(),
                            error);
                    return Mono.just(new EnhancedQueryPlan(
                            originalQuery,
                            contextualQuery,
                            distinctQueries(List.of(originalQuery, contextualQuery)),
                            Map.of(
                                    "enhancementMode", "fallback-safe-query",
                                    "enhancementError", error.getClass().getSimpleName()
                            )
                    ));
                });
    }

    private EnhancedQueryPlan enhanceInternal(LearningChatCommand command,
                                              IntentDecision decision,
                                              String originalQuery,
                                              String contextualQuery) {
        String rewriteInput = contextualQuery.isBlank() ? originalQuery : contextualQuery;
        Query baseQuery = new Query(rewriteInput);
        String rewrittenQuery = shouldRewrite(decision)
                ? normalizeQuery(rewriteQueryTransformer().transform(baseQuery).text())
                : heuristicRewrite(rewriteInput);

        List<String> retrievalQueries = new ArrayList<>();
        if (properties.isIncludeOriginal()) {
            retrievalQueries.add(originalQuery);
        }
        retrievalQueries.add(contextualQuery);
        retrievalQueries.add(rewrittenQuery);

        if (shouldExpandWithLlm(decision)) {
            retrievalQueries.addAll(multiQueryExpander().expand(new Query(rewrittenQuery)).stream()
                    .map(Query::text)
                    .toList());
        } else if (shouldExpandWithHeuristics(decision)) {
            retrievalQueries.addAll(heuristicExpand(command.prompt(), rewrittenQuery));
        }

        List<String> normalizedQueries = distinctQueries(retrievalQueries);
        if (normalizedQueries.isEmpty()) {
            normalizedQueries = distinctQueries(List.of(originalQuery, contextualQuery, rewrittenQuery));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("enhancementMode", normalizedQueries.size() > 1 ? "rewrite+multi-query" : "rewrite-only");
        metadata.put("intentType", decision.intentType().name());
        metadata.put("originalQuery", originalQuery);
        metadata.put("contextualQuery", contextualQuery);
        metadata.put("rewrittenQuery", rewrittenQuery);
        metadata.put("queryCount", normalizedQueries.size());
        if (command.subject() != null && !command.subject().isBlank()) {
            metadata.put("subject", command.subject());
        }
        if (command.currentTopic() != null && !command.currentTopic().isBlank()) {
            metadata.put("currentTopic", command.currentTopic());
        }

        return new EnhancedQueryPlan(originalQuery, rewrittenQuery, normalizedQueries, metadata);
    }

    private RewriteQueryTransformer rewriteQueryTransformer() {
        return RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .promptTemplate(REWRITE_PROMPT_TEMPLATE)
                .targetSearchSystem(properties.getTargetSearchSystem())
                .build();
    }

    private MultiQueryExpander multiQueryExpander() {
        return MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .promptTemplate(MULTI_QUERY_PROMPT_TEMPLATE)
                .includeOriginal(properties.isIncludeOriginal())
                .numberOfQueries(Math.max(1, properties.getMultiQueryCount()))
                .build();
    }

    private boolean shouldRewrite(IntentDecision decision) {
        return properties.isLlmEnabled() && properties.isRewriteEnabled() && decision.needRetrieval();
    }

    private boolean shouldExpandWithLlm(IntentDecision decision) {
        if (!properties.isMultiQueryEnabled() || !properties.isLlmEnabled()) {
            return false;
        }
        return decision.needRetrieval();
    }

    private boolean shouldExpandWithHeuristics(IntentDecision decision) {
        return properties.isMultiQueryEnabled() && !properties.isLlmEnabled() && decision.needRetrieval();
    }

    private String buildContextualQuery(LearningChatCommand command,
                                        IntentDecision decision,
                                        SessionMemorySnapshot snapshot) {
        StringBuilder builder = new StringBuilder();
        appendPart(builder, command.subject());
        appendPart(builder, command.currentTopic());

        if (decision.intentType() == IntentType.FOLLOW_UP) {
            appendPart(builder, latestRelevantUserQuestion(snapshot));
        }

        appendPart(builder, command.prompt());
        return normalizeQuery(builder.toString());
    }

    private String latestRelevantUserQuestion(SessionMemorySnapshot snapshot) {
        if (snapshot == null || snapshot.shortTermMemory() == null || snapshot.shortTermMemory().isEmpty()) {
            return "";
        }
        List<ConversationTurn> history = snapshot.shortTermMemory();
        for (int i = history.size() - 1; i >= 0; i--) {
            ConversationTurn turn = history.get(i);
            if (turn.role() == SessionMessageRole.USER && turn.content() != null && !turn.content().isBlank()) {
                return turn.content();
            }
        }
        return "";
    }

    private void appendPart(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value.trim());
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String heuristicRewrite(String query) {
        return normalizeQuery(query);
    }

    private List<String> heuristicExpand(String prompt, String rewrittenQuery) {
        List<String> queries = new ArrayList<>();
        String normalizedPrompt = normalizeQuery(prompt);
        if (normalizedPrompt.isBlank() || rewrittenQuery.isBlank()) {
            return queries;
        }

        if (containsAny(normalizedPrompt, "什么是", "定义", "概念", "含义")) {
            queries.add(rewrittenQuery + " 定义");
        }
        if (containsAny(normalizedPrompt, "为什么", "原因", "为何")) {
            queries.add(rewrittenQuery + " 原因");
        }
        if (containsAny(normalizedPrompt, "如何", "怎么", "怎样", "步骤", "流程")) {
            queries.add(rewrittenQuery + " 步骤");
        }
        if (containsAny(normalizedPrompt, "区别", "对比", "比较", "差异")) {
            queries.add(rewrittenQuery + " 区别");
        }
        if (containsAny(normalizedPrompt, "报错", "异常", "故障", "失败", "无法")) {
            queries.add(rewrittenQuery + " 排查");
        }
        if (containsAny(normalizedPrompt, "推荐", "建议", "选型", "哪个好")) {
            queries.add(rewrittenQuery + " 对比");
        }

        return queries;
    }

    private List<String> distinctQueries(List<String> queries) {
        return queries.stream()
                .map(this::normalizeQuery)
                .filter(query -> !query.isBlank())
                .distinct()
                .limit(Math.max(1, properties.getMaxQueries()))
                .toList();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

