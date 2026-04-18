# AI学习助手接口与表结构设计文档

## 1. 文档目的

本文档用于定义 AI 学习助手模块的接口协议、核心请求响应结构与推荐表结构，为前后端联调、数据库设计和后续实现提供统一基线。

## 2. 设计原则

1. 接口命名面向业务语义
2. 会话、学习画像、错题、练习等对象解耦
3. 支持流式输出与异步扩展
4. 尽量避免把所有内容都塞进单一会话表

## 3. 接口设计

### 3.1 学习问答接口

接口：

`POST /api/ai-learning/chat`

用途：

1. 发起一次学习问答任务
2. 返回任务受理结果和会话信息

请求示例：

```json
{
  "userId": 1001,
  "conversationId": "conv_20260417_001",
  "subject": "math",
  "grade": "high_school",
  "mode": "QA",
  "currentTopic": "函数单调性",
  "prompt": "什么是函数的单调递增区间？"
}
```

响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "conversationId": "conv_20260417_001",
    "streamId": "stream_001",
    "status": "PROCESSING"
  }
}
```

### 3.2 流式输出接口

接口：

`GET /api/ai-learning/chat/stream?conversationId=conv_20260417_001`

用途：

1. 获取指定会话的 SSE 流式响应

SSE 事件建议格式：

```json
{
  "type": "token",
  "conversationId": "conv_20260417_001",
  "content": "单调递增区间可以理解为"
}
```

结束事件：

```json
{
  "type": "finish",
  "conversationId": "conv_20260417_001",
  "content": "回答完成"
}
```

### 3.3 停止生成接口

接口：

`POST /api/ai-learning/chat/stop`

请求示例：

```json
{
  "conversationId": "conv_20260417_001"
}
```

响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": "stopped"
}
```

### 3.4 错题分析接口

接口：

`POST /api/ai-learning/wrong-question/analyze`

请求示例：

```json
{
  "userId": 1001,
  "subject": "math",
  "grade": "high_school",
  "questionText": "已知函数f(x)=x^2-2x，求最小值。",
  "userAnswer": "最小值是0",
  "standardAnswer": "最小值是-1",
  "mode": "WRONG_ANALYZE"
}
```

响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "analysisId": "wa_001",
    "conversationId": "conv_wrong_001",
    "status": "PROCESSING"
  }
}
```

### 3.5 练习生成接口

接口：

`POST /api/ai-learning/exercise/generate`

请求示例：

```json
{
  "userId": 1001,
  "subject": "math",
  "grade": "high_school",
  "knowledgePoint": "函数单调性",
  "difficulty": "medium",
  "count": 5
}
```

响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "exerciseSetId": "exset_001",
    "questions": [
      {
        "questionNo": 1,
        "questionText": "判断函数在区间内的单调性",
        "answer": "略",
        "analysis": "略"
      }
    ]
  }
}
```

### 3.6 获取学习档案接口

接口：

`GET /api/ai-learning/profile?userId=1001&subject=math`

响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "userId": 1001,
    "subject": "math",
    "grade": "high_school",
    "goal": "提升函数题型得分",
    "weakPoints": [
      "函数单调性",
      "二次函数最值"
    ],
    "preferredStyle": "step_by_step"
  }
}
```

## 4. 统一数据对象设计

### 4.1 LearningChatRequest

```json
{
  "userId": 1001,
  "conversationId": "string",
  "subject": "string",
  "grade": "string",
  "mode": "QA | EXPLAIN | REVIEW",
  "currentTopic": "string",
  "prompt": "string"
}
```

### 4.2 WrongQuestionAnalyzeRequest

```json
{
  "userId": 1001,
  "subject": "string",
  "grade": "string",
  "questionText": "string",
  "userAnswer": "string",
  "standardAnswer": "string",
  "mode": "WRONG_ANALYZE"
}
```

### 4.3 ExerciseGenerateRequest

```json
{
  "userId": 1001,
  "subject": "string",
  "grade": "string",
  "knowledgePoint": "string",
  "difficulty": "easy | medium | hard",
  "count": 5
}
```

## 5. 表结构设计

### 5.1 学习会话表 `learning_session`

用途：

1. 管理用户学习会话
2. 支撑多轮上下文和状态追踪

字段建议：

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| conversation_id | varchar(64) | 会话唯一标识 |
| user_id | bigint | 用户 ID |
| subject | varchar(64) | 学科 |
| grade | varchar(64) | 年级 |
| mode | varchar(32) | 会话模式 |
| current_topic | varchar(255) | 当前主题 |
| status | varchar(32) | 处理中 / 已完成 / 已中止 |
| turn_count | int | 当前轮次 |
| last_active_at | timestamp | 最后活跃时间 |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

建表建议：

```sql
create table learning_session (
    id bigserial primary key,
    conversation_id varchar(64) not null unique,
    user_id bigint not null,
    subject varchar(64),
    grade varchar(64),
    mode varchar(32),
    current_topic varchar(255),
    status varchar(32) not null default 'PROCESSING',
    turn_count int not null default 0,
    last_active_at timestamp not null default now(),
    created_at timestamp not null default now(),
    updated_at timestamp not null default now()
);
```

### 5.2 学习档案表 `learning_profile`

用途：

1. 记录用户在学科维度上的长期学习画像

字段建议：

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| user_id | bigint | 用户 ID |
| subject | varchar(64) | 学科 |
| grade | varchar(64) | 年级 |
| goal | varchar(255) | 学习目标 |
| preferred_style | varchar(64) | 偏好讲解风格 |
| weak_points_json | jsonb | 薄弱知识点列表 |
| extra_json | jsonb | 扩展信息 |
| last_active_at | timestamp | 最后活跃时间 |
| created_at | timestamp | 创建时间 |
| updated_at | timestamp | 更新时间 |

建表建议：

```sql
create table learning_profile (
    id bigserial primary key,
    user_id bigint not null,
    subject varchar(64) not null,
    grade varchar(64),
    goal varchar(255),
    preferred_style varchar(64),
    weak_points_json jsonb not null default '[]'::jsonb,
    extra_json jsonb not null default '{}'::jsonb,
    last_active_at timestamp,
    created_at timestamp not null default now(),
    updated_at timestamp not null default now(),
    unique (user_id, subject)
);
```

### 5.3 错题记录表 `wrong_question_record`

用途：

1. 存储用户错题
2. 支持后续错题回顾与相似错因分析

字段建议：

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| user_id | bigint | 用户 ID |
| subject | varchar(64) | 学科 |
| grade | varchar(64) | 年级 |
| knowledge_point | varchar(255) | 知识点 |
| question_text | text | 题目内容 |
| user_answer | text | 用户答案 |
| standard_answer | text | 标准答案 |
| wrong_reason | text | 错因 |
| analysis | text | 系统分析 |
| source_type | varchar(32) | 来源类型 |
| created_at | timestamp | 创建时间 |

建表建议：

```sql
create table wrong_question_record (
    id bigserial primary key,
    user_id bigint not null,
    subject varchar(64),
    grade varchar(64),
    knowledge_point varchar(255),
    question_text text not null,
    user_answer text,
    standard_answer text,
    wrong_reason text,
    analysis text,
    source_type varchar(32),
    created_at timestamp not null default now()
);
```

### 5.4 学习摘要表 `learning_summary`

用途：

1. 记录阶段性学习总结
2. 支持长期学习记忆检索

字段建议：

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| conversation_id | varchar(64) | 会话 ID |
| user_id | bigint | 用户 ID |
| subject | varchar(64) | 学科 |
| summary_type | varchar(32) | 摘要类型 |
| content | text | 摘要内容 |
| metadata_json | jsonb | 摘要元数据 |
| created_at | timestamp | 创建时间 |

建表建议：

```sql
create table learning_summary (
    id bigserial primary key,
    conversation_id varchar(64),
    user_id bigint not null,
    subject varchar(64),
    summary_type varchar(32) not null,
    content text not null,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamp not null default now()
);
```

### 5.5 练习记录表 `exercise_record`

用途：

1. 保存生成的练习题及用户作答结果

字段建议：

| 字段名 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| exercise_set_id | varchar(64) | 练习批次 ID |
| user_id | bigint | 用户 ID |
| subject | varchar(64) | 学科 |
| knowledge_point | varchar(255) | 知识点 |
| difficulty | varchar(32) | 难度 |
| question_no | int | 题号 |
| question_text | text | 题目 |
| standard_answer | text | 标准答案 |
| analysis | text | 解析 |
| user_answer | text | 用户作答 |
| answer_status | varchar(32) | 正确 / 错误 / 未作答 |
| created_at | timestamp | 创建时间 |

建表建议：

```sql
create table exercise_record (
    id bigserial primary key,
    exercise_set_id varchar(64) not null,
    user_id bigint not null,
    subject varchar(64),
    knowledge_point varchar(255),
    difficulty varchar(32),
    question_no int not null,
    question_text text not null,
    standard_answer text,
    analysis text,
    user_answer text,
    answer_status varchar(32),
    created_at timestamp not null default now()
);
```

## 6. 向量表设计建议

### 6.1 知识库向量表

现有 `pgvector_store` 可继续使用，但建议确保 metadata 包含：

1. `subject`
2. `grade`
3. `chapter`
4. `section`
5. `knowledge_point`
6. `doc_type`
7. `source_name`

### 6.2 学习记忆向量表

现有 `vector_store_conversation` 可以演进为学习记忆向量表，建议 metadata 包含：

1. `user_id`
2. `conversation_id`
3. `subject`
4. `memory_type`
5. `knowledge_point`
6. `created_at`

建议 `memory_type` 取值：

1. `MESSAGE`
2. `SUMMARY`
3. `WRONG_QUESTION`
4. `LEARNING_PROFILE`

## 7. 索引建议

### 7.1 关系表索引

建议索引：

```sql
create index idx_learning_session_user_id on learning_session(user_id);
create index idx_learning_session_last_active_at on learning_session(last_active_at);

create index idx_learning_profile_user_subject on learning_profile(user_id, subject);

create index idx_wrong_question_user_subject on wrong_question_record(user_id, subject);
create index idx_wrong_question_knowledge_point on wrong_question_record(knowledge_point);

create index idx_learning_summary_user_subject on learning_summary(user_id, subject);

create index idx_exercise_record_user_subject on exercise_record(user_id, subject);
create index idx_exercise_record_exercise_set_id on exercise_record(exercise_set_id);
```

### 7.2 向量检索辅助索引

除向量索引外，建议对 metadata 中高频过滤字段做结构化落表，避免完全依赖 jsonb 检索。

## 8. 状态枚举建议

### 8.1 会话状态

1. `PROCESSING`
2. `FINISHED`
3. `STOPPED`
4. `FAILED`

### 8.2 会话模式

1. `QA`
2. `EXPLAIN`
3. `EXERCISE`
4. `WRONG_ANALYZE`
5. `REVIEW`

### 8.3 练习作答状态

1. `CORRECT`
2. `WRONG`
3. `UNANSWERED`

## 9. 前后端联调建议

1. 流式接口先只做 `token` 与 `finish` 两类事件
2. 后续再增加 `citation`、`status`、`error`
3. 练习题生成接口优先返回结构化 JSON，不走 SSE
4. 错题分析可复用问答流式能力

## 10. 结论

接口与表结构设计要围绕“学习过程数据化”展开，而不是只围绕聊天消息。核心对象应包括会话、学习档案、错题、摘要和练习记录，这样后续才能真正支持个性化学习闭环。
