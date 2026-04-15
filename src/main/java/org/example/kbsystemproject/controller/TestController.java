package org.example.kbsystemproject.controller;

import org.example.kbsystemproject.T2test.RecallEvaluator;
import org.example.kbsystemproject.T2test.RecallTestCase;
import org.example.kbsystemproject.T2test.T2RecallTestCaseBuilder;
import org.example.kbsystemproject.base.response.ResponseBuilder;
import org.example.kbsystemproject.base.response.ResponseVO;
import org.example.kbsystemproject.service.AgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestController {

    private final AgentService agentService;
    private final RecallEvaluator recallEvaluator;
    private final T2RecallTestCaseBuilder testCaseBuilder;


    public TestController(AgentService agentService, RecallEvaluator recallEvaluator,
                          T2RecallTestCaseBuilder testCaseBuilder, RecallEvaluator recallEvaluator1, T2RecallTestCaseBuilder testCaseBuilder1) {
        this.agentService = agentService;
        this.recallEvaluator = recallEvaluator1;
        this.testCaseBuilder = testCaseBuilder1;
    }

    @GetMapping("/hello")
    public Mono<ResponseVO<String>> hello() {
        return Mono.just(ResponseBuilder.success("hello"));
    }

    @PostMapping("/ai/search")
    public Flux<ResponseVO<Map<String, Object>>> aiSearch(@RequestBody Map<String, String> payload) {
        String query = payload.get("query");
        return agentService.searchSimilarity(query)
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

}
