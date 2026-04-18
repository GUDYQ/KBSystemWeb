package org.example.kbsystemproject.ailearning.infrastructure.rag;

import org.example.kbsystemproject.ailearning.document.LocalTokenTextSplit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LearningKnowledgeBaseInitializer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LearningKnowledgeBaseInitializer.class);

    private volatile boolean running = false;

    private final VectorStore vectorStore;
    private final LearningDocumentLoader learningDocumentLoader;
    private final LocalTokenTextSplit localTokenTextSplit;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public LearningKnowledgeBaseInitializer(@Qualifier("PgVectorStore") VectorStore vectorStore,
                                            LearningDocumentLoader learningDocumentLoader,
                                            LocalTokenTextSplit localTokenTextSplit) {
        this.vectorStore = vectorStore;
        this.learningDocumentLoader = learningDocumentLoader;
        this.localTokenTextSplit = localTokenTextSplit;
    }

    public Mono<Void> initialize() {
        return Mono.empty();
//        return Mono.defer(() -> {
//                    if (!initialized.compareAndSet(false, true)) {
//                        return Mono.empty();
//                    }
//                    return mdFileLoader.loadMarkdowns()
//                            .collectList()
//                            .doOnNext(localTokenTextSplit::splitMarkdownDocument)
//                            .publishOn(Schedulers.boundedElastic())
//                            .doOnNext(this::saveDocuments)
//                            .then();
//                })
//                .doOnSubscribe(sub -> log.info("开始初始化本地知识库"))
//                .doOnSuccess(v -> log.info("本地知识库初始化完成"))
//                .doOnError(e -> {
//                    initialized.set(false);
//                    log.error("本地知识库初始化失败", e);
//                });
    }

    private void saveDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            log.info("没有可写入本地知识库的文档");
            return;
        }
        vectorStore.add(documents);
    }

    @Override
    public void start() {
        initialize().doOnSuccess(v -> {
            log.info("initialize knowledge base success");
            running = true;
        }).subscribe();
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
