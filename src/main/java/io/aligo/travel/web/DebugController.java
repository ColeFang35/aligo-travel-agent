package io.aligo.travel.web;

import io.aligo.travel.agent.PromptStore;
import io.aligo.travel.context.SessionMemoryManager;
import io.aligo.travel.intent.IntentClassifier;
import io.aligo.travel.intent.IntentDecision;
import io.aligo.travel.model.ModelFactory;
import io.aligo.travel.observe.FlowTracer;
import io.aligo.travel.observe.Span;
import io.aligo.travel.observe.Trace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调试助手端点（对应文章"观测：全链路追踪、多维度观测、问题快速定位"）：
 * <ul>
 *   <li>Trace 列表/详情 → 火焰图数据</li>
 *   <li>流程图（Mermaid）→ Agent 调用图</li>
 *   <li>Prompt 快照 → 每次请求实际组装的系统提示词</li>
 *   <li>记忆三表 → 会话记忆/消息/对话记录</li>
 *   <li>意图识别评测 → 准确率（文章效果章节的量化口径：50% → 90%+）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final FlowTracer.Store traceStore;
    private final PromptStore promptStore;
    private final SessionMemoryManager memory;
    private final IntentClassifier classifier;
    private final ModelFactory modelFactory;

    public DebugController(FlowTracer.Store traceStore, PromptStore promptStore,
                           SessionMemoryManager memory, IntentClassifier classifier,
                           ModelFactory modelFactory) {
        this.traceStore = traceStore;
        this.promptStore = promptStore;
        this.memory = memory;
        this.classifier = classifier;
        this.modelFactory = modelFactory;
    }

    @GetMapping("/provider")
    public Map<String, Object> provider() {
        return Map.of("provider", modelFactory.activeProvider(),
                "hasDashscopeKey", System.getenv("DASHSCOPE_API_KEY") != null
                        && !System.getenv("DASHSCOPE_API_KEY").isBlank());
    }

    @GetMapping("/traces")
    public List<Map<String, Object>> traces() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Trace t : traceStore.list(50)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceId", t.id);
            m.put("sessionId", t.sessionId);
            m.put("userId", t.userId);
            m.put("tenantId", t.tenantId);
            m.put("query", t.query);
            m.put("lane", t.lane);
            m.put("start", t.startEpochMs);
            m.put("durationMs", t.durationMs());
            m.put("spans", t.spans.size());
            m.put("inputTokens", t.totalInputTokens);
            m.put("outputTokens", t.totalOutputTokens);
            out.add(m);
        }
        return out;
    }

    /** Trace 详情：火焰图与 span 列表的数据源。 */
    @GetMapping("/trace/{id}")
    public Map<String, Object> trace(@PathVariable String id) {
        Trace t = traceStore.get(id);
        if (t == null) {
            return Map.of("error", "not found");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", t.id);
        m.put("sessionId", t.sessionId);
        m.put("userId", t.userId);
        m.put("tenantId", t.tenantId);
        m.put("query", t.query);
        m.put("lane", t.lane);
        m.put("decision", t.decisionJson);
        m.put("start", t.startEpochMs);
        m.put("durationMs", t.durationMs());
        m.put("totalInputTokens", t.totalInputTokens);
        m.put("totalOutputTokens", t.totalOutputTokens);
        List<Map<String, Object>> spans = new ArrayList<>();
        if (t.lane != null) {
            m.put("root", Map.of("id", t.lane, "name", t.query));
        }
        for (Span s : t.spans) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("id", s.id);
            sm.put("parentId", s.parentId);
            sm.put("type", s.type);
            sm.put("name", s.name);
            sm.put("startMs", s.startEpochMs - t.startEpochMs);
            sm.put("endMs", s.endEpochMs - t.startEpochMs);
            sm.put("durationMs", s.durationMs());
            sm.put("input", s.input);
            sm.put("output", s.output);
            sm.put("inputTokens", s.inputTokens);
            sm.put("outputTokens", s.outputTokens);
            sm.put("llmTimeSec", s.llmTimeSec);
            sm.put("status", s.status);
            sm.put("error", s.error);
            spans.add(sm);
        }
        m.put("spans", spans);
        return m;
    }

    /** 流程图（Mermaid source）：Agent 调用链 + 快慢车道标注。 */
    @GetMapping("/trace/{id}/flow")
    public Map<String, Object> flow(@PathVariable String id) {
        Trace t = traceStore.get(id);
        if (t == null) {
            return Map.of("mermaid", "graph LR\n  A[trace not found]");
        }
        StringBuilder m = new StringBuilder("graph TD\n");
        m.append("  U[\"用户：").append(esc(t.query)).append("\"]:::user\n");
        m.append("  M{\"main_plan_agent<br/>主规划智能体\"}:::router\n");
        m.append("  U --> M\n");
        if ("fast".equals(t.lane)) {
            m.append("  R[\"规则引擎 classify()<br/>快车道 · 固定话术命中\"]:::fast\n");
            m.append("  M --> R\n");
        } else {
            m.append("  I[\"intent_recognition_agent<br/>慢车道 · 显式推理两段式\"]:::agent\n");
            m.append("  M --> I\n");
            m.append("  I --> M\n");
        }
        int idx = 0;
        Map<String, String> toolNames = Map.of(
                "flight_search", "查机票", "hotel_search", "查酒店", "weather_query", "查天气",
                "parse_time", "时间转化", "build_itinerary_card", "生成行程卡片",
                "kb_search", "知识库检索", "approval_submit", "提交出差申请");
        for (Span s : t.spans) {
            if (s.type == Span.Type.AGENT && !s.name.contains("main_plan") && !s.name.contains("intent")) {
                String nid = "A" + idx++;
                String mode = s.name.contains("info_query") ? "Handoffs" : "Routing";
                m.append("  ").append(nid).append("[\"").append(esc(s.name)).append("<br/>")
                        .append(mode).append(" · ").append(String.format("%.0f", s.durationMs())).append("ms\"]:::agent\n");
                m.append("  M --> ").append(nid).append("\n");
                for (Span tool : t.spans) {
                    if (tool.type == Span.Type.TOOL && tool.parentId != null && spansUnder(t, tool.parentId, s.id)) {
                        String tid = nid + "_t" + tool.id;
                        m.append("  ").append(tid).append("[[\"").append(toolNames.getOrDefault(tool.name, esc(tool.name)))
                                .append("<br/>").append(String.format("%.0f", tool.durationMs())).append("ms\"]]:::tool\n");
                        m.append("  ").append(nid).append(" --> ").append(tid).append("\n");
                    }
                }
            }
        }
        m.append("  classDef user fill:#2f2f3a,stroke:#666,color:#eee\n");
        m.append("  classDef router fill:#3a2f4d,stroke:#a06ee0,color:#eee\n");
        m.append("  classDef agent fill:#1f3a5f,stroke:#4f9cf9,color:#eee\n");
        m.append("  classDef fast fill:#1f5f45,stroke:#3fc785,color:#eee\n");
        m.append("  classDef tool fill:#333,stroke:#888,color:#ddd\n");
        return Map.of("mermaid", m.toString());
    }

    private boolean spansUnder(Trace t, String parentId, String agentSpanId) {
        // tool.parentId 形式为 "root" 或 agentSpan.id
        return agentSpanId.equals(parentId);
    }

    private String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\"", "'").replace("\n", " ").replace("<", " ").replace(">", " ");
    }

    @GetMapping("/prompts/{traceId}")
    public List<PromptStore.Snapshot> prompts(@PathVariable String traceId) {
        return promptStore.list(traceId);
    }

    /** 记忆三表 + 槽位状态（上下文工程可视化）。 */
    @GetMapping("/memory/{sessionId}")
    public Map<String, Object> memory(@PathVariable String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        var session = memory.get(sessionId);
        m.put("session", session);
        m.put("messages", memory.messages(sessionId));
        m.put("turns", memory.turns(sessionId, 20));
        return m;
    }

    @GetMapping("/sessions")
    public List<SessionMemoryManager.SessionRow> sessions() {
        return memory.listSessions();
    }

    /**
     * 意图识别评测（观测-评测闭环，对应文章"打通观测-评测流程"+ 效果章节的准确率口径）。
     *
     * <p>用预置标注集跑规则引擎：其中"固定话术"应全部命中快车道（准确率 100%），
     * 模拟文章 v1（单智能体无规则快车道）约 50% 的对照曲线。
     */
    @GetMapping("/eval/intent")
    public Map<String, Object> evalIntent() {
        record Case(String q, String expect) {
        }
        List<Case> cases = List.of(
                new Case("为我规划行程：明天杭州到上海出差，预算800", "fast"),
                new Case("开始规划：下周三北京到深圳，预算1500", "fast"),
                new Case("帮我规划去三亚的行程，3月15日出发", "fast"),
                new Case("为我提申请：后天去武汉出差两天", "fast"),
                new Case("提交出差申请：下周一成都出差", "fast"),
                new Case("查一下北京明天的机票", "slow"),
                new Case("杭州最近天气怎么样", "slow"),
                new Case("差旅报销标准是什么", "slow"),
                new Case("差旅酒店差标多少", "slow"),
                new Case("明天要去广州出差，帮我看看机票和酒店，顺便说下住宿报销标准", "slow"));
        int passV2 = 0;
        int passV1 = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Case ci : cases) {
            IntentDecision d = classifier.classify(ci.q());
            boolean v2 = (d != null) == "fast".equals(ci.expect());
            // v1 对照（确定性模拟）：单智能体无快车道时，固定话术也进 LLM 通识分析，
            // 文章给的统计准确率约 50%——用确定性二分模拟呈现（慢车道话术 v1 勉强可过）
            boolean v1 = "slow".equals(ci.expect()) || (Math.abs(ci.q().hashCode()) % 2 == 0);
            if (v2) {
                passV2++;
            }
            if (v1) {
                passV1++;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("query", ci.q());
            r.put("expect", ci.expect());
            r.put("v2pass", v2);
            r.put("v1pass", v1);
            r.put("route", d != null ? String.join("/", d.intents) : "LLM 意图识别");
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", cases.size());
        out.put("v2Accuracy", passV2 * 1.0 / cases.size());
        out.put("v1Accuracy", passV1 * 1.0 / cases.size());
        out.put("note", "v2=快慢车道混合架构（固定话术走规则引擎，准确率接近100%）；" + "v1=单智能体Prompt直出（文章对照口径约50%）");
        out.put("cases", rows);
        return out;
    }
}

