package org.example.kbsystemproject.service.component.advisor;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Component
public class CompressionDecider {

    // 配置项（实际应放在配置类中，这里简化）
    private static final int MAX_SAFE_CONTEXT_CHARS = 6000; // 模型安全上下文字符数估算
    private static final Duration SLEEP_THRESHOLD = Duration.ofHours(2);
    private static final int MAX_TURNS = 20;

    /**
     * 复合判定逻辑
     * @param shortTermMemoryMessages Redis中的近期对话内容列表
     * @param currentUserMessage 当前用户输入
     * @param turnCount 当前轮次
     * @param lastActiveAt 上次活跃时间
     * @return true 如果需要触发压缩
     */
    public boolean decide(List<Message> shortTermMemoryMessages,
                          Message currentUserMessage,
                          int turnCount,
                          OffsetDateTime lastActiveAt) {

        // 1. Token 水位线 (硬性) - 遍历 Message 提取文本估算
        int estimatedTokens = 0;
        if (shortTermMemoryMessages != null) {
            for (Message msg : shortTermMemoryMessages) {
                estimatedTokens += (msg.getText() != null ? msg.getText().length() : 0);
            }
        }
        estimatedTokens += (currentUserMessage.getText() != null ? currentUserMessage.getText().length() : 0);

        if (estimatedTokens > MAX_SAFE_CONTEXT_CHARS) {
            return true;
        }

        // 2. 休眠唤醒 (语义性)
        if (lastActiveAt != null && Duration.between(lastActiveAt, OffsetDateTime.now()).compareTo(SLEEP_THRESHOLD) > 0) {
            return true;
        }

        // 3. 轮次上限 (兜底性)
        if (turnCount >= MAX_TURNS) {
            return true;
        }

        return false;
    }
}
