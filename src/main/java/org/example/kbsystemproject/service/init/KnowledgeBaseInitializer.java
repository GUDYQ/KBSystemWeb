package org.example.kbsystemproject.service.init;

import org.example.kbsystemproject.base.ai.LocalTokenTextSplit;
import org.example.kbsystemproject.service.component.MDFileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

// ！todo - 目前的实现是一次性加载所有文档并写入向量数据库，后续可以改为增量加载（比如监听文件变化，或者定时扫描目录）
//  - 注意实现不同文件格式的加载器（比如 Word、PDF 等），目前只实现了 Markdown 的加载器
//  - 注意实现不同的文本切分器（比如基于句子、段落、固定长度等），目前只实现了基于 token 的切分器
//  - 结果验证问题：如何验证加载和切分的结果是正确的？可以考虑在开发阶段输出一些日志，或者写一些单元测试来验证加载和切分的逻辑
@Service
public class KnowledgeBaseInitializer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInitializer.class);

    private volatile Boolean isRunning  = false;

    private final VectorStore vectorStore;
    private final MDFileLoader mdFileLoader;
    private final LocalTokenTextSplit localTokenTextSplit;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public KnowledgeBaseInitializer(@Qualifier("PgVectorStore") VectorStore vectorStore, MDFileLoader mdFileLoader,
                                    LocalTokenTextSplit localTokenTextSplit) {
        this.vectorStore = vectorStore;
        this.mdFileLoader = mdFileLoader;
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
//                            .publishOn(Schedulers.boundedElastic())
//                            .doOnNext(this::saveDocuments)
//                            .then();
//                })
//                .doOnSubscribe(sub -> log.info("开始初始化本地知识库 (父子块模式)"))
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
    public int getPhase() {
        return SmartLifecycle.super.getPhase();
    }

    @Override
    public void start() {
        initialize().doOnSuccess(v-> {
            log.info("initialize knowledge base success");
            isRunning = true;
        }).subscribe();
    }

    @Override
    public void stop() {
        isRunning = false;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }
}
