package org.example.kbsystemproject.service.component;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.List;

@Component
@Slf4j
public class MDFileLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    MDFileLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public Flux<Document> loadMarkdowns() {
        return Mono.fromCallable(() -> resourcePatternResolver.getResources("classpath:document/*.md"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromArray)
                .flatMap(this::readOneMarkdown)
                .onErrorResume(IOException.class, e -> {
                    log.error("Markdown 文档加载失败", e);
                    return Flux.empty();
                });
    }

    private Flux<Document> readOneMarkdown(Resource resource) {
        return Mono.fromCallable(() -> {
                    String fileName = resource.getFilename();
                    MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                            .withHorizontalRuleCreateDocument(true)
                            .withIncludeCodeBlock(false)
                            .withIncludeBlockquote(false)
                            .withAdditionalMetadata("filename", fileName == null ? "unknown" : fileName)
                            .build();
                    MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                    return reader.get();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("读取 Markdown 资源失败: {}", resource.getFilename(), e);
                    return Mono.just(List.of());
                })
                .flatMapMany(Flux::fromIterable);
    }
}
