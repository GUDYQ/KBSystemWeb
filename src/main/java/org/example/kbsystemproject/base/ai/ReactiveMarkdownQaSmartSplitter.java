package org.example.kbsystemproject.base.ai;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReactiveMarkdownQaSmartSplitter {

    // 正则：匹配 1 到 6 个 # 号开头的标题行（支持标题前后有空格的情况）
    // 使用 MULTILINE 模式，让 ^ 和 $ 匹配每行的开头和结尾
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(\\s*#{1,6}\\s+.+)$", Pattern.MULTILINE);

    private final TokenTextSplitter tokenSplitter;

    public ReactiveMarkdownQaSmartSplitter() {
        this.tokenSplitter = TokenTextSplitter.builder()
                .withChunkSize(300)
                .withMinChunkSizeChars(100)
                .withKeepSeparator(true)
                .build();
    }

    public Flux<Document> split(List<Document> originalDocs) {
        return Flux.fromStream(originalDocs.stream())
                .flatMap(this::processSingleDocument);
    }

    private Flux<Document> processSingleDocument(Document doc) {
        return Mono.fromCallable(() -> doBlockingSplit(doc))
                .subscribeOn(Schedulers.boundedElastic()) // 保护 WebFlux
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * 核心同步切分逻辑
     */
    private List<Document> doBlockingSplit(Document originalDoc) {
        List<Document> finalChunks = new ArrayList<>();
        String text = originalDoc.getText();

        // 1. 继承原始元数据 (如 file_name 等)
        Map<String, Object> baseMetadata = new HashMap<>(originalDoc.getMetadata());

        // 2. 按照【所有级别】的 Markdown 标题进行强切
        // 零宽断言：匹配标题行的开头位置进行 split
        String[] blocks = null;
        if (text != null) {
            blocks = text.split("(?=^\\s*#{1,6}\\s+)", Pattern.MULTILINE);
        }

        int globalChunkIndex = 0;

        if (blocks != null) {
            for (String block : blocks) {
                block = block.trim();
                if (block.isEmpty()) continue;

                // 3. 提取标题（作为问题）和正文（作为答案）
                String rawHeading = ""; // 原始标题行，例如 "### 如何退款？"
                String pureQuestion = ""; // 纯净问题，例如 "如何退款？"
                String answer = block;

                Matcher matcher = HEADING_PATTERN.matcher(block);
                if (matcher.find()) {
                    rawHeading = matcher.group(1).trim();
                    // 去掉开头的 # 号和空格，得到纯文本问题
                    pureQuestion = rawHeading.replaceAll("^#+\\s*", "").trim();
                    // 答案就是去掉标题行之后剩余的所有文本
                    answer = block.substring(matcher.end()).trim();
                }

                // 4. 短文本策略：直接保留原生 Markdown 格式（带标题）
                if (block.length() < 400) {
                    Map<String, Object> chunkMeta = new HashMap<>(baseMetadata);
                    chunkMeta.put("chunk_index", globalChunkIndex++);
                    chunkMeta.put("question_title", pureQuestion); // 将提取出的纯问题存入元数据

                    finalChunks.add(new Document(block, chunkMeta));
                    continue;
                }

                // 5. 长文本策略：对答案部分进行二次切片
                Document tempAnswerDoc = new Document(answer);
                List<Document> answerFragments = tokenSplitter.apply(List.of(tempAnswerDoc));

                for (Document fragment : answerFragments) {
                    // 重新拼接：保留原始的 Markdown 标题！
                    // 为什么保留？因为 text-embedding-v2 能识别 Markdown 标题的权重，
                    // 标题通常代表这段文本的核心主题，保留 # 号有助于提升向量质量。
                    String combinedText = rawHeading + "\n\n" + fragment.getText();

                    Map<String, Object> chunkMeta = new HashMap<>(baseMetadata);
                    chunkMeta.put("chunk_index", globalChunkIndex++);
                    chunkMeta.put("question_title", pureQuestion); // 纯问题存入元数据
                    chunkMeta.put("split_type", "LONG_ANSWER_CHUNK");

                    finalChunks.add(new Document(combinedText, chunkMeta));
                }
            }
        }

        return finalChunks;
    }
}
