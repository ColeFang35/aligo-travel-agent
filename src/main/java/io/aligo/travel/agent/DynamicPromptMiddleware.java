package io.aligo.travel.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import io.aligo.travel.context.DynamicPromptStateMachine;
import io.aligo.travel.context.MemorySharingPolicy;
import io.aligo.travel.context.SessionMemoryManager.TripState;
import java.util.function.BiConsumer;
import reactor.core.publisher.Mono;

/**
 * 动态 Prompt 中间件（框架 MiddlewareBase 实现）。
 *
 * <p>对应文章"Prompt 工程（工程与智能体结合）- 动态 Prompt 组装机制，本质上为 AI
 * 构建了一个状态机"：基词（角色 + 任务 + 工具规范）在 builder 期固定；中间件在每次
 * {@code agent.call()} 前按需注入"当前阶段聚焦块 + 记忆共享层（L2 槽位 / L3 政策 /
 * L1 会话历史）"——模型注意力始终被工程手段聚焦在当前主链路，而非全量规则线性堆叠。
 *
 * <p>同时把组装后的最终 system prompt 快照存到 {@link PromptStore}，
 * 供调试助手"Prompt 查看"页复现每一次请求实际发给模型的完整提示词。
 */
public class DynamicPromptMiddleware implements MiddlewareBase {

    private final String agentKey;
    private final TripState trip;
    private final MemorySharingPolicy sharing;
    private final DynamicPromptStateMachine dsp;
    private final String sessionId;
    private final String tenantId;
    private final BiConsumer<String, String> snapshotSink;

    public DynamicPromptMiddleware(String agentKey, TripState trip, MemorySharingPolicy sharing,
                                   DynamicPromptStateMachine dsp, String sessionId, String tenantId,
                                   BiConsumer<String, String> snapshotSink) {
        this.agentKey = agentKey;
        this.trip = trip;
        this.sharing = sharing;
        this.dsp = dsp;
        this.sessionId = sessionId;
        this.tenantId = tenantId;
        this.snapshotSink = snapshotSink;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        StringBuilder sb = new StringBuilder(currentPrompt);
        // 1) 状态机阶段聚焦块：主链路边界（文章"明确业务边界，可控范围内交由 AI 自主处理"）
        sb.append('\n').append(dsp.stageBlock(trip)).append('\n');
        // 2) 记忆共享层（最小权限）：按需供给高相关性上下文
        if ("intent".equals(agentKey)) {
            sb.append(sharing.intentContext(sessionId, 3)).append('\n');
        }
        if ("itinerary".equals(agentKey) || "info".equals(agentKey) || "approval".equals(agentKey)) {
            sb.append(sharing.tripContext(trip)).append('\n');
        }
        if ("itinerary".equals(agentKey) || "knowledge".equals(agentKey)) {
            sb.append(sharing.policyContext(tenantId)).append('\n');
        }
        String finalPrompt = sb.toString();
        if (snapshotSink != null) {
            snapshotSink.accept(agentKey, finalPrompt);
        }
        return Mono.just(finalPrompt);
    }
}
