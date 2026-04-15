package org.example.kbsystemproject.T2test;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class T2RecallTestCaseBuilder {

    private final T2EvalQrelsService qrelsService;
    private final T2QueryTextService queryTextService;

    public T2RecallTestCaseBuilder(T2EvalQrelsService qrelsService,
                                   T2QueryTextService queryTextService) {
        this.qrelsService = qrelsService;
        this.queryTextService = queryTextService;
    }

    public Flux<RecallTestCase> buildDevCases() {
        return qrelsService.queryIds()
                .flatMap(qid -> {
                    String query = queryTextService.getQueryText(qid).orElse("");
                    return qrelsService.getGroundTruthIds(qid)
                            .collectList()
                            // 优化: 将初始 topK 调大(如50)，获取足够多 Chunk 供后面去重计算 Doc 级 Recall
                            .map(gt -> new RecallTestCase("t2_" + qid, query, gt, 50, 0.0));
                })
                .filter(tc -> !tc.query().isBlank() && !tc.groundTruthIds().isEmpty())
                .take(1000); // 先跑 1000 条做快速验证，避免太慢
    }
}
