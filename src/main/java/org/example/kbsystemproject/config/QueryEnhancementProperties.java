package org.example.kbsystemproject.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai-learning.query-enhancement")
public class QueryEnhancementProperties {

    private boolean enabled = true;
    private boolean llmEnabled = true;
    private boolean rewriteEnabled = true;
    private boolean multiQueryEnabled = true;
    private int multiQueryCount = 3;
    private boolean includeOriginal = true;
    private int maxQueries = 4;
    private String targetSearchSystem = "knowledge base";
}
