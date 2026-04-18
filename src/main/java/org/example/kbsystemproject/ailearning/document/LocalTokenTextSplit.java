package org.example.kbsystemproject.ailearning.document;

import org.example.kbsystemproject.ailearning.document.splitter.ReactiveMarkdownQaSmartSplitter;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Component
public class LocalTokenTextSplit {

    private final ReactiveMarkdownQaSmartSplitter markdownSplitter
            = new ReactiveMarkdownQaSmartSplitter();

    public Flux<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return Mono.fromCallable(() -> splitter.apply(documents))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    public Flux<Document> splitMarkdownDocument(List<Document> documents) {
        return markdownSplitter.split(documents);
    }
}
