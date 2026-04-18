# AI学习助手目标结构

## 1. 目标

基于当前代码分类结果，AI 学习助手模块的目标不是继续在现有 `controller/service/base.ai` 中叠加功能，而是收敛成一个独立的业务模块：

1. 接入层只负责协议与参数
2. 应用层只负责流程编排
3. 领域层只负责学习场景模型与规则
4. 基础设施层只负责模型、RAG、记忆、持久化、工具实现
5. 评测层独立于主业务链路

## 2. 目标目录结构

```text
src/main/java/org/example/kbsystemproject
├─ ailearning
│  ├─ interfaces
│  │  └─ http
│  │     ├─ AiLearningController.java
│  │     ├─ request
│  │     │  ├─ ChatRequest.java
│  │     │  ├─ ExerciseGenerateRequest.java
│  │     │  └─ WrongQuestionAnalyzeRequest.java
│  │     └─ response
│  │        ├─ ChatChunkResponse.java
│  │        ├─ LearningProfileResponse.java
│  │        └─ ExerciseGenerateResponse.java
│  ├─ application
│  │  ├─ service
│  │  │  ├─ AiLearningApplicationService.java
│  │  │  ├─ ExerciseApplicationService.java
│  │  │  ├─ WrongQuestionApplicationService.java
│  │  │  └─ LearningProfileApplicationService.java
│  │  └─ assembler
│  │     └─ AiLearningDtoAssembler.java
│  ├─ domain
│  │  ├─ session
│  │  │  ├─ model
│  │  │  │  ├─ LearningSession.java
│  │  │  │  ├─ LearningMode.java
│  │  │  │  └─ SessionType.java
│  │  │  └─ service
│  │  │     └─ LearningSessionDomainService.java
│  │  ├─ profile
│  │  │  └─ model
│  │  │     ├─ LearningProfile.java
│  │  │     ├─ WeakKnowledgePoint.java
│  │  │     └─ LearningSummary.java
│  │  ├─ exercise
│  │  │  └─ model
│  │  │     ├─ ExerciseRecord.java
│  │  │     └─ ExerciseDifficulty.java
│  │  └─ wrongquestion
│  │     └─ model
│  │        └─ WrongQuestionRecord.java
│  ├─ infrastructure
│  │  ├─ ai
│  │  │  ├─ prompt
│  │  │  │  ├─ LearningPromptTemplateFactory.java
│  │  │  │  └─ template
│  │  │  ├─ orchestration
│  │  │  │  ├─ LearningChatOrchestrator.java
│  │  │  │  └─ LearningTaskRouter.java
│  │  │  ├─ advisor
│  │  │  └─ tool
│  │  ├─ rag
│  │  │  ├─ LearningKnowledgeRetriever.java
│  │  │  ├─ LearningKnowledgeBaseInitializer.java
│  │  │  ├─ LearningDocumentLoader.java
│  │  │  └─ metadata
│  │  ├─ memory
│  │  │  ├─ RedisShortTermMemoryStore.java
│  │  │  ├─ LearningMemoryService.java
│  │  │  └─ ConversationArchiveStore.java
│  │  ├─ persistence
│  │  │  ├─ session
│  │  │  ├─ profile
│  │  │  ├─ exercise
│  │  │  └─ wrongquestion
│  │  └─ config
│  │     └─ AiLearningModuleConfig.java
│  └─ evaluation
│     ├─ retrieval
│     ├─ answer
│     └─ learning
├─ base
├─ config
├─ controller
├─ entity
├─ reposity
└─ service
```

## 3. 包职责边界

### 3.1 `ailearning.interfaces`

职责：

1. 暴露正式业务接口
2. 承接请求校验
3. 不放业务编排与模型调用逻辑

建议接口：

1. `POST /api/ai-learning/chat`
2. `GET /api/ai-learning/chat/stream`
3. `POST /api/ai-learning/chat/stop`
4. `POST /api/ai-learning/exercise/generate`
5. `POST /api/ai-learning/wrong-question/analyze`
6. `GET /api/ai-learning/profile`

### 3.2 `ailearning.application`

职责：

1. 组织一次完整学习任务
2. 调用 RAG、记忆、Agent、持久化等能力
3. 管理会话流、模式切换、降级策略

禁止：

1. 直接写 SQL
2. 直接操作 Redis key
3. 直接拼底层 Prompt 文本模板

### 3.3 `ailearning.domain`

职责：

1. 承载学习场景核心对象
2. 约束会话模式、学习档案、错题、练习记录
3. 定义领域规则和枚举

这一层只表达业务概念，不依赖 Spring AI、Redis、PgVector 实现细节。

### 3.4 `ailearning.infrastructure`

职责：

1. 封装 ChatModel、Prompt、Advisor、Tool
2. 封装向量检索与知识库初始化
3. 封装短期记忆与长期归档
4. 封装 JDBC/R2DBC/Redis 持久化适配

这是“技术能力实现层”，不直接暴露给 Controller。

### 3.5 `ailearning.evaluation`

职责：

1. 检索评测
2. 回答质量评测
3. 学习效果评测

评测代码从主业务链路剥离，不再和 Controller/Service 混放。

## 4. 现有代码到目标结构的迁移映射

| 现有位置 | 目标位置 | 说明 |
|---|---|---|
| `controller/AIController` | `ailearning/interfaces/http/AiLearningController` | 从测试接口改为正式学习助手接口 |
| `service/AgentService` | `ailearning/application/service/AiLearningApplicationService` + `infrastructure/ai/orchestration/*` | 拆分聊天编排、会话流管理、Agent 调用 |
| `service/ConversationService` | `ailearning/infrastructure/persistence/session/*` | 作为学习会话状态持久化适配 |
| `service/ConversationMemoryService` | `ailearning/infrastructure/memory/ConversationArchiveStore` | 长期归档和摘要检索 |
| `service/MemoryService` | `ailearning/infrastructure/memory/LearningMemoryService` | 学习记忆统一服务 |
| `service/component/RedisShortTermMemory` | `ailearning/infrastructure/memory/RedisShortTermMemoryStore` | 短期对话记忆存储 |
| `service/component/advisor/*` | `ailearning/infrastructure/ai/advisor/*` | 学习场景上下文注入 |
| `service/component/MDFileLoader` | `ailearning/infrastructure/rag/LearningDocumentLoader` | 学习资料加载器 |
| `service/init/KnowledgeBaseInitializer` | `ailearning/infrastructure/rag/LearningKnowledgeBaseInitializer` | 学习知识库初始化 |
| `config/ai/ModelConfig` | `ailearning/infrastructure/config/*` 或保留全局配置 | 通用 Bean 和学习助手专属 Bean 分开 |
| `base/ai/agent/*` | `base/ai/agent/*` | 先保留为共享底层框架，不直接并入业务层 |
| `base/ai/agent/tool/impl/*` | `ailearning/infrastructure/ai/tool/*` | 工具改为学习场景工具集 |
| `T2test/*` | `ailearning/evaluation/retrieval/*` | 评测代码归档到评测层 |

## 5. 资源目录目标结构

```text
src/main/resources
├─ knowledge-base
│  └─ learning
│     ├─ math
│     │  ├─ junior
│     │  └─ college
│     ├─ physics
│     └─ english
├─ prompt
│  └─ ailearning
│     ├─ chat-system.md
│     ├─ explain-problem.md
│     ├─ generate-exercise.md
│     ├─ analyze-wrong-question.md
│     └─ review-summary.md
└─ application.yml
```

当前 `resources/document/*.md` 中的恋爱问答内容应迁移出学习助手知识库目录，不再作为学习场景的默认数据源。

## 6. 测试目录目标结构

```text
src/test/java/org/example/kbsystemproject
└─ ailearning
   ├─ interfaces
   ├─ application
   ├─ infrastructure
   └─ evaluation
```

## 7. 推荐迁移顺序

1. 先新建 `ailearning` 包骨架，不直接移动旧类
2. 优先迁移 Controller、请求 DTO、Application Service
3. 再迁移 RAG、记忆、知识库初始化
4. 最后迁移评测与场景工具

这样可以避免一次性大规模重命名导致编译链路失控。
