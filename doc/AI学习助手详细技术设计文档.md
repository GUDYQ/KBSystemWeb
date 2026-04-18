# AI学习助手详细技术设计文档

## 1. 文档目的

本文档在《AI学习助手设计文档（草案）》基础上，进一步细化 AI 学习助手的技术架构、模块职责、调用链路、关键数据流、扩展点与落地建议，用于指导后端开发与后续联调。

## 2. 设计目标

本次技术设计聚焦以下目标：

1. 将现有通用 Agent 能力收敛为学习场景可用能力
2. 建立稳定的 RAG 检索链路
3. 建立适合学习场景的会话记忆和长期记忆
4. 保持流式输出体验
5. 支持后续练习生成、错题分析、学习总结等扩展能力

## 3. 现状分析

结合当前项目代码，已有基础模块包括：

1. `AIController`
   - 提供 SSE 流式聊天入口
2. `AgentService`
   - 承担调用编排与会话流管理
3. `ReActAgent`
   - 提供工具式 Agent 执行框架
4. `RedisShortTermMemory`
   - 提供短期对话记忆
5. `ConversationMemoryService`
   - 提供长期摘要与消息归档
6. `KnowledgeBaseInitializer`
   - 预留知识库初始化能力
7. `VectorStore`
   - 已具备 `PgVectorStore` 配置

现有主要问题：

1. Prompt 与学习场景不匹配
2. Agent 与 Tool 的注册关系不一致
3. 知识库初始化链路未启用
4. 记忆结构仍偏通用对话
5. 检索结果未形成标准化学习上下文

## 4. 总体技术架构

```text
Client
  ->
AIController
  ->
AgentService
  ->
LearningOrchestrator
  |- SessionContextBuilder
  |- PromptAssembler
  |- RetrievalService
  |- LearningMemoryService
  |- ToolRouter
  ->
ChatModel / Agent
  ->
Redis / PostgreSQL / PgVector
```

建议在现有结构上增加一个学习场景编排层，而不是把全部学习逻辑继续堆在 `AgentService` 中。

## 5. 模块设计

### 5.1 Controller 层

职责：

1. 接收前端请求
2. 校验基础参数
3. 建立会话标识
4. 返回 SSE 流

建议新增正式控制器：

1. `AiLearningController`

建议接口：

1. `POST /api/ai-learning/chat`
2. `GET /api/ai-learning/chat/stream`
3. `POST /api/ai-learning/chat/stop`
4. `POST /api/ai-learning/exercise/generate`
5. `POST /api/ai-learning/wrong-question/analyze`

现有 `/api/test/ai` 仅保留为测试接口，不作为正式业务路径。

### 5.2 AgentService 层

职责建议收敛为：

1. 管理会话流
2. 管理 session sink
3. 触发编排执行
4. 回收流资源

不建议继续承担以下复杂逻辑：

1. Prompt 拼装
2. 学习画像加载
3. 检索过滤规则拼装
4. 错题记录决策

这些职责应下沉到专门服务。

### 5.3 LearningOrchestrator 层

建议新增 `LearningOrchestrator` 作为学习场景主编排服务。

核心职责：

1. 接收业务请求对象
2. 判断当前模式
   - 普通问答
   - 题目讲解
   - 错题分析
   - 练习生成
3. 构建上下文
4. 调用检索
5. 拼装 Prompt
6. 决定是直接模型回答还是走 Agent
7. 生成流式响应
8. 异步持久化记忆

建议核心方法：

```java
Flux<String> chat(LearningChatRequest request)
Flux<String> analyzeWrongQuestion(WrongQuestionAnalyzeRequest request)
Mono<ExerciseGenerateResponse> generateExercise(ExerciseGenerateRequest request)
```

### 5.4 SessionContextBuilder

职责：

1. 加载用户会话状态
2. 加载 Redis 短期记忆
3. 加载长期学习摘要
4. 加载用户学习画像
5. 输出统一上下文对象

建议输出对象：

```java
public record LearningSessionContext(
    String conversationId,
    Long userId,
    String subject,
    String grade,
    String currentTopic,
    List<Message> shortTermMessages,
    List<String> longTermSummaries,
    List<String> weakPoints,
    Map<String, Object> extra
) {}
```

### 5.5 RetrievalService

职责：

1. 接收查询文本
2. 接收学科、年级、章节等过滤条件
3. 执行向量检索
4. 返回标准化检索结果

建议统一检索输入：

```java
public record RetrievalQuery(
    String query,
    String subject,
    String grade,
    String chapter,
    String knowledgePoint,
    Integer topK
) {}
```

建议统一检索输出：

```java
public record RetrievalResult(
    String documentId,
    String content,
    Double score,
    Map<String, Object> metadata
) {}
```

### 5.6 PromptAssembler

职责：

1. 根据场景模板生成 Prompt
2. 合并用户问题、短期记忆、长期总结、检索结果
3. 控制 Prompt 长度
4. 支持引用信息拼装

建议方法：

1. `buildQaPrompt(...)`
2. `buildExplainPrompt(...)`
3. `buildExercisePrompt(...)`
4. `buildReviewPrompt(...)`

建议输出对象：

```java
public record PromptPackage(
    String systemPrompt,
    List<Message> messages,
    Map<String, Object> metadata
) {}
```

### 5.7 LearningMemoryService

建议从现有 `ConversationMemoryService` 继续演进，拆为学习语义更明确的服务。

职责：

1. 存储短期问答摘要
2. 存储学习阶段总结
3. 记录错题
4. 维护薄弱知识点
5. 记录练习结果

建议内部拆分：

1. `LearningProfileService`
2. `WrongQuestionService`
3. `LearningSummaryService`
4. `ExerciseRecordService`

### 5.8 ToolRouter

工具层建议只保留学习场景确实需要的工具。

建议优先工具：

1. `RetrieveKnowledgeTool`
2. `RecordWrongQuestionTool`
3. `GenerateExerciseTool`
4. `GetLearningProfileTool`
5. `FinishTaskTool`

联网检索工具和天气工具不应作为学习主链路默认能力。

## 6. 两种执行模式设计

### 6.1 直接问答模式

适用于：

1. 知识点解释
2. 简单概念问答
3. 轻量追问

执行步骤：

1. 构建上下文
2. 检索知识库
3. 组装 Prompt
4. 调用模型流式回答
5. 保存会话

优点：

1. 延迟更低
2. 路径更稳定
3. 更适合大部分学习问题

### 6.2 Agent 模式

适用于：

1. 复杂题目讲解
2. 多步骤学习任务
3. 错题记录与练习联动
4. 需要多工具配合的场景

执行步骤：

1. 模型判断任务类型
2. 调用检索工具
3. 调用学习画像工具
4. 生成讲解或练习
5. 调用结束工具

建议默认优先用直接问答模式，仅在复杂任务场景切换到 Agent 模式。

## 7. RAG 技术设计

### 7.1 文档来源

学习场景文档建议分为：

1. 教材正文
2. 课程讲义
3. 章节知识点说明
4. 例题解析
5. 练习题及答案
6. 错题归纳

### 7.2 文档入库流程

```text
源文档
  ->
FileLoader
  ->
DocumentNormalizer
  ->
ChunkSplitter
  ->
MetadataEnricher
  ->
Embedding
  ->
PgVectorStore
```

建议新增中间步骤：

1. 文档标准化
2. 标题层级提取
3. 知识点元数据增强

### 7.3 Chunk 策略

建议规则：

1. 优先按章节标题切分
2. 再按段落切分
3. 最后按 token 限制做细分

原则：

1. 保留完整语义单元
2. 避免单个 chunk 过短
3. 保留父标题信息

建议元数据：

1. `doc_id`
2. `subject`
3. `grade`
4. `chapter`
5. `section`
6. `knowledge_point`
7. `chunk_type`
8. `source_name`
9. `source_path`

### 7.4 检索策略

建议采用“向量检索 + 元数据过滤 + 结果重排”：

1. 按 query 向量召回 topK
2. 按学科、章节过滤不相关结果
3. 对结果进行简单重排
4. 保留 3 到 5 个最高价值片段用于 Prompt

### 7.5 引用策略

回答中建议包含来源引用：

1. 章节名
2. 文档名
3. 知识点名

避免只输出原始 chunk 内容而没有来源说明。

## 8. 记忆系统设计

### 8.1 短期记忆

继续使用 Redis。

内容：

1. 最近若干轮问答
2. 当前会话即时上下文

限制：

1. 只保留最近 N 轮
2. 仅用于连续对话

### 8.2 中期记忆

存储当前学习主题的阶段总结。

内容：

1. 当前主题摘要
2. 当前章节掌握情况
3. 当前会话中的高价值问题

建议存储于 PostgreSQL + PgVector，支持相似主题召回。

### 8.3 长期记忆

存储长期学习画像。

内容：

1. 薄弱知识点
2. 历史错题模式
3. 偏好讲解风格
4. 学习目标
5. 历史学习阶段总结

### 8.4 记忆更新策略

建议使用异步更新：

1. 每轮问答结束后写入短期记忆
2. 满足阈值时触发摘要压缩
3. 对错题类任务优先写入错题记录
4. 周期性生成长期学习总结

## 9. Prompt 技术方案

### 9.1 Prompt 模板结构

统一结构建议：

1. System Prompt
2. 学习场景元信息
3. 用户历史摘要
4. 检索结果片段
5. 当前问题

### 9.2 学习问答 Prompt 要点

1. 使用教学型表达
2. 优先解释概念再给结论
3. 给出步骤化说明
4. 标出易错点
5. 结尾给简短总结

### 9.3 题目解析 Prompt 要点

1. 明确题型
2. 分步骤拆解
3. 说明每一步依据
4. 输出常见错误思路
5. 给出同类题练习建议

### 9.4 练习生成 Prompt 要点

1. 指定知识点
2. 指定难度
3. 输出题目、答案、解析
4. 控制格式可解析

## 10. 流式响应设计

继续基于 WebFlux + SSE。

建议输出事件类型：

1. `token`
2. `status`
3. `citation`
4. `finish`
5. `error`

建议后续不要只返回纯文本字符串，而是返回结构化 SSE 事件。

示例：

```json
{
  "type": "token",
  "content": "这个知识点可以分为两部分来看"
}
```

## 11. 异常与降级设计

### 11.1 模型调用失败

降级策略：

1. 返回统一错误提示
2. 记录 traceId
3. 不中断整体服务

### 11.2 检索失败

降级策略：

1. 允许无 RAG 回答
2. 明示“当前未检索到可靠资料”
3. 降低回答确定性

### 11.3 工具执行失败

降级策略：

1. 当前工具失败不影响主会话结束
2. 失败工具写日志
3. 返回有限结果

### 11.4 记忆写入失败

降级策略：

1. 不阻塞用户回答
2. 异步重试
3. 记录异常事件

## 12. 配置设计建议

建议新增独立配置前缀：

```yaml
ai-learning:
  prompt:
    mode: standard
  rag:
    top-k: 5
    max-context-docs: 4
  memory:
    short-term-turns: 10
    summary-trigger-turns: 20
  exercise:
    default-count: 5
```

避免学习助手配置继续分散在多个无明显业务语义的节点下。

## 13. 可观测性设计

建议打通以下日志链路：

1. 请求 ID
2. 会话 ID
3. 用户 ID
4. 检索耗时
5. 模型耗时
6. 召回文档数
7. Prompt token 大小
8. 工具调用次数

建议核心指标：

1. SSE 活跃连接数
2. 平均首 token 耗时
3. 平均总耗时
4. 检索命中率
5. 模型错误率
6. 记忆写入失败率

## 14. 开发落地建议

### 14.1 第一阶段

1. 新增正式学习助手控制器
2. 修正 Prompt
3. 统一 Tool 注册
4. 启用知识库初始化
5. 接入学习场景资料

### 14.2 第二阶段

1. 新增 `LearningOrchestrator`
2. 新增 `RetrievalService`
3. 新增 `PromptAssembler`
4. 新增学习档案实体
5. 新增错题记录能力

### 14.3 第三阶段

1. 练习题生成
2. 学习复习模式
3. 学习进度与画像增强
4. 回答质量评测体系

## 15. 结论

技术上，AI学习助手应采用“轻量 RAG 问答为主，复杂任务 Agent 编排为辅”的结构，避免把所有问题都推给通用 ReAct Agent。系统的核心不只是接入大模型，而是稳定组织检索、记忆、Prompt 和学习场景数据，使输出真正服务于学习过程。
