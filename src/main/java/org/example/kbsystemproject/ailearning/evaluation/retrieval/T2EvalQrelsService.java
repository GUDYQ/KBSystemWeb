package org.example.kbsystemproject.ailearning.evaluation.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class T2EvalQrelsService implements SmartLifecycle {

    @Value("${t2.eval.qrels.path:C:\\Mywork\\MustDo\\Learning\\dataset\\t2_mini_qrels.json}")
    private String qrelsPath;

    private Map<String, List<String>> qid2positives = Map.of();
    private volatile boolean running = false;

    @Override
    public void start() {
        Mono.fromCallable(() -> {
                    ObjectMapper om = new ObjectMapper();
                    Path path = Path.of(qrelsPath);
                    if (java.nio.file.Files.exists(path)) {
                        try (InputStreamReader reader = new InputStreamReader(
                                new FileInputStream(path.toFile()), StandardCharsets.UTF_8)) {
                            return om.readValue(reader,
                                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {
                                    });
                        }
                    }
                    return Map.<String, List<String>>of();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        data -> {
                            this.qid2positives = data;
                            this.running = true;
                        },
                        error -> {
                            error.printStackTrace();
                            this.running = true;
                        }
                );
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public Flux<String> getGroundTruthIds(String queryId) {
        return Flux.fromIterable(qid2positives.getOrDefault(queryId, List.of()));
    }

    public Flux<String> queryIds() {
        return Flux.fromIterable(qid2positives.keySet());
    }
}
