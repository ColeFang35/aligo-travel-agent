package io.aligo.travel.intent;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图识别决策（意图识别智能体的结构化输出）。
 *
 * <p>对应文章意图识别职责：多意图识别和分类、智能体调度决策（targets）、
 * query 改写（rewrittenQuery）、显式推理（reasoning，两段式结构的第一段）。
 */
public class IntentDecision {
    public String reasoning;                       // 显式推理过程
    public List<String> intents = new ArrayList<>();       // 意图标签
    public List<Target> targets = new ArrayList<>();       // 调度目标
    public String rewrittenQuery;                  // 改写后的标准化 query
    public double confidence;
    public boolean fastLane;                       // 规则引擎快车道命中？

    public record Target(String agentKey, String agentName, Mode mode) {
    }

    public enum Mode {
        ROUTING,  // 纯路由：子结果回流主智能体汇总（行程规划、知识库查询场景）
        HANDOFF   // 交接：控制权移交，子智能体直接输出最终回答（信息查询场景）
    }
}

