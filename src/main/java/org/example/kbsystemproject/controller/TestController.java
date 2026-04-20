package org.example.kbsystemproject.controller;

import org.example.kbsystemproject.ailearning.application.service.AiLearningApplicationService;
import org.example.kbsystemproject.ailearning.evaluation.retrieval.EnhancedRecallEvaluator;
import org.example.kbsystemproject.ailearning.evaluation.retrieval.RecallEvaluator;
import org.example.kbsystemproject.ailearning.evaluation.retrieval.T2RecallTestCaseBuilder;
import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestController {

    private final AiLearningApplicationService aiLearningApplicationService;
    private final RecallEvaluator recallEvaluator;
    private final EnhancedRecallEvaluator enhancedRecallEvaluator;
    private final T2RecallTestCaseBuilder testCaseBuilder;

    public TestController(AiLearningApplicationService aiLearningApplicationService,
                          RecallEvaluator recallEvaluator,
                          EnhancedRecallEvaluator enhancedRecallEvaluator,
                          T2RecallTestCaseBuilder testCaseBuilder) {
        this.aiLearningApplicationService = aiLearningApplicationService;
        this.recallEvaluator = recallEvaluator;
        this.enhancedRecallEvaluator = enhancedRecallEvaluator;
        this.testCaseBuilder = testCaseBuilder;
    }

    @GetMapping("/hello")
    public Mono<ResponseVO<String>> hello() {
        return Mono.just(ResponseBuilder.success("hello"));
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ResponseVO<String>> chat(@RequestParam String prompt) {
        return aiLearningApplicationService.chatTest(prompt)
                .map(ResponseBuilder::success);
    }

    @PostMapping("/ai/search")
    public Flux<ResponseVO<Map<String, Object>>> aiSearch(@RequestBody Map<String, String> payload) {
        String query = payload.get("query");
        return aiLearningApplicationService.searchSimilarity(query)
                .map(doc -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("id", doc.getId());
                    map.put("content", doc.getText());
                    map.put("metadata", doc.getMetadata());
                    return map;
                })
                .map(ResponseBuilder::success);
    }

    /**
     * 用 T2Retrieval dev 集批量测试召回率（默认最多 1000 条）
     */
    @GetMapping("/batch")
    public Mono<ResponseVO<RecallEvaluator.RecallReport>> batchRecall() {
        return testCaseBuilder.buildDevCases()
                .collectList()
                .flatMap(recallEvaluator::evaluateBatch)
                .map(ResponseBuilder::success);
    }

    /**
     * 获取平均召回率测试结果
     */
    @GetMapping("/batch/avg")
    public Mono<ResponseVO<Double>> getAverageRecall() {
        return testCaseBuilder.buildDevCases()
                .collectList()
                .flatMap(recallEvaluator::evaluateBatch)
                .map(RecallEvaluator.RecallReport::getAvgRecall)
                .map(ResponseBuilder::success);
    }

    @GetMapping("/batch/enhanced")
    public Mono<ResponseVO<EnhancedRecallEvaluator.RecallComparisonReport>> batchEnhancedRecall(
            @RequestParam(defaultValue = "20") int limit) {
        return testCaseBuilder.buildDevCases()
                .take(Math.max(1, limit))
                .collectList()
                .flatMap(enhancedRecallEvaluator::evaluateBatch)
                .map(ResponseBuilder::success);
    }
}
