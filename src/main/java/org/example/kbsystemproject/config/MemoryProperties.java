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
    private Compression compression = new Compression();
    private Concurrency concurrency = new Concurrency();
    private Request request = new Request();
    private Async async = new Async();

    @Data
    public static class ShortTerm {
        private int maxTurns = 10;
        private long ttlHours = 72;
    }

    @Data
    public static class LongTerm {
        private boolean enabled = true;
        private int topK = 5;
        private double similarityThreshold = 0.45;
        private double timeDecayLambda = 0.03;
        private int collectionTurns = 6;
        private int collectionMinTokens = 180;
        private int maxCandidatesPerTurn = 2;
        private double explicitMinConfidence = 0.9;
        private double structuredMinConfidence = 0.85;
        private double behavioralMinConfidence = 0.9;
        private double inferredMinConfidence = 0.95;
        private int minEvidenceCount = 1;
        private int inferredMinEvidenceCount = 2;
        private int promotionBatchSize = 20;
        private int promotionMinDistinctTurns = 2;
        private int promotionMinDistinctSessions = 2;
        private double recallMinConfidence = 0.85;
        private int blankQueryTopK = 2;
    }

    @Data
    public static class Summary {
        private boolean enabled = true;
        private int triggerTurns = 20;
        private int maxInputTokensEstimate = 6000;
    }

    @Data
    public static class Compression {
        private boolean enabled = true;
        private int recentRawTurns = 6;
        private int whiteboardRefreshTurns = 3;
        private int whiteboardRefreshMinTokens = 120;
        private int shortTermTriggerTurns = 8;
        private int topicBlockMinTurns = 3;
        private double lowInfoRatioThreshold = 0.5;
        private int unresolvedThreshold = 2;
        private long idleSummaryMinutes = 30;
        private int longTermArchiveMinTurns = 3;
    }

    @Data
    public static class Concurrency {
        private boolean enabled = true;
        private long lockWaitMillis = 3000;
        private long lockLeaseMillis = 15000;
    }

    @Data
    public static class Request {
        private long processingLeaseSeconds = 600;
    }

    @Data
    public static class Async {
        private long fixedDelayMillis = 3000;
        private int batchSize = 20;
        private long leaseSeconds = 120;
        private long retryDelaySeconds = 30;
    }
}
