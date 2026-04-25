package org.example.kbsystemproject.ailearning.infrastructure.rag;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.application.chat.LearningChatCommand;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LearningKnowledgeRetrievalService {

    private static final int RAG_RETRIEVAL_TOP_K = 8;
    private static final int RAG_MAX_CONTEXT_DOCS = 4;
    private static final int RAG_RRF_K = 60;

    private final VectorStore pgVectorStore;
    private final QueryEnhancementService queryEnhancementService;
    private final Bm25SearchService bm25SearchService;
    private final MeterRegistry meterRegistry;
    private final Scheduler retrievalBlockingScheduler;

    public LearningKnowledgeRetrievalService(VectorStore pgVectorStore,
                                             QueryEnhancementService queryEnhancementService,
                                             Bm25SearchService bm25SearchService,
                                             MeterRegistry meterRegistry,
                                             @Qualifier("retrievalBlockingScheduler") Scheduler retrievalBlockingScheduler) {
        this.pgVectorStore = pgVectorStore;
        this.queryEnhancementService = queryEnhancementService;
        this.bm25SearchService = bm25SearchService;
        this.meterRegistry = meterRegistry;
        this.retrievalBlockingScheduler = retrievalBlockingScheduler;
    }

    public Mono<List<Document>> retrieveKnowledge(LearningChatCommand command,
                                                  IntentDecision decision,
                                                  SessionMemorySnapshot snapshot) {
        if (!decision.needRetrieval()) {
            recordRetrievalMetrics(decision, "skipped", 0, 0L);
            return Mono.just(List.of());
        }
        return Mono.defer(() -> {
            long startNanos = System.nanoTime();
            return queryEnhancementService.enhance(command, decision, snapshot)
                    .flatMap(plan -> Mono.zip(
                                    searchVectorQueries(plan.retrievalQueries()),
                                    searchBm25Queries(buildBm25Queries(plan))
                            )
                            .map(tuple -> {
                                List<QuerySearchResult> allResults = new ArrayList<>(tuple.getT1());
                                allResults.addAll(tuple.getT2());
                                return allResults;
                            })
                            .map(this::selectRetrievedDocuments)
                            .doOnNext(documents -> {
                                long durationNanos = System.nanoTime() - startNanos;
                                recordRetrievalMetrics(decision, "success", documents.size(), durationNanos);
                                log.info(
                                        "RAG retrieval completed. conversationId={}, requestId={}, rewrittenQuery={}, retrievalQueries={}, bm25Queries={}, docs={}",
                                        command.conversationId(),
                                        command.requestId(),
                                        plan.rewrittenQuery(),
                                        plan.retrievalQueries(),
                                        buildBm25Queries(plan),
                                        documents.size()
                                );
                            }))
                    .onErrorResume(error -> {
                        long durationNanos = System.nanoTime() - startNanos;
                        recordRetrievalMetrics(decision, "fallback", 0, durationNanos);
                        log.warn(
                                "RAG retrieval failed, falling back to prompt-only flow. conversationId={}, requestId={}",
                                command.conversationId(),
                                command.requestId(),
                                error
                        );
                        return Mono.just(List.of());
                    });
        });
    }

    private Mono<List<QuerySearchResult>> searchVectorQueries(List<String> queries) {
        return Flux.fromIterable(queries)
                .flatMap(this::searchVectorDocumentsForQuery)
                .collectList();
    }

    private Mono<List<QuerySearchResult>> searchBm25Queries(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(queries)
                .flatMap(this::searchBm25DocumentsForQuery)
                .collectList();
    }

    private List<String> buildBm25Queries(EnhancedQueryPlan plan) {
        List<String> queries = new ArrayList<>();
        if (plan.originalQuery() != null && !plan.originalQuery().isBlank()) {
            queries.add(plan.originalQuery());
        }
        if (plan.rewrittenQuery() != null && !plan.rewrittenQuery().isBlank()) {
            queries.add(plan.rewrittenQuery());
        }
        return queries.stream().distinct().toList();
    }

    private Mono<QuerySearchResult> searchVectorDocumentsForQuery(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(RAG_RETRIEVAL_TOP_K)
                .build();
        return Mono.fromCallable(() -> pgVectorStore.similaritySearch(request))
                .subscribeOn(retrievalBlockingScheduler)
                .map(documents -> new QuerySearchResult("vector", query, documents))
                .onErrorResume(error -> {
                    log.warn("Vector search failed for query: {}", query, error);
                    return Mono.just(new QuerySearchResult("vector", query, List.of()));
                });
    }

    private Mono<QuerySearchResult> searchBm25DocumentsForQuery(String query) {
        return Mono.fromCallable(() -> bm25SearchService.search(query, RAG_RETRIEVAL_TOP_K))
                .subscribeOn(retrievalBlockingScheduler)
                .map(documents -> new QuerySearchResult("bm25", query, documents))
                .onErrorResume(error -> {
                    log.warn("BM25 search failed for query: {}", query, error);
                    return Mono.just(new QuerySearchResult("bm25", query, List.of()));
                });
    }

    private List<Document> selectRetrievedDocuments(List<QuerySearchResult> queryResults) {
        if (queryResults == null || queryResults.isEmpty()) {
            return List.of();
        }

        List<Document> sanitizedDocuments = fuseDocumentsByRrf(queryResults);
        if (sanitizedDocuments.isEmpty()) {
            return List.of();
        }
        return sanitizedDocuments.stream()
                .limit(RAG_MAX_CONTEXT_DOCS)
                .toList();
    }

    private List<Document> fuseDocumentsByRrf(List<QuerySearchResult> queryResults) {
        Map<String, RetrievedDocumentCandidate> fused = new LinkedHashMap<>();
        for (QuerySearchResult queryResult : queryResults) {
            List<Document> documents = queryResult.documents();
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                if (document == null || document.getText() == null || document.getText().isBlank()) {
                    continue;
                }
                String key = documentDedupKey(document);
                RetrievedDocumentCandidate existing = fused.get(key);
                double rrfScore = 1.0D / (RAG_RRF_K + i + 1);
                double bestScore = documentScoreOrMaxDistance(document);
                if (existing == null) {
                    fused.put(key, new RetrievedDocumentCandidate(document, rrfScore, 1, bestScore, queryResult.source()));
                    continue;
                }
                existing.rrfScore += rrfScore;
                existing.hitCount++;
                existing.sources.add(queryResult.source());
                if (bestScore < existing.bestScore) {
                    existing.document = document;
                    existing.bestScore = bestScore;
                }
            }
        }
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedDocumentCandidate::finalScore).reversed())
                .map(candidate -> candidate.document)
                .toList();
    }

    private String documentDedupKey(Document document) {
        if (document.getId() != null && !document.getId().isBlank()) {
            return document.getId();
        }
        return formatDocumentSource(document) + "::" + limitText(document.getText(), 120);
    }

    private double documentScoreOrMaxDistance(Document document) {
        if (document.getMetadata() == null || document.getMetadata().isEmpty()) {
            return Double.MAX_VALUE;
        }
        Object score = document.getMetadata().get("distance");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        score = document.getMetadata().get("score");
        if (score instanceof Number number) {
            return -number.doubleValue();
        }
        return Double.MAX_VALUE;
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

    private void recordRetrievalMetrics(IntentDecision decision,
                                        String outcome,
                                        int documentCount,
                                        long durationNanos) {
        List<Tag> tags = retrievalMetricTags(decision, outcome);

        Counter.builder("ai.learning.retrieval.requests")
                .description("Learning retrieval executions")
                .tags(tags)
                .register(meterRegistry)
                .increment();

        DistributionSummary.builder("ai.learning.retrieval.docs")
                .description("Retrieved documents selected for context")
                .baseUnit("documents")
                .tags(tags)
                .register(meterRegistry)
                .record(documentCount);

        Timer.builder("ai.learning.retrieval.duration")
                .description("Learning retrieval duration")
                .tags(tags)
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private List<Tag> retrievalMetricTags(IntentDecision decision, String outcome) {
        return List.of(
                Tag.of("executionMode", decision.executionMode().name()),
                Tag.of("intentType", decision.intentType().name()),
                Tag.of("retrievalEnabled", Boolean.toString(decision.needRetrieval())),
                Tag.of("outcome", outcome)
        );
    }

    private static final class RetrievedDocumentCandidate {
        private Document document;
        private double rrfScore;
        private int hitCount;
        private double bestScore;
        private final List<String> sources = new ArrayList<>();

        private RetrievedDocumentCandidate(Document document,
                                           double rrfScore,
                                           int hitCount,
                                           double bestScore,
                                           String source) {
            this.document = document;
            this.rrfScore = rrfScore;
            this.hitCount = hitCount;
            this.bestScore = bestScore;
            this.sources.add(source);
        }

        private double finalScore() {
            return rrfScore + Math.max(0, hitCount - 1) * 1.0e-4 - Math.min(bestScore, 1.0D) * 1.0e-6;
        }
    }

    private record QuerySearchResult(String source, String query, List<Document> documents) {
    }
}

