package org.example.kbsystemproject.T2test;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@Service
public class T2QueryTextService implements SmartLifecycle {

    @Value("${t2.query.text.path:C:\\Mywork\\MustDo\\Learning\\dataset\\t2_mini_queries.jsonl}")
    private String queriesPath;

    private Map<String, String> qid2text = Map.of(); // query-id -> query text
    private volatile boolean isRunning = false;

    @Override
    public void start() {
        System.out.println("T2QueryTextService (SmartLifecycle) 开始初始化...");
        try {
            System.out.println("准备读取 queries 文件: " + queriesPath);
            ObjectMapper om = new ObjectMapper();
            Path path = Path.of(queriesPath);
            if (!java.nio.file.Files.exists(path)) {
                System.out.println("queries 文件不存在: " + queriesPath);
                this.isRunning = true;
                return;
            }

            Map<String, String> map = new java.util.HashMap<>();
            try (var lines = java.nio.file.Files.lines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                System.out.println("文件找到，开始解析 queries...");
                lines.forEach(line -> {
                    try {
                        T2DataIngestService.T2QueryItem item = om.readValue(line, T2DataIngestService.T2QueryItem.class);
                        map.put(item._id(), item.text());
                    } catch (Exception e) {
                        System.err.println("解析行出错: " + line);
                        e.printStackTrace();
                    }
                });
            }
            this.qid2text = Map.copyOf(map);
            System.out.println("读取 queries 完成，共加载 " + this.qid2text.size() + " 条记录。");
        } catch (Exception e) {
            System.err.println("读取 queries 时发生错误: " + queriesPath);
            e.printStackTrace();
        } finally {
            this.isRunning = true;
        }
    }

    @Override
    public void stop() {
        this.isRunning = false;
    }

    @Override
    public boolean isRunning() {
        return this.isRunning;
    }

    public Optional<String> getQueryText(String queryId) {
        return Optional.ofNullable(qid2text.get(queryId));
    }
}
