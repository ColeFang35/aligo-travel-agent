package io.aligo.travel.intent;

import io.aligo.travel.intent.IntentDecision.Mode;
import io.aligo.travel.intent.IntentDecision.Target;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 规则引擎（快慢车道里的"快车道"）。
 *
 * <p>对应文章分层处理策略："将明确的用户意图通过快速通道直接路由，复杂语义
 * 理解仍交由 AI 智能分析"。命中 → 跳过 LLM 意图分析直接路由；未命中返回
 * {@code null} → 慢车道（意图识别智能体）。规则为固定套路的话术（如界面按钮
 * 触发的固定文案），这恰恰是规则引擎擅长且准确率 100% 的场景。
 */
@Component
public class IntentClassifier {

    private record Rule(Pattern pattern, String intent, String agentKey, String agentName, Mode mode) {
        boolean matches(String q) {
            return pattern.matcher(q).find();
        }
    }

    // 按钮/固定话术 → 直接路由（准确率~100%，毫秒级）。
    // 话术锚定句首：只有"整句就是固定套路"才跳慢车道；复杂长句（即便含相同关键词）
    // 仍交给 LLM 慢车道做多意图识别 —— 对应文章"整体就是个'快慢车道'的设计"。
    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("^(?:为我规划行程|开始规划|帮我规划)"),
                    "行程规划", "itinerary", "itinerary_planning_agent", Mode.ROUTING),
            new Rule(Pattern.compile("^(?:为我提申请|提交出差申请|提申请)"),
                    "提申请", "approval", "approval_agent", Mode.ROUTING));

    /**
     * 规则匹配：命中返回决策（fastLane=true），未命中返回 null（走慢车道）。
     */
    public IntentDecision classify(String userInput) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        IntentDecision d = new IntentDecision();
        StringBuilder intents = new StringBuilder();
        for (Rule r : RULES) {
            if (r.matches(userInput)) {
                if (!seen.containsKey(r.agentKey())) {
                    seen.put(r.agentKey(), true);
                    d.targets.add(new Target(r.agentKey(), r.agentName(), r.mode()));
                }
                intents.append(intents.isEmpty() ? "" : "/").append(r.intent());
            }
        }
        if (d.targets.isEmpty()) {
            return null;
        }
        d.fastLane = true;
        d.reasoning = "规则引擎命中固定话术「" + intents + "」（如界面按钮触发），"
                + "跳过 LLM 意图分析直接路由，消除不必要延迟。";
        d.intents = List.of(intents.toString().split("/"));
        d.rewrittenQuery = userInput;
        d.confidence = 1.0;
        return d;
    }
}

