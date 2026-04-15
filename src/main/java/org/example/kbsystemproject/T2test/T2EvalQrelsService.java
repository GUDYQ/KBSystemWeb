package org.example.kbsystemproject.T2test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class T2EvalQrelsService implements SmartLifecycle {

    @Value("${t2.eval.qrels.path:C:\\Mywork\\MustDo\\Learning\\dataset\\t2_mini_qrels.json}")
    private String qrelsPath;

    private Map<String, List<String>> qid2positives = Map.of(); // query-id -> 正例 doc ID 列表
    private volatile boolean isRunning = false;

    @Override
    public void start() {
        System.out.println("T2EvalQrelsService (SmartLifecycle) 开始初始化...");
        Mono.fromCallable(() -> {
            System.out.println("准备读取 qrels 文件: " + qrelsPath);
            ObjectMapper om = new ObjectMapper();
            Path path = Path.of(qrelsPath);
            if (java.nio.file.Files.exists(path)) {
                System.out.println("文件找到，开始解析...");
                // 指定字符集 UTF-8 读取文件解决乱码问题
                try (java.io.Reader reader = new java.io.InputStreamReader(
                        new java.io.FileInputStream(path.toFile()), java.nio.charset.StandardCharsets.UTF_8)) {
                    return om.readValue(reader,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {});
                }
            }
            System.out.println("文件不存在: " + qrelsPath);
            return Map.<String, List<String>>of();
        })
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            data -> {
                this.qid2positives = data;
                System.out.println("读取 qrels 完成，共加载 " + data.size() + " 条记录。");
                this.isRunning = true;
            },
            error -> {
                System.err.println("读取 qrels 时发生错误:");
                error.printStackTrace();
                this.isRunning = true;
            }
        );
    }

    @Override
    public void stop() {
        this.isRunning = false;
    }

    @Override
    public boolean isRunning() {
        return this.isRunning;
    }

    public Flux<String> getGroundTruthIds(String queryId) {
        return Flux.fromIterable(qid2positives.getOrDefault(queryId, List.of()));
    }

    public Flux<String> queryIds() {
        return Flux.fromIterable(qid2positives.keySet());
    }
}
