package io.aligo.travel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 基于 AgentScope 2.0 Java 的智能旅游助手（复刻阿里商旅 AliGo 多智能体架构）。
 *
 * <p>架构分层（严格对照文章）：
 * <ul>
 *   <li>主规划智能体 main_plan_agent + 意图识别智能体（快慢车道）</li>
 *   <li>Handoffs + Routing 混合的多智能体协作</li>
 *   <li>ReActAgent Hook + TaskCollector 实时思考链 + SSE 流式输出</li>
 *   <li>上下文工程：记忆分层与共享（最小权限）+ 动态 Prompt（状态机）</li>
 *   <li>观测：Langfuse 式 Trace（火焰图 / 流程图 / Token 统计）</li>
 *   <li>RAG 知识库（模拟 MaxKB 标准化 API + 多企业隔离）+ 准确率评测</li>
 * </ul>
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
