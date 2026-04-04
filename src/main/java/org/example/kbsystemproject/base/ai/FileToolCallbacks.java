package org.example.kbsystemproject.base.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FileToolCallbacks {

    private static final Logger log = LoggerFactory.getLogger(FileToolCallbacks.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path workspaceRoot;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FileToolCallbacks() {
        this.workspaceRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public ToolCallback readFileToolCallback() {
        return new SimpleToolCallback(
                ToolDefinition.builder()
                        .name("read_file")
                        .description("读取工作区内指定文件的内容，返回文本字符串。")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"文件路径\"}},\"required\":[\"path\"]}")
                        .build(),
                rawArgs -> executeReactive(rawArgs, args -> {
                    String path = requireString(args, "path");
                    Path filePath = resolvePath(path);
                    if (!Files.exists(filePath)) {
                        return Mono.just("ERROR: 文件不存在: " + filePath);
                    }
                    if (!Files.isRegularFile(filePath)) {
                        return Mono.just("ERROR: 不是普通文件: " + filePath);
                    }
                    return Mono.fromCallable(() -> Files.readString(filePath, StandardCharsets.UTF_8));
                })
        );
    }

    public ToolCallback saveFileToolCallback() {
        return new SimpleToolCallback(
                ToolDefinition.builder()
                        .name("save_file")
                        .description("将文本内容保存到指定文件。若目录不存在会自动创建。")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"},\"append\":{\"type\":\"boolean\",\"default\":false}},\"required\":[\"path\",\"content\"]}")
                        .build(),
                rawArgs -> executeReactive(rawArgs, args -> {
                    String path = requireString(args, "path");
                    String content = requireString(args, "content");
                    boolean append = Boolean.parseBoolean(String.valueOf(args.getOrDefault("append", false)));
                    Path filePath = resolvePath(path);
                    return Mono.fromCallable(() -> {
                        Path parent = filePath.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        if (append && Files.exists(filePath)) {
                            Files.writeString(filePath, content, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                        } else {
                            Files.writeString(filePath, content, StandardCharsets.UTF_8);
                        }
                        log.info("file saved: {}", filePath);
                        return "OK: 文件已保存: " + filePath;
                    });
                })
        );
    }

    private Mono<String> executeReactive(String rawArgs, java.util.function.Function<Map<String, Object>, Mono<String>> action) {
        return Mono.fromCallable(() -> parseArgs(rawArgs))
                .flatMap(action)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> Mono.just("ERROR: " + ex.getMessage()));
    }

    private Map<String, Object> parseArgs(String rawArgs) {
        try {
            if (rawArgs == null || rawArgs.isBlank()) {
                return Map.of();
            }
            Map<String, Object> parsed = objectMapper.readValue(rawArgs, MAP_TYPE);
            return new LinkedHashMap<>(parsed);
        } catch (Exception ex) {
            throw new IllegalArgumentException("参数不是合法 JSON: " + ex.getMessage(), ex);
        }
    }

    private Path resolvePath(String path) {
        Path candidate = Paths.get(path);
        if (!candidate.isAbsolute()) {
            candidate = workspaceRoot.resolve(candidate);
        }
        return candidate.normalize();
    }

    private String requireString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("缺少参数: " + key);
        }
        return String.valueOf(value);
    }

    private record SimpleToolCallback(ToolDefinition toolDefinition,
                                      java.util.function.Function<String, Mono<String>> executor) implements ToolCallback {

        @Override
        public @NonNull ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public String call(String toolInput) {
            return executor.apply(toolInput).blockOptional().orElse("");
        }
    }
}
