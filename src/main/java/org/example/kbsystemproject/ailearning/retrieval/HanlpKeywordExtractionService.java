package org.example.kbsystemproject.ailearning.retrieval;

import com.hankcs.hanlp.restful.HanLPClient;
import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.config.HanlpProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class HanlpKeywordExtractionService {

    private final HanlpProperties properties;

    public HanlpKeywordExtractionService(HanlpProperties properties) {
        this.properties = properties;
    }

    public List<String> extractKeywords(String text) {
        if (!properties.isEnabled() || text == null || text.isBlank()) {
            return List.of();
        }
        try {
            HanLPClient client = new HanLPClient(properties.getBaseUrl(), properties.getAuth());
            Map<String, Double> keywords = client.keyphraseExtraction(text, Math.max(1, properties.getTopKeywords()));
            return keywords.keySet().stream()
                    .map(String::trim)
                    .filter(keyword -> !keyword.isBlank())
                    .toList();
        } catch (IOException e) {
            log.warn("HanLP keyword extraction failed, falling back to local keyword extraction", e);
            return List.of();
        }
    }
}
