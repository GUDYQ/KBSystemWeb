package org.example.kbsystemproject.T2test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.base.ai.agent.RecursiveCharacterTextSplitter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class T2DataIngestService implements SmartLifecycle {

    public record T2CorpusItem(String _id, String text, String title, Boolean is_positive) {}
    public record T2QueryItem(String _id, String text) {}

    @Value("${t2.corpus.path:C:\\Mywork\\MustDo\\Learning\\dataset\\t2_mini_corpus.jsonl}")
    private String defaultCorpusPath;

    private final VectorStore pgVectorStore;
    private final ObjectMapper om = new ObjectMapper();
    private volatile boolean isRunning = false;
    private final RecursiveCharacterTextSplitter textSplitter = RecursiveCharacterTextSplitter.builder()
            .withChunkSize(400) // 考虑到模型最大 512 tokens 限制，安全起见按中文字符占用估算将按字符切分长度调整为 400
            .withMinChunkSizeChars(30)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();

    public T2DataIngestService(VectorStore pgVectorStore) {
        this.pgVectorStore = pgVectorStore;
    }

    @Override
    public void start() {
        System.out.println("T2DataIngestService (SmartLifecycle) 开始初始化...");
//        this.ingestCorpus(null, 500)
//            .doOnSuccess(msg -> {
//                System.out.println("T2DataIngestService 导入成功: " + msg);
//                this.isRunning = true;
//            })
//            .doOnError(e -> {
//                System.err.println("T2DataIngestService 导入失败!");
//                e.printStackTrace();
//                this.isRunning = true;
//            })
//            .subscribe();
    }

    @Override
    public void stop() {
        this.isRunning = false;
    }

    @Override
    public boolean isRunning() {
        return this.isRunning;
    }

    /**
     * 把 t2_corpus.jsonl 导入到 VectorStore
     * 文档 ID 使用 T2 数据集的原始 _id
     */
    public Mono<String> ingestCorpus(Path jsonlPath, int batchSize) {
        if (jsonlPath == null) {
            jsonlPath = Path.of(defaultCorpusPath);
        }
        final Path finalPath = jsonlPath;
        return Mono.fromCallable(() -> {
                    long[] count = {0};
                    System.out.println("开始读取文件: " + finalPath.toAbsolutePath());
                    if (!java.nio.file.Files.exists(finalPath)) {
                        System.out.println("文件不存在: " + finalPath.toAbsolutePath());
                        return "文件不存在: " + finalPath.toAbsolutePath();
                    }

                    List<Document> batch = new java.util.ArrayList<>(batchSize);
                    try (var lines = java.nio.file.Files.lines(finalPath, java.nio.charset.StandardCharsets.UTF_8)) {
                        lines.forEach(line -> {
                            try {
                                T2CorpusItem item = om.readValue(line, T2CorpusItem.class);
                                Document doc = new Document(
                                        item._id(),
                                        item.text(),
                                        Map.of(
                                                "source", "t2_corpus",
                                                "title", item.title() != null ? item.title() : "",
                                                "docType", "passage",
                                                "t2_doc_id", item._id() // 将原始真实 ID 存在元数据里，防止切分后 ID 丢失 (或被覆盖)
                                        )
                                );

                                // 切分长文本以避免 token 超限
                                List<Document> chunkedDocs = textSplitter.apply(List.of(doc));

                                // 保证所有切片有自己唯一的 ID 获取写入向量数据库，否则会被覆盖
                                // 但是它们都携带着 "t2_doc_id" 这个原 ID 供后续评估使用
                                // 注意如果文档不需要切分，返回原文档的 ID 则不会改变，
                                // 为了防止和其他 chunk 冲突，强制重置一下无妨，或者就依赖 Transformer 的处理
                                batch.addAll(chunkedDocs);

                                if (batch.size() >= batchSize) {
                                    pgVectorStore.add(batch);
                                    count[0] += batch.size();
                                    batch.clear();
                                }
                            } catch (Exception e) {
                                System.err.println("处理该行时出现异常: " + line);
                                e.printStackTrace();
                            }
                        });
                    } catch (Exception e) {
                        System.err.println("读取文件时出现异常: " + finalPath.toAbsolutePath());
                        e.printStackTrace();
                        return "读取文件异常: " + e.getMessage();
                    }

                    // 最后一批
                    if (!batch.isEmpty()) {
                        pgVectorStore.add(batch);
                        count[0] += batch.size();
                    }

                    return "导入完成，共 " + count[0] + " 篇文档。";
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
