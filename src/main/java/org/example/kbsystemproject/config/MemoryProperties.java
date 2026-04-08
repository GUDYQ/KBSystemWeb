package org.example.kbsystemproject.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {

    private ShortTerm shortTerm = new ShortTerm();
    private LongTerm longTerm = new LongTerm();
    private Summary summary = new Summary();

    @Data
    public static class ShortTerm {
        private int maxTurns = 10;
    }

    @Data
    public static class LongTerm {
        private int topK = 5;
        private double similarityThreshold = 0.45;
        private double timeDecayLambda = 0.03;
    }

    @Data
    public static class Summary {
        private boolean enabled = true;
        private int triggerTurns = 20;
    }
}
