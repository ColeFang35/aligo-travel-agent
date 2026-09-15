package io.aligo.travel.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.aligo.travel.context.DynamicPromptStateMachine;
import io.aligo.travel.context.MemorySharingPolicy;
import io.aligo.travel.context.MemoryStack;
import io.aligo.travel.context.SessionMemoryManager;
import io.aligo.travel.context.SessionMemoryManager.SessionRow;
import io.aligo.travel.context.SessionMemoryManager.TripState;
import io.aligo.travel.intent.IntentClassifier;
import io.aligo.travel.intent.IntentDecision;
import io.aligo.travel.intent.IntentDecision.Mode;
import io.aligo.travel.intent.IntentDecision.Target;
import io.aligo.travel.model.ModelFactory;
import io.aligo.travel.observe.FlowTracer;
import io.aligo.travel.observe.Span;
import io.aligo.travel.observe.Span.Type;
import io.aligo.travel.observe.Trace;
import io.aligo.travel.thought.TaskCollector;
import io.aligo.travel.thought.TaskPrintHook;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 多智能体编排器（主规划智能体 + Handoffs/Routing 混合调度）。
 *
 * <p>严格对照文章架构：
 * <ol>
 *   <li>快慢车道意图识别：规则引擎命中 → 直接路由；否则意图识别智能体两段式输出
 *       （显式推理 + JSON 决策）</li>
 *   <li>main_plan_agent 动态 Prompt（按 classify() 结果选简单/复杂模板）+ Middleware
 *       注入状态机聚焦块与记忆共享层</li>
 *   <li>子智能体协作：Routing（行程规划/知识库，子结果回流主智能体汇总）与
 *       Handoffs（信息查询，接管本轮直接输出）</li>
 *   <li>思考链：TaskCollector（PENDING/DOING/DONE/FAILED + 层级 + 发布订阅）+
 *       TaskPrintHook（拦截 tool_use/tool_result）</li>
 *   <li>观测：FlowTracer 全链路 span（AGENT/LLM/TOOL/ROUTER），驱动火焰图/流程图/Token 统计</li>
 * </ol>
 */
@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    /** SSE 事件出口（Web 层把事件翻译成 SSE 帧）。 */
    public interface EventSink {
        void send(String type, Object payload);

        default void complete() {
        }
    }

    public record ChatRequest(String sessionId, String userId, String tenantId, String query) {
    }

    private final IntentClassifier classifier;
    private final DynamicPromptStateMachine dsp;
    private final MemorySharingPolicy sharing;
    private final SessionMemoryManager memory;
    private final SubAgentFactory factory;
    private final ModelFactory modelFactory;
    private final FlowTracer.Store traceStore;
    private final PromptStore promptStore;
    private final ObjectMapper om;

    public OrchestratorService(IntentClassifier classifier, DynamicPromptStateMachine dsp,
                               MemorySharingPolicy sharing, SessionMemoryManager memory,
                               SubAgentFactory factory, ModelFactory modelFactory,
                               FlowTracer.Store traceStore, PromptStore promptStore) {
        this.classifier = classifier;
        this.dsp = dsp;
        this.sharing = sharing;
        this.memory = memory;
        this.factory = factory;
        this.modelFactory = modelFactory;
        this.traceStore = traceStore;
        this.promptStore = promptStore;
        this.om = new ObjectMapper();
    }

    // ==================================================================== 主入口

    public void handle(ChatRequest req, EventSink sink) {
        String tenant = req.tenantId() == null || req.tenantId().isBlank() ? "tenant-alibaba" : req.tenantId();
        SessionRow session = memory.getOrCreate(req.sessionId(), req.userId(), tenant);
        String sessionId = session.sessionId;
        TripState trip = session.trip;
        memory.appendMsg(sessionId, "user", "user", req.query());

        FlowTracer tracer = new FlowTracer(sessionId, req.userId(), tenant, req.query());
        MemoryStack stack = new MemoryStack(sessionId);
        TaskCollector collector = new TaskCollector();
        collector.subscribe(node -> sink.send("task_update", node));

        sink.send("session_started", Map.of(
                "sessionId", sessionId,
                "provider", modelFactory.activeProvider(),
                "tenant", tenant));

        try {
            dsp.updateSlots(trip, req.query());

            // 1) 快慢车道意图识别
            IntentDecision decision = decide(req.query(), session, tracer, collector, stack, trip, sink);
            tracer.setLane(decision.fastLane ? "fast" : "slow");
            emitMeta(sink, decision);

            // 2) Handoffs + Routing 混合调度
            String finalText = dispatch(req.query(), decision, session, tracer, collector, stack, trip, sink);

            // 3) 上下文工程：写回三张表
            String intents = String.join("/", decision.intents);
            String agents = decision.targets.stream().map(Target::agentName).distinct()
                    .reduce((a, b) -> a + " → " + b).orElse("-");
            memory.appendTurn(sessionId, req.query(), FlowTracer.abbrev(finalText, 600), intents, agents);
            memory.appendMsg(sessionId, "assistant", "main_plan_agent", finalText);

            Trace t = tracer.finish();
            traceStore.add(t);
            sink.send("done", Map.of(
                    "traceId", t.id,
                    "lane", t.lane == null ? "" : t.lane,
                    "elapsedMs", (long) t.durationMs(),
                    "inputTokens", t.totalInputTokens,
                    "outputTokens", t.totalOutputTokens));
            sink.complete();
        } catch (Exception e) {
            log.error("handle failed", e);
            try {
                tracer.finish();
                traceStore.add(tracer.trace());
            } catch (Exception ignore) {
                // 忽略二次异常
            }
            sink.send("error", Map.of("message", String.valueOf(e.getMessage())));
            sink.complete();
        }
    }

    // ==================================================================== 意图识别（快慢车道）

    private IntentDecision decide(String query, SessionRow session, FlowTracer tracer,
                                  TaskCollector collector, MemoryStack stack, TripState trip, EventSink sink) {
        tracer.start("router", Type.ROUTER, "rule_engine.classify", null, query);
        IntentDecision rule = classifier.classify(query);
        if (rule != null) {
            tracer.end("router", "命中：" + String.join("/", rule.intents));
            return rule;
        }
        tracer.end("router", "未命中 → 进入慢车道（LLM 意图识别）");

        // 慢车道：意图识别智能体（显式推理两段式输出）
        AgentRun res = runAgent(new AgentRun(
                "intent", "intent_recognition_agent", dsp.intentPrompt(),
                query, 0, null, session, tracer, collector, stack, trip, sink, false));
        IntentDecision d = parseDecision(res.text(), query, tracer);
        return d;
    }

    /** 解析意图识别智能体的两段式输出（推理过程 + JSON 决策）。 */
    private IntentDecision parseDecision(String text, String query, FlowTracer tracer) {
        IntentDecision d = new IntentDecision();
        d.fastLane = false;
        d.rewrittenQuery = query;
        int i = text.indexOf('{');
        int j = text.lastIndexOf('}');
        try {
            if (i >= 0 && j > i) {
                String json = text.substring(i, j + 1);
                Map<?, ?> m = om.readValue(json, Map.class);
                d.reasoning = str(m.get("reasoning"));
                d.intents = strList(m.get("intents"));
                d.rewrittenQuery = m.get("rewritten_query") != null ? str(m.get("rewritten_query")) : query;
                d.confidence = m.get("confidence") instanceof Number n ? n.doubleValue() : 0.8;
                d.targets = targetsOf(strList(m.get("targets")));
                tracer.setDecision(json);
            }
        } catch (Exception e) {
            log.warn("decision json parse failed, fallback heuristics", e);
        }
        if (d.reasoning == null) {
            d.reasoning = text.lines().findFirst().orElse("");
        }
        if (d.targets.isEmpty()) {
            // 兜底启发式（显式推理失败的容错路径，消除文章提到的"识别异常请重试"类顽疾）
            d = heuristicDecision(query);
        }
        return d;
    }

    private IntentDecision heuristicDecision(String query) {
        IntentDecision d = new IntentDecision();
        d.fastLane = false;
        d.rewrittenQuery = query;
        d.confidence = 0.6;
        List<String> intents = new ArrayList<>();
        List<Target> targets = new ArrayList<>();
        if (query.matches(".*(规划|行程|出差|差旅).*")) {
            intents.add("行程规划");
            targets.add(new Target("itinerary", "itinerary_planning_agent", Mode.ROUTING));
        }
        if (query.matches(".*(机票|航班|酒店|天气|查).*")) {
            intents.add("信息查询");
            targets.add(new Target("info", "info_query_agent", Mode.HANDOFF));
        }
        if (query.matches(".*(政策|报销|差标|预算|审批流程|规定).*")) {
            intents.add("知识库问答");
            targets.add(new Target("knowledge", "rag_knowledge_agent", Mode.ROUTING));
        }
        if (query.matches(".*(申请|提单|审批).*")) {
            intents.add("提申请");
            targets.add(new Target("approval", "approval_agent", Mode.ROUTING));
        }
        if (targets.isEmpty()) {
            intents.add("闲聊/通用");
            targets.add(new Target("info", "info_query_agent", Mode.HANDOFF));
        }
        d.intents = intents;
        d.targets = targets;
        d.reasoning = "意图 JSON 解析失败，走关键词启发式兜底（工程容错）。";
        return d;
    }

    private List<Target> targetsOf(List<String> keys) {
        Map<String, Target> registry = Map.of(
                "itinerary", new Target("itinerary", "itinerary_planning_agent", Mode.ROUTING),
                "info", new Target("info", "info_query_agent", Mode.HANDOFF),
                "knowledge", new Target("knowledge", "rag_knowledge_agent", Mode.ROUTING),
                "approval", new Target("approval", "approval_agent", Mode.ROUTING));
        List<Target> out = new ArrayList<>();
        for (String k : keys) {
            String key = k.trim();
            if (key.contains("_")) {
                key = key.split("_")[0]; // 真实 LLM 可能输出全名 info_query_agent → info
            if ("rag".equals(key)) { key = "knowledge"; }
            }
            Target t = registry.get(key);
            if (t != null && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    // ==================================================================== 调度执行

    private String dispatch(String query, IntentDecision decision, SessionRow session,
                            FlowTracer tracer, TaskCollector collector, MemoryStack stack,
                            TripState trip, EventSink sink) {
        boolean hasHandoff = decision.targets.stream().anyMatch(t -> t.mode() == Mode.HANDOFF);
        List<Map<String, String>> subResults = new ArrayList<>();
        String handoffText = "";

        for (Target t : decision.targets) {
            String prompt = switch (t.agentKey()) {
                case "itinerary" -> dsp.itineraryPrompt();
                case "info" -> dsp.infoPrompt();
                case "knowledge" -> dsp.knowledgePrompt();
                case "approval" -> dsp.approvalPrompt();
                default -> dsp.infoPrompt();
            };
            AgentRun res = runAgent(new AgentRun(
                    t.agentKey(), t.agentName(), prompt,
                    decision.rewrittenQuery, 1, null, session, tracer, collector, stack, trip,
                    sink, true));
            subResults.add(Map.of("agentName", t.agentName(), "text", res.text()));
            if (t.mode() == Mode.HANDOFF) {
                handoffText = res.text();
            }
        }

        if (hasHandoff) {
            // Handoffs：子智能体接管本轮，直接输出最终回答
            return handoffText;
        }
        // Routing：子结果回流主规划智能体汇总
        StringBuilder sb = new StringBuilder("原计划请求：").append(query).append("\n\n子智能体结果汇总：\n");
        for (Map<String, String> r : subResults) {
            sb.append("- ").append(r.get("agentName")).append(" 结果：\n").append(r.get("text")).append("\n\n");
        }
        AgentRun main = runAgent(new AgentRun(
                "main", "main_plan_agent", dsp.mainPlanPrompt(decision),
                sb.toString(), 0, null, session, tracer, collector, stack, trip, sink, true));
        return main.text();
    }

    // ==================================================================== 单智能体执行（思考链+Trace 接入点）

    /** 一次子智能体执行的规格 + 结果（text()）。 */
    private static final class AgentRun {
        final String agentKey;
        final String agentName;
        final String sysPrompt;
        final String input;
        final int plannedLevel;
        final String parentSpanKey;
        final SessionRow session;
        final FlowTracer tracer;
        final TaskCollector collector;
        final MemoryStack stack;
        final TripState trip;
        final EventSink sink;
        final boolean streamText;
        String finalText = "";

        AgentRun(String agentKey, String agentName, String sysPrompt, String input,
                 int plannedLevel, String parentSpanKey, SessionRow session,
                 FlowTracer tracer, TaskCollector collector, MemoryStack stack,
                 TripState trip, EventSink sink, boolean streamText) {
            this.agentKey = agentKey;
            this.agentName = agentName;
            this.sysPrompt = sysPrompt;
            this.input = input;
            this.plannedLevel = plannedLevel;
            this.parentSpanKey = parentSpanKey;
            this.session = session;
            this.tracer = tracer;
            this.collector = collector;
            this.stack = stack;
            this.trip = trip;
            this.sink = sink;
            this.streamText = streamText;
        }

        String agentKey() { return agentKey; }
        String agentName() { return agentName; }
        String sysPrompt() { return sysPrompt; }
        String input() { return input; }
        int plannedLevel() { return plannedLevel; }
        String parentSpanKey() { return parentSpanKey; }
        SessionRow session() { return session; }
        FlowTracer tracer() { return tracer; }
        TaskCollector collector() { return collector; }
        MemoryStack stack() { return stack; }
        TripState trip() { return trip; }
        EventSink sink() { return sink; }
        boolean streamText() { return streamText; }
        String text() { return finalText; }
    }

    private AgentRun runAgent(AgentRun spec) {
        int seq = (int) Math.abs(System.nanoTime() % 100000);
        String agentSpanKey = "agent:" + spec.agentKey() + ":" + seq;
        String parentSpan = spec.stack().currentSpanKey();
        Span agentSpan = spec.tracer().start(agentSpanKey, Type.AGENT, spec.agentName(), parentSpan, spec.input());
        String agentSpanParent = agentSpan.id; // TOOL/LLM 子 span 挂 span.id，保证火焰图层级与流程图父子链一致

        var frame = spec.stack().push(spec.agentKey(), agentSpanKey);
        var planned = spec.collector().addPlanned(spec.agentKey(), spec.agentName(),
                agentLabel(spec.agentKey(), spec.agentName()), spec.plannedLevel(), null);
        spec.collector().doing(planned.id);
        spec.sink().send("agent_start", Map.of("agent", spec.agentKey(), "agentName", spec.agentName()));

        String traceId = spec.tracer().trace().id;
        DynamicPromptMiddleware mw = new DynamicPromptMiddleware(
                spec.agentKey(), spec.trip(), sharing, dsp, spec.session().sessionId,
                spec.session().tenantId,
                (a, p) -> promptStore.put(traceId, a, spec.trip().stage, p));
        TaskPrintHook hook = new TaskPrintHook(
                spec.agentKey(), spec.agentName(), spec.collector(), spec.tracer(),
                spec.plannedLevel() + 1, agentSpanParent, planned.id,
                cardJson -> emitCard(spec.sink(), spec.agentKey(), cardJson));

        ReActAgent agent = factory.build(spec.agentKey(), spec.sysPrompt(),
                spec.session().tenantId, hook, mw);

        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        final Msg[] resultMsg = new Msg[1];
        final boolean[] failed = {false};

        Msg userMsg = new UserMessage("user", spec.input());
        agent.streamEvents(userMsg)
                .doOnNext((AgentEvent ev) -> {
                    if (ev instanceof ThinkingBlockDeltaEvent e) {
                        thinking.append(e.getDelta());
                        spec.sink().send("think", Map.of("agent", spec.agentKey(),
                                "agentName", spec.agentName(), "delta", e.getDelta()));
                    } else if (ev instanceof TextBlockDeltaEvent e) {
                        answer.append(e.getDelta());
                        if (spec.streamText()) {
                            spec.sink().send("text", Map.of("agent", spec.agentKey(),
                                    "agentName", spec.agentName(), "delta", e.getDelta()));
                        }
                    } else if (ev instanceof ModelCallStartEvent e) {
                        spec.tracer().start("llm:" + e.getReplyId(), Type.LLM, "llm.call",
                                agentSpanParent, "");
                    } else if (ev instanceof ModelCallEndEvent e) {
                        ChatUsage u = e.getUsage();
                        spec.tracer().end("llm:" + e.getReplyId(), "", null,
                                u != null ? u.getInputTokens() : 0,
                                u != null ? u.getOutputTokens() : 0,
                                u != null ? u.getTime() : 0);
                    } else if (ev instanceof AgentResultEvent e) {
                        resultMsg[0] = e.getResult();
                    } else if (ev instanceof ExceedMaxItersEvent) {
                        failed[0] = true;
                    }
                })
                .doOnError(err -> {
                    failed[0] = true;
                    spec.sink().send("warn", Map.of("message", spec.agentName() + " 异常：" + err.getMessage()));
                })
                .onErrorResume(err -> reactor.core.publisher.Flux.empty())
                .blockLast();

        String text = answer.length() > 0 ? answer.toString()
                : resultMsg[0] != null && resultMsg[0].getTextContent() != null
                        ? resultMsg[0].getTextContent() : "";
        spec.finalText = text;
        spec.collector().addResult(planned.id, FlowTracer.abbrev(text, 300), failed[0]);
        spec.tracer().end(agentSpanKey, FlowTracer.abbrev(text, 400), failed[0] ? "agent failed" : null, 0, 0, 0);
        spec.sink().send("agent_end", Map.of("agent", spec.agentKey(), "agentName", spec.agentName()));
        spec.stack().pop();
        return spec;
    }

    private void emitCard(EventSink sink, String agent, String cardJson) {
        try {
            Object parsed = om.readValue(cardJson, Object.class);
            Map<?, ?> card;
            if (parsed instanceof Map<?, ?> m) {
                card = m;
            } else if (parsed instanceof String s && s.contains("_card")) {
                card = om.readValue(s, Map.class);
            } else {
                log.warn("card skip: unexpected type={}", parsed == null ? "null" : parsed.getClass().getSimpleName());
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agent", agent);
            payload.put("data", card);
            sink.send("card", payload);
        } catch (Exception e) {
            log.warn("card parse failed", e);
        }
    }

    private void emitMeta(EventSink sink, IntentDecision d) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lane", d.fastLane ? "fast" : "slow");
        meta.put("intents", d.intents);
        meta.put("targets", d.targets.stream()
                .map(t -> Map.of("agent", t.agentKey(), "agentName", t.agentName(), "mode", t.mode().name()))
                .toList());
        meta.put("reasoning", d.reasoning);
        meta.put("rewrittenQuery", d.rewrittenQuery);
        meta.put("confidence", d.confidence);
        sink.send("meta", meta);
    }

    private String agentLabel(String key, String name) {
        return switch (key) {
            case "intent" -> "多意图识别与调度决策";
            case "itinerary" -> "行程规划：机票+酒店+天气+卡片";
            case "info" -> "信息查询（Handoffs 接管）";
            case "knowledge" -> "知识库检索回答";
            case "approval" -> "提交出差申请";
            default -> name;
        };
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object e : l) {
                out.add(String.valueOf(e));
            }
            return out;
        }
        return List.of();
    }
}
