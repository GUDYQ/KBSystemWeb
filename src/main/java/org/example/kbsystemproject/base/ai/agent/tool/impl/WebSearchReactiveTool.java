package org.example.kbsystemproject.base.ai.agent.tool.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.base.ai.agent.tool.ReactiveTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 联网搜索的响应式工具
 */
@Slf4j
@Component
public class WebSearchReactiveTool implements ReactiveTool {

    @Override
    public String getName() {
        return "search_web";
    }

    @Override
    public String getDescription() {
        return "在互联网上搜索信息，用于获取最新的新闻、知识或其他事实性信息";
    }

    @Override
    public Class<?> getInputType() {
        return SearchInput.class;
    }

    @Override
    public Mono<String> execute(String toolInput, ToolContext context) {
        log.info("Executing Web Search Tool with query: {}", toolInput);
        // 这里实际应该执行搜索引擎 API 调用，比如 Serper, Google Search, Bing 等
        // 返回模拟结果
        return Mono.just("[{\"title\": \"示例搜索结果\", \"snippet\": \"这是由 WebSearchReactiveTool 处理的一条模拟搜索结果。\", \"link\": \"https://example.com\"}]");
    }

    public record SearchInput(String query) {}
}

