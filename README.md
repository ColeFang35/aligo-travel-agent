# 多智能体智能差旅助手（AgentScope 2.0-Java · 仿阿里商旅）

基于 AgentScope 2.0-Java + Spring Boot 实现的多智能体差旅助手：把一句自然语言差旅诉求拆成机票、酒店、行程、审批等子任务，交由不同子 Agent 协同完成，卡片化流式呈现；自带火焰图 / 流程图调试页。

**离线可跑**：内置剧本模型（`ScriptedMockModel`），无需 API Key 即可完整演示，且走的是真实 ReActAgent / 事件 / 工具代码路径。

## 亮点

- **多智能体编排**：主规划 Agent + 行程 / 信息 / 知识库 / 审批 4 个子 Agent，各挂专属工具集与系统提示词，职责单一、权限受限。
- **快 / 慢车道路由**：固定话术走规则直达（毫秒级）；复杂长句由意图识别子 Agent 显式推理拆成多意图，再做问题改写与关键词兜底。内置评测中，意图识别准确率由 70% 提升至 100%。
- **两种调度方式**：结果回流主 Agent 汇总（Routing），或子 Agent 直接接管本轮对话（Handoff）。
- **上下文工程**：动态 Prompt 状态机按会话阶段拼装系统提示词；会话记忆三表；差旅报销标准走知识库检索（RAG）。
- **可观测**：`FlowTracer` 记录各阶段 span（耗时 / Token），调试页提供火焰图、Mermaid 流程图、Prompt 快照，并内置意图评测。

## 快速开始

需要 JDK 17 + Maven 3.9，以及 **MySQL**（审批数据）与 **Redis**（列表缓存）。

依赖 MySQL 与 Redis，一条命令起（`docker-compose.yml` 里 schema 会自动初始化）：

```bash
docker compose up -d          # MySQL 3306 + Redis 6379
```

已有本地 MySQL/Redis 的话，手动建库即可（含表、触发器、存储过程、函数）：

```bash
mysql -uroot -p < src/main/resources/db/schema.sql
```

```bash
mvn -q package -DskipTests
java -jar target/aligo-travel-agent-1.0.0.jar
# 打开 http://localhost:8080/
```

> Windows 下若中文路径导致 mvn 乱码，改用绝对路径调用 `mvn.cmd`；重启前先结束占用 8080 端口的进程。

要用真实模型：设置 `DASHSCOPE_API_KEY` 与 `DASHSCOPE_MODEL`，底层模型切换为通义千问，编排逻辑不变。

## 数据层（MySQL + MyBatis + Redis）

审批单持久化在 **MySQL**，访问层用 **MyBatis**；列表查询走 **Redis** 缓存。

| 组件 | 用在哪 |
|---|---|
| `dao/ApprovalMapper.java` + `resources/mapper/ApprovalMapper.xml` | MyBatis 数据访问层（增 / 查 / 单号生成） |
| `resources/db/schema.sql` | 建库建表 + **触发器** + **存储过程** + **函数** |
| `dao/Approval.java` | 表 `travel_approval` 的实体 |
| `Redis` | `aligo:approval:list` 列表缓存（60s TTL），提交后主动失效 |

数据库对象：
- 表 `travel_approval`（审批单）、`approval_audit`（变更审计）
- 触发器 `trg_approval_after_insert` / `trg_approval_after_update` —— **状态变更自动写审计表**，应用层不写审计
- 存储过程 `sp_approval_stats_by_month(year, month)` —— 按月统计；`sp_approve_application(apply_id, status)` —— 状态流转
- 函数 `fn_count_by_destination(destination)` —— 按目的地累计计数
- 申请单号在 SQL 内生成（`CONCAT` + `LPAD` + `MAX`），应用层不维护自增状态

## 试试这些

| 说什么 | 车道 | 效果 |
|---|---|---|
| 为我规划行程：明天杭州到上海出差，预算800 | 快 | 直达行程 Agent，返回卡片 |
| 为我提申请：后天去武汉出差两天 | 快 | 直达审批 |
| 后天去北京出差，看看机票和酒店，顺便说下住宿报销标准 | 慢 | 拆成 3 个意图，3 个子 Agent 协作 |
| 查一下北京明天的天气 | 慢 | 子 Agent 接管，返回天气卡片 |
| 差旅报销标准是什么 | 慢 | 知识库检索 |

## 调试页

```
/debug.html?trace=<id>&view=flame     火焰图
                ...&view=flow         Mermaid 流程图
                ...&view=prompt       Prompt 快照
```

trace id 见聊天流 `event:done` 的 `traceId`。

## 接口

| 接口 | 说明 |
|---|---|
| `GET /api/chat/stream?query=&tenantId=&sessionId=` | SSE 流式对话主入口 |
| `GET /api/debug/provider` | 模型 / Provider 信息 |
| `GET /api/debug/traces` · `/trace/{id}` | Trace 列表 / 详情 |
| `GET /api/debug/trace/{id}/flow` | Mermaid 流程图 |
| `GET /api/debug/prompts/{traceId}` | Prompt 快照 |
| `GET /api/debug/memory/{sessionId}` | 上下文三表与记忆 |
| `GET /api/debug/eval/intent` | 意图评测（v1 / v2 对比） |

## 目录结构

```
src/main/java/io/aligo/travel/
  agent/    编排服务、子 Agent 工厂、PromptStore、动态 Prompt 中间件
  context/  状态机、三表记忆、出入栈、槽位提取
  intent/   意图识别
  model/    模型工厂 + 剧本 Mock
  observe/  FlowTracer / Span / Trace（火焰图）
  rag/      知识库检索
  thought/  TaskCollector + TaskPrintHook
  tool/     业务工具
  web/      Chat / Debug Controller
src/main/resources/static/   前端（首页 / 聊天 / 调试）
pom.xml
```

## 生产替换路径

- `KnowledgeBaseService` → 真实向量库（如 MaxKB）
- `FlowTracer` → Langfuse / OpenTelemetry
- 剧本 Mock → 已内建 DashScope 通道，配置 Key 即用
- 意图规则引擎 → LLM 分类 + 校验兜底
