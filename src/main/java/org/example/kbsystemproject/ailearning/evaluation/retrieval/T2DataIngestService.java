package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.document.splitter.RecursiveCharacterTextSplitter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
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

    public record T2CorpusItem(String _id, String text, String title, Boolean is_positive) {
    }

    public record T2QueryItem(String _id, String text) {
    }

    @Value("${t2.corpus.path:C:\\Mywork\\MustDo\\Learning\\dataset\\t2_mini_corpus.jsonl}")
    private String defaultCorpusPath;

    private final VectorStore pgVectorStore;
    private final ObjectMapper om = new ObjectMapper();
    private volatile boolean running = false;
    private final RecursiveCharacterTextSplitter textSplitter = RecursiveCharacterTextSplitter.builder()
            .withChunkSize(400)
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
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public Mono<String> ingestCorpus(Path jsonlPath, int batchSize) {
        if (jsonlPath == null) {
            jsonlPath = Path.of(defaultCorpusPath);
        }
        Path finalPath = jsonlPath;
        return Mono.fromCallable(() -> {
                    long[] count = {0};
                    if (!java.nio.file.Files.exists(finalPath)) {
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
                                                "t2_doc_id", item._id()
                                        )
                                );

                                List<Document> chunkedDocs = textSplitter.apply(List.of(doc));
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
                        e.printStackTrace();
                        return "读取文件异常: " + e.getMessage();
                    }

                    if (!batch.isEmpty()) {
                        pgVectorStore.add(batch);
                        count[0] += batch.size();
                    }

                    return "导入完成，共 " + count[0] + " 篇文档。";
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
