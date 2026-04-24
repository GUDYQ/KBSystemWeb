package org.example.kbsystemproject.ailearning.application.service;

import org.example.kbsystemproject.ailearning.domain.session.ConversationTurn;
import org.example.kbsystemproject.ailearning.domain.session.SessionMemorySnapshot;
import org.example.kbsystemproject.ailearning.domain.session.SessionMessageRole;
import org.example.kbsystemproject.ailearning.domain.session.SessionTopicBlock;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TopicInferenceService {

    private static final Map<String, List<String>> TOPIC_RULES = buildTopicRules();
    private static final List<String> FOLLOW_UP_MARKERS = List.of(
            "继续", "这个", "这里", "那这个", "这个呢", "这里呢", "为什么", "怎么理解",
            "什么意思", "举个例子", "详细一点", "再说一下", "展开讲", "接着讲", "细说一下"
    );

    public String inferTopic(LearningChatCommand command, SessionMemorySnapshot snapshot) {
        return inferTopic(
                command.currentTopic(),
                snapshot == null || snapshot.session() == null ? null : snapshot.session().currentTopic(),
                snapshot == null ? null : snapshot.activeTopicBlock(),
                snapshot == null ? List.of() : snapshot.shortTermMemory(),
                command.prompt()
        );
    }

    public String inferTopic(String explicitTopic,
                             String sessionCurrentTopic,
                             SessionTopicBlock activeTopicBlock,
                             List<ConversationTurn> recentTurns,
                             String prompt) {
        String normalizedExplicitTopic = normalizeTopic(explicitTopic);
        if (normalizedExplicitTopic != null) {
            return normalizedExplicitTopic;
        }

        String inheritedTopic = firstNonBlank(
                normalizeTopic(activeTopicBlock == null ? null : activeTopicBlock.topic()),
                normalizeTopic(sessionCurrentTopic),
                normalizeTopic(latestTopicFromRecentTurns(recentTurns))
        );

        String inferredFromPrompt = inferTopicFromPrompt(prompt);
        if (inferredFromPrompt != null) {
            return inferredFromPrompt;
        }

        if (isFollowUpPrompt(prompt)) {
            return inheritedTopic == null ? "general" : inheritedTopic;
        }

        return inheritedTopic == null ? "general" : inheritedTopic;
    }

    private String inferTopicFromPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        String normalizedPrompt = prompt.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : TOPIC_RULES.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (normalizedPrompt.contains(keyword)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private boolean isFollowUpPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        String normalizedPrompt = prompt.trim().toLowerCase(Locale.ROOT);
        if (normalizedPrompt.length() <= 12) {
            return true;
        }
        return FOLLOW_UP_MARKERS.stream().anyMatch(normalizedPrompt::contains);
    }

    private String latestTopicFromRecentTurns(List<ConversationTurn> recentTurns) {
        if (recentTurns == null || recentTurns.isEmpty()) {
            return null;
        }
        for (int i = recentTurns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = recentTurns.get(i);
            if (turn.role() != SessionMessageRole.USER || turn.metadata() == null) {
                continue;
            }
            Object currentTopic = turn.metadata().get("currentTopic");
            if (currentTopic instanceof String value && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalizeTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        return topic.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, List<String>> buildTopicRules() {
        Map<String, List<String>> rules = new LinkedHashMap<>();
        rules.put("redis", List.of(
                "redis", "缓存穿透", "缓存击穿", "缓存雪崩", "布隆过滤器", "淘汰策略", "分布式锁", "rdb", "aof"
        ));
        rules.put("mysql", List.of(
                "mysql", "sql", "join", "innodb", "慢查询"
        ));
        rules.put("索引", List.of(
                "索引", "回表", "覆盖索引", "最左前缀", "索引失效", "b+树", "b树"
        ));
        rules.put("事务", List.of(
                "事务", "mvcc", "隔离级别", "脏读", "不可重复读", "幻读", "提交", "回滚"
        ));
        rules.put("tcp", List.of(
                "tcp", "三次握手", "四次挥手", "拥塞控制", "滑动窗口"
        ));
        rules.put("http", List.of(
                "http", "https", "状态码", "cookie", "session", "跨域"
        ));
        rules.put("spring", List.of(
                "spring", "springboot", "spring boot", "ioc", "aop", "bean", "mvc"
        ));
        rules.put("java", List.of(
                "java", "jvm", "gc", "threadlocal", "多线程", "并发"
        ));
        rules.put("python", List.of(
                "python", "django", "flask", "gil"
        ));
        rules.put("递归", List.of(
                "递归", "回溯", "dfs"
        ));
        rules.put("二叉树", List.of(
                "二叉树", "bst", "红黑树", "树的遍历"
        ));
        rules.put("链表", List.of(
                "链表", "快慢指针"
        ));
        rules.put("动态规划", List.of(
                "动态规划", "dp", "状态转移"
        ));
        rules.put("操作系统", List.of(
                "进程", "线程", "死锁", "内存管理", "页表", "操作系统"
        ));
        return rules;
    }
}
