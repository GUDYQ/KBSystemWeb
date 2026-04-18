package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import lombok.Data;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RecallEvaluator {

    private final VectorStore pgVectorStore;

    public RecallEvaluator(VectorStore pgVectorStore) {
        this.pgVectorStore = pgVectorStore;
    }

    public Mono<RecallResult> evaluate(RecallTestCase testCase) {
        return Mono.fromCallable(() -> doEvaluate(testCase))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private RecallResult doEvaluate(RecallTestCase testCase) {
        SearchRequest request = SearchRequest.builder()
                .query(testCase.query())
                .topK(testCase.topK())
                .similarityThreshold(testCase.threshold())
                .build();

        List<Document> retrievedDocs = pgVectorStore.similaritySearch(request);

        Set<String> retrievedIds = retrievedDocs.stream()
                .map(doc -> {
                    Object originalId = doc.getMetadata().get("t2_doc_id");
                    if (originalId != null) {
                        return originalId.toString();
                    }
                    return doc.getId();
                })
                .collect(Collectors.toSet());

        Set<String> groundTruthSet = new HashSet<>(testCase.groundTruthIds());
        List<String> hitIds = new ArrayList<>();
        List<String> missedIds = new ArrayList<>();

        for (String gtId : testCase.groundTruthIds()) {
            if (retrievedIds.contains(gtId)) {
                hitIds.add(gtId);
            } else {
                missedIds.add(gtId);
            }
        }

        List<String> unexpectedIds = retrievedIds.stream()
                .filter(id -> !groundTruthSet.contains(id))
                .toList();

        int gtCount = testCase.groundTruthIds().size();
        double recall = gtCount == 0 ? 0.0 : (double) hitIds.size() / gtCount;

        List<RetrievedDoc> docDetails = retrievedDocs.stream()
                .map(doc -> new RetrievedDoc(
                        doc.getId(),
                        truncate(doc.getText(), 100),
                        extractScore(doc),
                        doc.getMetadata()
                ))
                .toList();

        return new RecallResult(
                testCase.caseId(),
                testCase.query(),
                Math.round(recall * 10000.0) / 10000.0,
                gtCount,
                hitIds.size(),
                hitIds,
                missedIds,
                unexpectedIds,
                docDetails
        );
    }

    public Mono<RecallReport> evaluateBatch(List<RecallTestCase> testCases) {
        return Flux.fromIterable(testCases)
                .flatMap(this::evaluate, 4)
                .collectList()
                .map(this::generateReport);
    }

    public Mono<Map<String, RecallReport>> evaluateMultiDimension(List<RecallTestCase> baseCases,
                                                                  List<Integer> topKs,
                                                                  List<Double> thresholds) {
        List<RecallTestCase> allCases = new ArrayList<>();
        for (RecallTestCase base : baseCases) {
            for (int k : topKs) {
                for (double t : thresholds) {
                    String caseId = base.caseId() + "_k" + k + "_t" + t;
                    allCases.add(new RecallTestCase(caseId, base.query(), base.groundTruthIds(), k, t));
                }
            }
        }

        return evaluateBatch(allCases)
                .map(report -> {
                    Map<String, RecallReport> reports = new LinkedHashMap<>();
                    for (int k : topKs) {
                        String key = "Top-" + k;
                        List<RecallResult> filtered = report.getResults().stream()
                                .filter(r -> r.retrievedDocs().size() <= k)
                                .toList();
                        reports.put(key, generateReport(filtered));
                    }
                    return reports;
                });
    }

    private RecallReport generateReport(List<RecallResult> results) {
        RecallReport report = new RecallReport();
        report.setResults(results);
        report.setTotalCases(results.size());

        if (results.isEmpty()) {
            report.setAvgRecall(0);
            return report;
        }

        double avgRecall = results.stream()
                .mapToDouble(RecallResult::recall)
                .average()
                .orElse(0);
        report.setAvgRecall(Math.round(avgRecall * 10000.0) / 10000.0);
        report.setRecallAt1(calcAvgRecallAtK(results, 1));
        report.setRecallAt3(calcAvgRecallAtK(results, 3));
        report.setRecallAt5(calcAvgRecallAtK(results, 5));
        return report;
    }

    private double calcAvgRecallAtK(List<RecallResult> results, int k) {
        return results.stream()
                .mapToDouble(r -> {
                    if (r.groundTruthCount() == 0) {
                        return 0.0;
                    }
                    Set<String> topKRetrievedIds = r.retrievedDocs().stream()
                            .map(doc -> {
                                Object originalId = doc.metadata().get("t2_doc_id");
                                return originalId != null ? originalId.toString() : doc.id();
                            })
                            .distinct()
                            .limit(k)
                            .collect(Collectors.toSet());

                    long hitsInTopK = 0;
                    List<String> groundTruths = new ArrayList<>(r.hitIds());
                    groundTruths.addAll(r.missedIds());
                    for (String gtId : groundTruths) {
                        if (topKRetrievedIds.contains(gtId)) {
                            hitsInTopK++;
                        }
                    }
                    return (double) hitsInTopK / r.groundTruthCount();
                })
                .average()
                .orElse(0);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
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

    public record RecallResult(
            String caseId,
            String query,
            double recall,
            int groundTruthCount,
            int hitCount,
            List<String> hitIds,
            List<String> missedIds,
            List<String> unexpectedIds,
            List<RetrievedDoc> retrievedDocs
    ) {
    }

    public record RetrievedDoc(
            String id,
            String textSnippet,
            double score,
            Map<String, Object> metadata
    ) {
    }

    @Data
    public class RecallReport {
        private List<RecallResult> results;
        private int totalCases;
        private double avgRecall;
        private double recallAt1;
        private double recallAt3;
        private double recallAt5;
        private Map<String, Double> recallByThreshold;
    }
}
