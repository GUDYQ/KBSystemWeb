package org.example.kbsystemproject.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.config.MemoryProperties;
import org.example.kbsystemproject.service.component.RedisShortTermMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 长期记忆服务
 *
 * ⚠️ 核心设计决策：
 * Spring AI 的 VectorStore 接口是同步阻塞的（底层 JdbcTemplate），
 * 暂无 R2DBC 实现。因此本服务中所有 VectorStore 操作都通过
 * Schedulers.boundedElastic() 包装为响应式，确保不阻塞 Netty 事件循环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService {

    private final VectorStore vectorStore;
    private final RedisShortTermMemory shortTermMemory;
    private final MemoryProperties properties;

    private final FilterExpressionBuilder filterBuilder = new FilterExpressionBuilder();

    /**
     * 将短期记忆溢出的消息批量存入 PGVector
     *
     * 阻塞点：vectorStore.add() → JdbcTemplate insert + Embedding API 调用
     * 通过 boundedElastic 隔离，不影响 Netty 线程
     */
    public Mono<Void> evictToLongTerm(String conversationId,
                                      List<Message> entries) {
        if (entries == null || entries.isEmpty()) {
            return Mono.empty();
        }

        return Mono.<Void>fromRunnable(() -> {
                    List<Document> documents = entries.stream()
                            .map(entry -> Document.builder()
                                    // 给 document 生成一个唯一ID，或者用默认
                                    .id(java.util.UUID.randomUUID().toString())
                                    .text(entry.getText())
                                    .metadata(buildMetadata(
                                            conversationId,
                                            entry.getMessageType().getValue(),
                                            "MESSAGE"
                                    ))
                                    .build())
                            .collect(Collectors.toList());

                    // 阻塞调用：JDBC insert + OpenAI Embedding API
                    vectorStore.add(documents);
                    log.info("移入长期记忆: conversationId={}, count={}",
                            conversationId, entries.size());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 存储摘要到 PGVector
     */
    public Mono<Void> storeSummary(String conversationId, String summary) {
        return Mono.<Void>fromRunnable(() -> {
                    Document doc = Document.builder()
                            .text(summary)
                            .metadata(buildMetadata(conversationId, "SYSTEM", "SUMMARY"))
                            .build();
                    vectorStore.add(List.of(doc));
                    log.info("存储摘要: conversationId={}, length={}", conversationId, summary.length());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 语义检索相关长期记忆
     *
     * 阻塞点：vectorStore.similaritySearch() → JDBC query + 向量距离计算
     * 返回 Flux，下游可以按需取用
     */
    public Flux<ScoredMemory> searchRelevant(String conversationId, String query) {
        return Mono.<List<Document>>fromCallable(() -> {
                    MemoryProperties.LongTerm config = properties.getLongTerm();

                    // 构建 JSONB 过滤条件：conversationId = 'xxx'
                    var filter = filterBuilder.eq("conversationId", conversationId).build();

                    SearchRequest searchRequest = SearchRequest.builder()
                            .query(query)
                            .topK(config.getTopK() * 3)    // 多取，二次排序后裁剪
                            .similarityThreshold(config.getSimilarityThreshold())
                            .filterExpression(filter)
                            .build();

                    // 阻塞调用：JDBC 查询 PGVector
                    return vectorStore.similaritySearch(searchRequest);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .map(doc -> {
                    double similarity = doc.getScore() != null ? doc.getScore() : 0.0;
                    double timeDecay = calculateTimeDecay(doc.getMetadata());
                    double finalScore = similarity * timeDecay;
                    return new ScoredMemory(doc, similarity, timeDecay, finalScore);
                })
                .sort(Comparator.comparingDouble(ScoredMemory::finalScore).reversed())
                .take(properties.getLongTerm().getTopK());
    }

    /**
     * 按会话ID删除所有长期记忆（管理接口用）
     */
    public Mono<Integer> deleteByConversation(String conversationId) {
        return Mono.fromCallable(() -> {
                    var filter = filterBuilder.eq("conversationId", conversationId).build();
                    // Spring AI 1.0 的 VectorStore 暂无 delete(filter) 方法
                    // 需要自行通过 JdbcTemplate 执行 SQL
                    // 这里给出思路：
                    // String sql = "DELETE FROM vector_store WHERE metadata->>'conversationId' = ?";
                    // return jdbcTemplate.update(sql, conversationId);
                    log.warn("deleteByConversation 需要注入 JdbcTemplate 手动实现");
                    return 0;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Boolean> shouldSummarize(String conversationId) {
        if (!properties.getSummary().isEnabled()) {
            return Mono.just(false);
        }
        return shortTermMemory.getTotalTurnsMono(conversationId)
                .map(turns -> turns >= properties.getSummary().getTriggerTurns());
    }

    // ==================== 内部方法 ====================

    /**
     * 时间衰减：exp(-λ × daysSince)
     * λ=0.03: 7天→0.81, 30天→0.41, 90天→0.07
     * λ=0.05: 7天→0.70, 30天→0.22, 90天→0.01
     */
    private double calculateTimeDecay(Map<String, Object> metadata) {
        double lambda = properties.getLongTerm().getTimeDecayLambda();
        if (lambda <= 0) return 1.0;

        Object tsObj = metadata.get("timestamp");
        if (tsObj == null) return 1.0;

        try {
            Instant createdAt = Instant.parse(tsObj.toString());
            long days = Duration.between(createdAt, Instant.now()).toDays();
            return Math.exp(-lambda * days);
        } catch (Exception e) {
            return 1.0;
        }
    }

    private Map<String, Object> buildMetadata(String conversationId, String role, String type) {
        Map<String, Object> m = new LinkedHashMap<>(8);
        m.put("conversationId", conversationId);
        m.put("role", role);
        m.put("type", type);
        m.put("timestamp", Instant.now().toString());
        return m;
    }

    public record ScoredMemory(
            Document document,
            double similarity,
            double timeDecay,
            double finalScore
    ) {}
}
