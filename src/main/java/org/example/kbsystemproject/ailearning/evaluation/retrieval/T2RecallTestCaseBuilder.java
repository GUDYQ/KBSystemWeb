package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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
                            .map(gt -> new RecallTestCase("t2_" + qid, query, gt, 50, 0.0));
                })
                .filter(tc -> !tc.query().isBlank() && !tc.groundTruthIds().isEmpty())
                .take(1000);
    }
}
