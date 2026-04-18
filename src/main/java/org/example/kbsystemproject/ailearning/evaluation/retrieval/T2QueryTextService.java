package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@Service
public class T2QueryTextService implements SmartLifecycle {

    @Value("${t2.query.text.path:C:\\Mywork\\MustDo\\Learning\\dataset\\t2_mini_queries.jsonl}")
    private String queriesPath;

    private Map<String, String> qid2text = Map.of();
    private volatile boolean running = false;

    @Override
    public void start() {
        try {
            ObjectMapper om = new ObjectMapper();
            Path path = Path.of(queriesPath);
            if (!java.nio.file.Files.exists(path)) {
                running = true;
                return;
            }

            Map<String, String> map = new java.util.HashMap<>();
            try (var lines = java.nio.file.Files.lines(path, StandardCharsets.UTF_8)) {
                lines.forEach(line -> {
                    try {
                        T2DataIngestService.T2QueryItem item = om.readValue(line, T2DataIngestService.T2QueryItem.class);
                        map.put(item._id(), item.text());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
            qid2text = Map.copyOf(map);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            running = true;
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public Optional<String> getQueryText(String queryId) {
        return Optional.ofNullable(qid2text.get(queryId));
    }
}
