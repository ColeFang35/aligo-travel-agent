package io.aligo.travel.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.tool.Toolkit;
import io.aligo.travel.model.ModelFactory;
import io.aligo.travel.rag.KnowledgeBaseService.KnowledgeTool;
import io.aligo.travel.tool.ApprovalTool;
import io.aligo.travel.tool.ItineraryCardTool;
import io.aligo.travel.tool.TravelTools;
import org.springframework.stereotype.Component;

/**
 * 子智能体工厂：按 agentKey 装配 ReActAgent + 对应工具集。
 *
 * <p>对应文章"工具注册管理"（上下文工程四大件之一）：每个子智能体只挂载自己
 * 领域所需的工具，遵循最小权限；每次请求全新构建（无状态设计，便于分布式）。
 */
@Component
public class SubAgentFactory {

    private final ModelFactory modelFactory;
    private final TravelTools travelTools;
    private final ItineraryCardTool itineraryCardTool;
    private final KnowledgeTool knowledgeTool;
    private final ApprovalTool approvalTool;

    public SubAgentFactory(ModelFactory modelFactory, TravelTools travelTools,
                           ItineraryCardTool itineraryCardTool, KnowledgeTool knowledgeTool,
                           ApprovalTool approvalTool) {
        this.modelFactory = modelFactory;
        this.travelTools = travelTools;
        this.itineraryCardTool = itineraryCardTool;
        this.knowledgeTool = knowledgeTool;
        this.approvalTool = approvalTool;
    }

    /** 构建一次请求用的 ReActAgent（基词 + 中间件动态 prompt + Hook 思考链）。 */
    public ReActAgent build(String agentKey, String sysPrompt, String tenantId, Hook hook,
                            MiddlewareBase middleware) {
        Toolkit toolkit = toolkitFor(agentKey);
        var builder = ReActAgent.builder()
                .name(agentKey)
                .sysPrompt(sysPrompt)
                .model(modelFactory.create(tenantId))
                .toolkit(toolkit)
                .maxIters(12);
        if (hook != null) {
            builder.hook(hook);
        }
        if (middleware != null) {
            builder.middleware(middleware);
        }
        return builder.build();
    }

    private Toolkit toolkitFor(String agentKey) {
        Toolkit tk = new Toolkit();
        switch (agentKey) {
            case "itinerary" -> {
                tk.registerTool(travelTools);
                tk.registerTool(itineraryCardTool);
            }
            case "info" -> tk.registerTool(travelTools);
            case "knowledge" -> tk.registerTool(knowledgeTool);
            case "approval" -> tk.registerTool(approvalTool);
            default -> {
                // intent / main：无工具
            }
        }
        return tk;
    }
}

