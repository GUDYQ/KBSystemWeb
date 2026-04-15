package org.example.kbsystemproject.T2test;

import java.util.List;

public record RecallTestCase(
        String caseId,                  // 用例ID
        String query,                   // 用户查询
        List<String> groundTruthIds,    // 标准答案文档ID列表（应该被召回的）
        int topK,                       // 检索多少条
        double threshold                // 相似度阈值
) {
    // 便捷构造，默认 topK=5, threshold=0.0
    public RecallTestCase(String caseId, String query, List<String> groundTruthIds) {
        this(caseId, query, groundTruthIds, 5, 0.0);
    }
}
