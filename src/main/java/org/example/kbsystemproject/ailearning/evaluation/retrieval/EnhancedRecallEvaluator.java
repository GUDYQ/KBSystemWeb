package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import lombok.Data;
import org.example.kbsystemproject.ailearning.application.chat.LearningChatCommand;
import org.example.kbsystemproject.ailearning.domain.intent.ExecutionMode;
import org.example.kbsystemproject.ailearning.domain.intent.IntentDecision;
import org.example.kbsystemproject.ailearning.domain.intent.IntentSource;
import org.example.kbsystemproject.ailearning.domain.intent.IntentType;
import org.example.kbsystemproject.ailearning.domain.session.LearningSessionType;
import org.example.kbsystemproject.ailearning.infrastructure.rag.Bm25SearchService;
import org.example.kbsystemproject.ailearning.infrastructure.rag.EnhancedQueryPlan;
import org.example.kbsystemproject.ailearning.infrastructure.rag.QueryEnhancementService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EnhancedRecallEvaluator {

    private static final int RRF_K = 60;

    private final VectorStore pgVectorStore;
    private final QueryEnhancementService queryEnhancementService;
    private final Bm25SearchService bm25SearchService;
    private final T2Bm25IndexService t2Bm25IndexService;

    public EnhancedRecallEvaluator(VectorStore pgVectorStore,
                                   QueryEnhancementService queryEnhancementService,
                                   Bm25SearchService bm25SearchService,
                                   T2Bm25IndexService t2Bm25IndexService) {
        this.pgVectorStore = pgVectorStore;
        this.queryEnhancementService = queryEnhancementService;
        this.bm25SearchService = bm25SearchService;
        this.t2Bm25IndexService = t2Bm25IndexService;
    }

    public Mono<RecallComparisonReport> evaluateBatch(List<RecallTestCase> testCases) {
        return t2Bm25IndexService.ensureIndexed()
                .thenMany(Flux.fromIterable(testCases)
                        .flatMap(this::evaluateCase, 2))
                .collectList()
                .map(this::toComparisonReport);
    }

    public Mono<RecallComparisonCaseResult> evaluateCase(RecallTestCase testCase) {
        return buildEnhancedPlan(testCase)
                .flatMap(plan -> Mono.zip(
                        evaluateVectorOnly(testCase),
                        evaluateBm25Only(testCase),
                        evaluateEnhancedVector(testCase, plan),
                        evaluateHybrid(testCase, plan)
                ))
                .map(tuple -> new RecallComparisonCaseResult(
                        tuple.getT1(),
                        tuple.getT2(),
                        tuple.getT3(),
                        tuple.getT4()
                ));
    }

    private Mono<EnhancedQueryPlan> buildEnhancedPlan(RecallTestCase testCase) {
        LearningChatCommand command = new LearningChatCommand(
                "eval-" + UUID.randomUUID(),
                "req-" + UUID.randomUUID(),
                "evaluation",
                null,
                LearningSessionType.QA,
                null,
                null,
                testCase.query()
        );
        IntentDecision decision = new IntentDecision(
                IntentType.GENERAL_QA,
                LearningSessionType.QA,
                ExecutionMode.DIRECT,
                1.0D,
                IntentSource.FALLBACK,
                true,
                false,
                "enhanced-recall-eval"
        );
        return queryEnhancementService.enhance(command, decision, null);
    }

    private Mono<EnhancedRecallResult> evaluateVectorOnly(RecallTestCase testCase) {
        return Mono.fromCallable(() -> {
                    List<Document> retrievedDocs = searchVector(testCase.query(), testCase.topK(), testCase.threshold());
                    return toRecallResult(testCase, "vector", List.of(testCase.query()), Map.of(), retrievedDocs);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<EnhancedRecallResult> evaluateBm25Only(RecallTestCase testCase) {
        return Mono.fromCallable(() -> {
                    List<Document> retrievedDocs = searchBm25(testCase.query(), testCase.topK());
                    return toRecallResult(testCase, "bm25", List.of(testCase.query()), Map.of(), retrievedDocs);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<EnhancedRecallResult> evaluateEnhancedVector(RecallTestCase testCase, EnhancedQueryPlan plan) {
        return searchVectorQueries(plan.retrievalQueries(), testCase.topK(), testCase.threshold())
                .map(this::fuseDocumentsByRrf)
                .map(documents -> documents.stream().limit(testCase.topK()).toList())
                .map(documents -> toRecallResult(testCase, "enhanced-vector", plan.retrievalQueries(), plan.metadata(), documents));
    }

    private Mono<EnhancedRecallResult> evaluateHybrid(RecallTestCase testCase, EnhancedQueryPlan plan) {
        return Mono.zip(
                        searchVectorQueries(plan.retrievalQueries(), testCase.topK(), testCase.threshold()),
                        searchBm25Queries(buildBm25Queries(plan), testCase.topK())
                )
                .map(tuple -> {
                    List<QuerySearchResult> queryResults = new ArrayList<>(tuple.getT1());
                    queryResults.addAll(tuple.getT2());
                    return queryResults;
                })
                .map(this::fuseDocumentsByRrf)
                .map(documents -> documents.stream().limit(testCase.topK()).toList())
                .map(documents -> toRecallResult(testCase, "hybrid", plan.retrievalQueries(), plan.metadata(), documents));
    }

    private Mono<List<QuerySearchResult>> searchVectorQueries(List<String> queries, int topK, double threshold) {
        return Flux.fromIterable(queries)
                .flatMap(query -> Mono.fromCallable(() -> new QuerySearchResult(
                                "vector",
                                query,
                                searchVector(query, topK, threshold)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .collectList();
    }

    private Mono<List<QuerySearchResult>> searchBm25Queries(List<String> queries, int topK) {
        if (queries == null || queries.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(queries)
                .flatMap(query -> Mono.fromCallable(() -> new QuerySearchResult(
                                "bm25",
                                query,
                                searchBm25(query, topK)))
                        .subscribeOn(Schedulers.boundedElastic()))
                .collectList();
    }

    private List<Document> searchVector(String query, int topK, double threshold) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
        return pgVectorStore.similaritySearch(request);
    }

    private List<Document> searchBm25(String query, int topK) {
        return bm25SearchService.search(query, topK);
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

    private EnhancedRecallResult toRecallResult(RecallTestCase testCase,
                                                String mode,
                                                List<String> effectiveQueries,
                                                Map<String, Object> metadata,
                                                List<Document> retrievedDocs) {
        Map<String, Document> retrievedById = new LinkedHashMap<>();
        for (Document doc : retrievedDocs) {
            retrievedById.putIfAbsent(resolveGroundTruthId(doc), doc);
        }

        List<String> hitIds = new ArrayList<>();
        List<String> missedIds = new ArrayList<>();
        for (String gtId : testCase.groundTruthIds()) {
            if (retrievedById.containsKey(gtId)) {
                hitIds.add(gtId);
            } else {
                missedIds.add(gtId);
            }
        }

        double recall = testCase.groundTruthIds().isEmpty()
                ? 0.0
                : (double) hitIds.size() / testCase.groundTruthIds().size();

        List<RecallEvaluator.RetrievedDoc> docDetails = retrievedDocs.stream()
                .map(doc -> new RecallEvaluator.RetrievedDoc(
                        resolveGroundTruthId(doc),
                        truncate(doc.getText(), 100),
                        extractScore(doc),
                        doc.getMetadata()
                ))
                .toList();

        return new EnhancedRecallResult(
                testCase.caseId(),
                mode,
                testCase.query(),
                effectiveQueries,
                round4(recall),
                testCase.groundTruthIds().size(),
                hitIds.size(),
                hitIds,
                missedIds,
                docDetails,
                metadata
        );
    }

    private List<Document> fuseDocumentsByRrf(List<QuerySearchResult> queryResults) {
        Map<String, Candidate> fused = new LinkedHashMap<>();
        for (QuerySearchResult queryResult : queryResults) {
            List<Document> documents = queryResult.documents();
            for (int i = 0; i < documents.size(); i++) {
                Document document = documents.get(i);
                String key = resolveGroundTruthId(document);
                Candidate existing = fused.get(key);
                double comparableScore = extractComparableScore(document);
                double rrfScore = 1.0D / (RRF_K + i + 1);
                if (existing == null) {
                    fused.put(key, new Candidate(document, rrfScore, 1, comparableScore));
                    continue;
                }
                existing.rrfScore += rrfScore;
                existing.hitCount++;
                if (comparableScore < existing.bestComparableScore) {
                    existing.document = document;
                    existing.bestComparableScore = comparableScore;
                }
            }
        }
        return fused.values().stream()
                .sorted((left, right) -> Double.compare(right.finalComparableScore(), left.finalComparableScore()))
                .map(candidate -> candidate.document)
                .toList();
    }

    private String resolveGroundTruthId(Document doc) {
        Object originalId = doc.getMetadata().get("t2_doc_id");
        if (originalId != null) {
            return originalId.toString();
        }
        return doc.getId();
    }

    private double extractComparableScore(Document doc) {
        Object score = doc.getMetadata().get("distance");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        score = doc.getMetadata().get("score");
        if (score instanceof Number number) {
            return -number.doubleValue();
        }
        return Double.MAX_VALUE;
    }

    private double extractScore(Document doc) {
        Object score = doc.getMetadata().get("distance");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        score = doc.getMetadata().get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        return -1.0;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private RecallComparisonReport toComparisonReport(List<RecallComparisonCaseResult> results) {
        RecallComparisonReport report = new RecallComparisonReport();
        report.setResults(results);
        report.setTotalCases(results.size());
        if (results.isEmpty()) {
            return report;
        }

        double vectorAvg = avgRecall(results, ResultSelector.VECTOR);
        double bm25Avg = avgRecall(results, ResultSelector.BM25);
        double enhancedVectorAvg = avgRecall(results, ResultSelector.ENHANCED_VECTOR);
        double hybridAvg = avgRecall(results, ResultSelector.HYBRID);

        report.setAvgVectorRecall(round4(vectorAvg));
        report.setAvgBm25Recall(round4(bm25Avg));
        report.setAvgEnhancedVectorRecall(round4(enhancedVectorAvg));
        report.setAvgHybridRecall(round4(hybridAvg));
        report.setHybridVsVectorDelta(round4(hybridAvg - vectorAvg));
        report.setHybridVsBm25Delta(round4(hybridAvg - bm25Avg));
        report.setHybridVsEnhancedVectorDelta(round4(hybridAvg - enhancedVectorAvg));
        report.setBestModeCounts(bestModeCounts(results));
        report.setEnhancedQueryUsage(results.stream()
                .collect(LinkedHashMap::new,
                        (map, result) -> map.put(result.hybrid().caseId(), result.hybrid().effectiveQueries().size()),
                        LinkedHashMap::putAll));
        return report;
    }

    private Map<String, Integer> bestModeCounts(List<RecallComparisonCaseResult> results) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("vector", 0);
        counts.put("bm25", 0);
        counts.put("enhanced-vector", 0);
        counts.put("hybrid", 0);

        for (RecallComparisonCaseResult result : results) {
            double best = Math.max(
                    Math.max(result.vector().recall(), result.bm25().recall()),
                    Math.max(result.enhancedVector().recall(), result.hybrid().recall())
            );
            incrementIfBest(counts, "vector", result.vector().recall(), best);
            incrementIfBest(counts, "bm25", result.bm25().recall(), best);
            incrementIfBest(counts, "enhanced-vector", result.enhancedVector().recall(), best);
            incrementIfBest(counts, "hybrid", result.hybrid().recall(), best);
        }
        return counts;
    }

    private void incrementIfBest(Map<String, Integer> counts, String key, double value, double best) {
        if (Double.compare(value, best) == 0) {
            counts.computeIfPresent(key, (ignored, count) -> count + 1);
        }
    }

    private double avgRecall(List<RecallComparisonCaseResult> results, ResultSelector selector) {
        return results.stream()
                .map(selector::select)
                .mapToDouble(EnhancedRecallResult::recall)
                .average()
                .orElse(0.0D);
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public record EnhancedRecallResult(
            String caseId,
            String mode,
            String originalQuery,
            List<String> effectiveQueries,
            double recall,
            int groundTruthCount,
            int hitCount,
            List<String> hitIds,
            List<String> missedIds,
            List<RecallEvaluator.RetrievedDoc> retrievedDocs,
            Map<String, Object> metadata
    ) {
    }

    public record RecallComparisonCaseResult(
            EnhancedRecallResult vector,
            EnhancedRecallResult bm25,
            EnhancedRecallResult enhancedVector,
            EnhancedRecallResult hybrid
    ) {
    }

    @Data
    public static class RecallComparisonReport {
        private List<RecallComparisonCaseResult> results = List.of();
        private int totalCases;
        private double avgVectorRecall;
        private double avgBm25Recall;
        private double avgEnhancedVectorRecall;
        private double avgHybridRecall;
        private double hybridVsVectorDelta;
        private double hybridVsBm25Delta;
        private double hybridVsEnhancedVectorDelta;
        private Map<String, Integer> bestModeCounts = Map.of();
        private Map<String, Integer> enhancedQueryUsage = Map.of();
    }

    private enum ResultSelector {
        VECTOR {
            @Override
            EnhancedRecallResult select(RecallComparisonCaseResult result) {
                return result.vector();
            }
        },
        BM25 {
            @Override
            EnhancedRecallResult select(RecallComparisonCaseResult result) {
                return result.bm25();
            }
        },
        ENHANCED_VECTOR {
            @Override
            EnhancedRecallResult select(RecallComparisonCaseResult result) {
                return result.enhancedVector();
            }
        },
        HYBRID {
            @Override
            EnhancedRecallResult select(RecallComparisonCaseResult result) {
                return result.hybrid();
            }
        };

        abstract EnhancedRecallResult select(RecallComparisonCaseResult result);
    }

    private static final class Candidate {
        private Document document;
        private double rrfScore;
        private int hitCount;
        private double bestComparableScore;

        private Candidate(Document document, double rrfScore, int hitCount, double bestComparableScore) {
            this.document = document;
            this.rrfScore = rrfScore;
            this.hitCount = hitCount;
            this.bestComparableScore = bestComparableScore;
        }

        private double finalComparableScore() {
            return rrfScore + Math.max(0, hitCount - 1) * 1.0e-4 - Math.min(bestComparableScore, 1.0D) * 1.0e-6;
        }
    }

    private record QuerySearchResult(String source, String query, List<Document> documents) {
    }
}

