package org.example.kbsystemproject.ailearning.application.service;

import java.util.List;
import java.util.Map;

public record EnhancedQueryPlan(
        String originalQuery,
        String rewrittenQuery,
        List<String> retrievalQueries,
        Map<String, Object> metadata
) {
}
