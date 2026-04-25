package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.kbsystemproject.ailearning.document.splitter.RecursiveCharacterTextSplitter;
import org.example.kbsystemproject.ailearning.infrastructure.rag.Bm25SearchService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final Bm25SearchService bm25SearchService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecursiveCharacterTextSplitter textSplitter = RecursiveCharacterTextSplitter.builder()
            .withChunkSize(400)
            .withMinChunkSizeChars(30)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();

    private volatile boolean running = false;

    public T2DataIngestService(VectorStore pgVectorStore,
                               Bm25SearchService bm25SearchService) {
        this.pgVectorStore = pgVectorStore;
        this.bm25SearchService = bm25SearchService;
    }

    @Override
    public void start() {
        System.out.println("T2DataIngestService (SmartLifecycle) start");
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
        Path finalPath = jsonlPath == null ? Path.of(defaultCorpusPath) : jsonlPath;
        return Mono.fromCallable(() -> {
                    List<Document> chunkedDocuments = loadChunkedCorpus(finalPath);
                    long count = 0;
                    List<Document> batch = new ArrayList<>(batchSize);
                    for (Document document : chunkedDocuments) {
                        batch.add(document);
                        if (batch.size() >= batchSize) {
                            pgVectorStore.add(batch);
                            bm25SearchService.indexDocuments(batch);
                            count += batch.size();
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        pgVectorStore.add(batch);
                        bm25SearchService.indexDocuments(batch);
                        count += batch.size();
                    }
                    return "ingest completed, total documents: " + count;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public List<Document> loadChunkedCorpus(Path jsonlPath) throws Exception {
        if (!Files.exists(jsonlPath)) {
            throw new IllegalArgumentException("Corpus file not found: " + jsonlPath.toAbsolutePath());
        }

        List<Document> documents = new ArrayList<>();
        try (var lines = Files.lines(jsonlPath, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                try {
                    T2CorpusItem item = objectMapper.readValue(line, T2CorpusItem.class);
                    Document document = new Document(
                            item._id(),
                            item.text(),
                            Map.of(
                                    "source", "t2_corpus",
                                    "title", item.title() == null ? "" : item.title(),
                                    "docType", "passage",
                                    "t2_doc_id", item._id()
                            )
                    );
                    documents.addAll(textSplitter.apply(List.of(document)));
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to parse corpus line", e);
                }
            });
        }
        return documents;
    }
}
