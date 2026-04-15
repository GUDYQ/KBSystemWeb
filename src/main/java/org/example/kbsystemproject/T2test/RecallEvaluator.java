package org.example.kbsystemproject.T2test;

import lombok.Data;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RecallEvaluator {

    private final VectorStore pgVectorStore;

    public RecallEvaluator(VectorStore pgVectorStore) {
        this.pgVectorStore = pgVectorStore;
    }

    // ==========================================
    // 单条评估
    // ==========================================

    /**
     * 评估单个测试用例
     */
    public Mono<RecallResult> evaluate(RecallTestCase testCase) {
        return Mono.fromCallable(() -> doEvaluate(testCase))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private RecallResult doEvaluate(RecallTestCase testCase) {
        // 1. 执行检索
        SearchRequest request = SearchRequest.builder()
                .query(testCase.query())
                .topK(testCase.topK())
                .similarityThreshold(testCase.threshold())
                .build();

        List<Document> retrievedDocs = pgVectorStore.similaritySearch(request);

        // 2. 提取检索结果ID（注意：因为支持了 Chunk 切分，需要优先读取 metadata 中的原始 ID，兜底才是当前 Document 的 id）
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

        // 3. 计算命中情况
        List<String> hitIds = new ArrayList<>();
        List<String> missedIds = new ArrayList<>();

        for (String gtId : testCase.groundTruthIds()) {
            if (retrievedIds.contains(gtId)) {
                hitIds.add(gtId);
            } else {
                missedIds.add(gtId);
            }
        }

        // 4. 计算误召回（检索到但不在标准答案中）
        List<String> unexpectedIds = retrievedIds.stream()
                .filter(id -> !groundTruthSet.contains(id))
                .toList();

        // 5. 计算召回率
        int gtCount = testCase.groundTruthIds().size();
        double recall = gtCount == 0 ? 0.0 : (double) hitIds.size() / gtCount;

        // 6. 构建检索详情（截断文本用于展示）
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
                Math.round(recall * 10000.0) / 10000.0, // 保留4位小数
                gtCount,
                hitIds.size(),
                hitIds,
                missedIds,
                unexpectedIds,
                docDetails
        );
    }

    // ==========================================
    // 批量评估 + 报告生成
    // ==========================================

    /**
     * 批量评估，并发执行，最后汇总报告
     */
    public Mono<RecallReport> evaluateBatch(List<RecallTestCase> testCases) {
        // 并发执行所有测试用例（WebFlux 的优势）
        return Flux.fromIterable(testCases)
                .flatMap(this::evaluate, 4) // concurrency=4，避免压垮弹性线程池
                .collectList()
                .map(this::generateReport);
    }

    /**
     * 针对同一组 Query，自动测试不同 Top-K 和 Threshold 的召回率
     */
    public Mono<Map<String, RecallReport>> evaluateMultiDimension(
            List<RecallTestCase> baseCases,
            List<Integer> topKs,
            List<Double> thresholds) {

        // 生成所有组合的测试用例
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
                    // 按维度分组
                    Map<String, RecallReport> reports = new LinkedHashMap<>();

                    // 按 Top-K 分组
                    for (int k : topKs) {
                        String key = "Top-" + k;
                        List<RecallResult> filtered = report.getResults().stream()
                                .filter(r -> r.retrievedDocs().size() <= k) // 简单过滤
                                .toList();
                        reports.put(key, generateReport(filtered));
                    }

                    return reports;
                });
    }

    // ==========================================
    // 报告生成
    // ==========================================

    private RecallReport generateReport(List<RecallResult> results) {
        RecallReport report = new RecallReport();
        report.setResults(results);
        report.setTotalCases(results.size());

        if (results.isEmpty()) {
            report.setAvgRecall(0);
            return report;
        }

        // 平均召回率
        double avgRecall = results.stream()
                .mapToDouble(RecallResult::recall)
                .average()
                .orElse(0);
        report.setAvgRecall(Math.round(avgRecall * 10000.0) / 10000.0);

        // Recall@1, @3, @5 严格统计（截取前 K 个检索结果重新计算命中率）
        report.setRecallAt1(calcAvgRecallAtK(results, 1));
        report.setRecallAt3(calcAvgRecallAtK(results, 3));
        report.setRecallAt5(calcAvgRecallAtK(results, 5));

        // 完美召回比例（Recall = 1.0 的用例占比）
        long perfectCount = results.stream().filter(r -> r.recall() == 1.0).count();
        // 可以加到 report 中

        return report;
    }

    private double calcAvgRecallAtK(List<RecallResult> results, int k) {
        return results.stream()
                .mapToDouble(r -> {
                    if (r.groundTruthCount() == 0) return 0.0;

                    // 优化：先映射出原本真实的Doc ID，去重后，再截断前 K 个唯一的文档，避免多个Chunk属于同一个Doc占据坑位
                    Set<String> topKRetrievedIds = r.retrievedDocs().stream()
                            .map(doc -> {
                                Object originalId = doc.metadata().get("t2_doc_id");
                                return originalId != null ? originalId.toString() : doc.id();
                            })
                            .distinct()
                            .limit(k)
                            .collect(Collectors.toSet());

                    // 看这些前 K 个文档中命中了多少基础事实
                    long hitsInTopK = 0;
                    // 注意此处这里原来并没有把 groundTruthIds 存在 RecallResult 中，但可以通过 hitIds 和 missedIds 重构出来
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

    // ==========================================
    // 工具方法
    // ==========================================

    private String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    /**
     * 尝试从 Document 中提取相似度分数
     * 不同 VectorStore 实现存储方式不同，这里做兼容
     */
    private double extractScore(Document doc) {
        // 方式1：从 metadata 中获取（部分 VectorStore 会放入）
        Object score = doc.getMetadata().get("distance");
        if (score instanceof Number) {
            return ((Number) score).doubleValue();
        }
        score = doc.getMetadata().get("score");
        if (score instanceof Number) {
            return ((Number) score).doubleValue();
        }
        return -1.0; // 表示不可用
    }


    /**
     * 单条用例的评估结果
     */
    public record RecallResult(
            String caseId,
            String query,

            // === 核心指标 ===
            double recall,                  // 召回率 0.0 ~ 1.0

            // === 详细数据 ===
            int groundTruthCount,           // 标准答案总数
            int hitCount,                   // 实际命中数
            List<String> hitIds,            // 命中的标准答案ID
            List<String> missedIds,         // 未命中的标准答案ID（漏召回）
            List<String> unexpectedIds,     // 不在标准答案中但被检索出来的ID（误召回）

            // === 原始检索数据（调试用） ===
            List<RetrievedDoc> retrievedDocs // 实际检索到的文档列表（含相似度分数）
    ) {}

    /**
     * 检索到的文档详情
     */
    public record RetrievedDoc(
            String id,
            String textSnippet,             // 文本前100字
            double score,                   // 相似度分数（如果 VectorStore 提供）
            Map<String, Object> metadata
    ) {}

    /**
     * 批量测试报告
     */
    @Data
    public class RecallReport {
        private List<RecallResult> results;
        private int totalCases;
        private double avgRecall;
        private double recallAt1;       // Recall@1 平均值
        private double recallAt3;       // Recall@3 平均值
        private double recallAt5;       // Recall@5 平均值
        private Map<String, Double> recallByThreshold; // 不同阈值下的召回率

        // getter/setter/toString 省略，实际使用建议用 record 或 Lombok
    }
}
