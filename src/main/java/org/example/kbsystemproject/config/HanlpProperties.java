package org.example.kbsystemproject.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai-learning.hanlp")
public class HanlpProperties {

    private boolean enabled = false;
    private String baseUrl = "https://www.hanlp.com/api";
    private String auth = "";
    private String language = "zh";
    private int topKeywords = 6;
}
