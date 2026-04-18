package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import java.util.List;

public record RecallTestCase(
        String caseId,
        String query,
        List<String> groundTruthIds,
        int topK,
        double threshold
) {
    public RecallTestCase(String caseId, String query, List<String> groundTruthIds) {
        this(caseId, query, groundTruthIds, 5, 0.0);
    }
}
