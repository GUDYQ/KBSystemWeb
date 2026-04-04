package org.example.kbsystemproject.base.ai.agent.tool;

import org.springframework.ai.chat.model.ToolContext;
import reactor.core.publisher.Mono;

/**
 * 响应式工具接口
 * 1. 支持响应式返回
 * 2. 支持上下文注入
 */
public interface ReactiveTool {

    /**
     * 工具名称
     */
    String getName();

    /**
     * 工具描述
     */
    String getDescription();

    /**
     * 输入参数类型，用于生成 JSON Schema
     */
    Class<?> getInputType();

    /**
     * 响应式执行逻辑
     */
    Mono<String> execute(String toolInput, ToolContext context);
}

