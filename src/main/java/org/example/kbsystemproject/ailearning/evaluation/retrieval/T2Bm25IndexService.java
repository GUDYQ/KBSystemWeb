package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.ailearning.infrastructure.rag.Bm25SearchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class T2Bm25IndexService {

    @Value("${t2.corpus.path:C:\\Mywork\\MustDo\\Learning\\dataset\\t2_mini_corpus.jsonl}")
    private String corpusPath;

    private final Bm25SearchService bm25SearchService;
    private final T2DataIngestService t2DataIngestService;
    private final AtomicReference<String> indexedCorpusPath = new AtomicReference<>();

    public T2Bm25IndexService(Bm25SearchService bm25SearchService,
                              T2DataIngestService t2DataIngestService) {
        this.bm25SearchService = bm25SearchService;
        this.t2DataIngestService = t2DataIngestService;
    }

    public Mono<Void> ensureIndexed() {
        return Mono.fromRunnable(() -> {
                    Path path = Path.of(corpusPath);
                    if (!Files.exists(path)) {
                        log.warn("Skip BM25 index build because corpus file does not exist: {}", path);
                        return;
                    }
                    String normalizedPath = path.toAbsolutePath().normalize().toString();
                    if (normalizedPath.equals(indexedCorpusPath.get()) && bm25SearchService.hasDocuments()) {
                        return;
                    }
                    try {
                        bm25SearchService.replaceAll(t2DataIngestService.loadChunkedCorpus(path));
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to build BM25 corpus index", e);
                    }
                    indexedCorpusPath.set(normalizedPath);
                    log.info("BM25 corpus index ready. path={}", normalizedPath);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
