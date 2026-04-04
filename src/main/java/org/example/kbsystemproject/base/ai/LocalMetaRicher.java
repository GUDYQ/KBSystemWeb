package org.example.kbsystemproject.base.ai;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocalMetaRicher {
    @Resource
    private ChatModel chatModel;

    public List<Document> enrichMetadata(List<Document> documents) {
        KeywordMetadataEnricher keywordMetadataEnricher =
                new KeywordMetadataEnricher(chatModel, 5);
        return keywordMetadataEnricher.apply(documents);
    }
}
