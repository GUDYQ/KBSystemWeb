package org.example.kbsystemproject.base.ai.agent.tool.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.kbsystemproject.base.ai.agent.tool.ReactiveTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 查询实时天气的响应式工具
 */
@Slf4j
@Component
public class WeatherReactiveTool implements ReactiveTool {

    @Override
    public String getName() {
        return "get_weather";
    }

    @Override
    public String getDescription() {
        return "查询指定城市的实时天气预报";
    }

    @Override
    public Class<?> getInputType() {
        return WeatherInput.class;
    }

    @Override
    public Mono<String> execute(String toolInput, ToolContext context) {
        log.info("Executing Weather Tool with input: {}", toolInput);
        // 这里实际应该解析 JSON toolInput 到 WeatherInput
        // 或者调用第三方天气 API
        // 简单模拟返回：
        return Mono.just("{\"weather\": \"晴\", \"temperature\": \"22℃\", \"description\": \"今日天气晴朗，适宜户外活动。\"}");
    }

    public record WeatherInput(String city) {}
}


