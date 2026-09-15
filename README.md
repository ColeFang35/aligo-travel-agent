# 基于 AgentScope 2.0-Java 的智能差旅助手（阿里商旅 AliGo 复刻）

按《准确率提升至 90%，阿里商旅基于 AgentScope 构建多智能体差旅助手最佳实践》口径，
基于 AgentScope 2.0-Java（+ Spring Boot + DashScope）复现的可运行项目，含可视化页面与调试助手（火焰图/流程图）。

## 一、启动（离线演示，无需 API Key）

```bash
# Windows / PowerShell，JDK17 + Maven3.9 已配置；若 mvn 中文路径乱码用绝对路径 mvn
mvn -q package -DskipTests
java -jar target\aligo-travel-agent-1.0.0.jar
# 浏览器打开 http://localhost:8080/
```

> 中文路径下命令可用 `c:\tools\apache-maven-3.9.16\bin\mvn.cmd` 打到 `target` 用绝对路径。
> 重启服务前先杀掉 8080 监听进程：`Get-NetTCPConnection -LocalPort 8080 -State Listen | % OwningProcess | % { Stop-Process -Id $_ -Force }`

## 二、双模式说明

| 模式 | 触发 | 效果 |
|---|---|---|
| **Mock 剧本**（默认） | 无 `DASHSCOPE_API_KEY` | `ScriptedMockModel` 确定性剧本，走真实 ReActAgent / Hook / Tool 代码路径，离网可完整演示 |
| **真模型** | 设置 `DASHSCOPE_API_KEY` 与 DASHSCOPE_MODEL | 底层 Model 切换通义千问，编排不变 |

脚本化演示语句（聊天框点快捷按钮或直接粘贴）：

| 按钮/语句 | 车道 | 效果 |
|---|---|---|
| 为我规划行程：明天杭州到上海出差，预算800 | 快 | 直达 plan_agent，卡片 |
| 为我提申请：后天去武汉出差两天 | 快 | 直达 approval |
| 后天去北京出差，看看机票和酒店，顺便说下住宿报销标准 | 慢 | 3 意图拆分，3 子 Agent |
| 查一下北京明天的天气 | 慢 | Handoffs 接管，天气卡片 |
| 差旅报销标准是什么 | 慢 | RAG 检索 |
| 后天去北京出差，顺便报销 | 慢 | 多意图混合演示查办 |

## 三、调试助手直达链接

```
http://localhost:8080/debug.html?trace=<id>&view=flame     深度火焰图
...&view=flow       Mermaid 流程图
...&view=prompt     Prompt 快照
...&view=memory     （index?tab=debug 记忆/会话）
```
Trace id 见聊天流 `event:done` 的 `traceId`，或 Debug 列表。

## 四、服务端接口

| 接口 | 说明 |
|---|---|
| `GET /api/chat/stream?query=&tenantId=&sessionId=` | SSE 流式对话主入口 |
| `GET /api/debug/provider` | 模型/Provider 信息 |
| `GET /api/debug/traces` | Trace 列表 |
| `GET /api/debug/trace/{id}` | Trace 详情（spans） |
| `GET /api/debug/trace/{id}/flow` | Mermaid 流程图文本 |
| `GET /api/debug/prompts/{traceId}` | Prompt 快照 |
| `GET /api/debug/memory/{sessionId}` | 上下文三表 + 记忆 |
| `GET /api/debug/sessions` | 会话列表 |
| `GET /api/debug/eval/intent` | 意图评测（v1/v2 对比） |

## 五、目录结构

```
pom.xml
src/main/resources/static/   前端（index 学习首页 + chat 聊天页 + debug 调试页）
src/main/java/io/aligo/travel/
  agent/      编排编排服务、子 Agent 工厂、PromptStore、DynamicPromptMiddleware
  context/    状态机、三表记忆、出入栈、槽位提取
  intent/     意图识别
  model/      模型工厂 + 剧本 Mock
  observe/    FlowTracer/Span/Trace（火焰图）
  rag/        KnowledgeBaseService
  thought/    TaskCollector + TaskPrintHook
  tool/       业务工具
  web/        Chat/Debug Controller
```

## 六、文件定位

- 演示讲稿：`讲稿.md`
- 服务端编排：`src/main/java/io/aligo/travel/agent/OrchestratorService.java`
- 动态 Prompt 状态机：`src/main/java/io/aligo/travel/context/DynamicPromptStateMachine.java`
- 中间件（框架 Middleware）：`src/main/java/io/aligo/travel/agent/DynamicPromptMiddleware.java`
- 火焰图/流程图数据：`src/main/java/io/aligo/travel/observe/FlowTracer.java`
- 前端调试页：`src/main/resources/static/debug.html` + `debug.js`
- 前端聊天页：`src/main/resources/static/chat.html` + `app.js`
- 前端学习首页：`src/main/resources/static/index.html`（架构图/流程图/类图/亮点/学习路线）

## 七、课题文档（答辩 / 论文素材）

| 文件 | 内容 |
|---|---|
| 讲稿.md | 演示讲稿（背景/技术选型/演示/亮点/结论） |
| 答辩八股文.md | 开题/答辩八股（背景、意义、现状、内容、方法、路线、创新点、章节安排） |
| 论文章节.md | 七章论文正文骨架（绪论→相关技术→需求→设计→实现→测试→总结） |
| Agent面试资料.pdf | 面试八股文 + 项目面试稿 合并 PDF，可在首页下载或直达 \`/Agent面试资料.pdf\` |
| tech.html | 关键技术拆解页：架构图 / 类图 / 时序图（Mermaid 渲染）+ 组件拆解 + 问答，首页右上导航直达 |
| 关键技术拆解讲稿.md | 讲解文档 Markdown 版（含 Mermaid 源码），与 tech.html 对应 |

## 八、生产替换路径

- `KnowledgeBaseService` -> MaxKB 真向量库
- `FlowTracer` -> Langfuse / OpenTelemetry（框架自带 Tracer 需外部后端）
- `ScriptedMockModel` -> 已内建 DashScope 通道，配 Key 即用
- 意图规则引擎 -> 可升级为 LLM 分类 + 校验兜底

## 九、截图（本项目 .shots/ 用于讲稿配图）

| 文件 | 内容 |
|---|---|
| fast.png / slow.png | 快车道/慢车道聊天效果 |
| t_handoff.png | Handoffs 接管 + 天气卡片 |
| t_flame.png | 慢车道 21-span 火焰图 |
| t_flow.png | Mermaid 流程图 |
| t_prompt.png | 动态 Prompt 快照 |
| t_trace.png | Trace 详情 |
| t_memory.png | 记忆/上下文三表 |