package org.example.kbsystemproject.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai-learning.intent")
public class IntentRecognitionProperties {

    private boolean enabled = true;
    private boolean llmFallbackEnabled = false;
    private double llmConfidenceThreshold = 0.65D;
    private int followUpPromptLengthThreshold = 24;
}
