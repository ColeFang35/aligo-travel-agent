package io.aligo.travel.observe;

import io.aligo.travel.observe.Span.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全链路追踪器（Langfuse 风格轻量实现）。
 *
 * <p>对应文章观测章节"全链路追踪：从用户输入到最终输出，追踪完整的智能体调用链路，
 * 包括 LLM 调用、工具使用、记忆管理等所有环节"以及"Trace(单次对话)、Session(会话)、
 * User(用户) 三个维度"的目标。火焰图、流程图、Token 统计全部由这里的 span 驱动。
 */
public class FlowTracer {

    private final Trace trace;
    private final Span root;
    private final Map<String, Span> open = new ConcurrentHashMap<>();

    public FlowTracer(String sessionId, String userId, String tenantId, String query) {
        this.trace = new Trace();
        this.trace.id = UUID.randomUUID().toString().substring(0, 8);
        this.trace.sessionId = sessionId;
        this.trace.userId = userId;
        this.trace.tenantId = tenantId;
        this.trace.query = query;
        this.trace.startEpochMs = System.currentTimeMillis();
        this.root = new Span();
        this.root.id = "root";
        this.root.type = Type.SESSION;
        this.root.name = "CHAT " + abbrev(query, 24);
        this.root.startEpochMs = trace.startEpochMs;
        this.root.startNanos = System.nanoTime();
    }

    public Span start(String key, Type type, String name, String parentId, String input) {
        Span s = new Span();
        s.id = UUID.randomUUID().toString().substring(0, 8);
        s.type = type;
        s.name = name;
        s.parentId = parentId == null ? root.id : parentId;
        s.input = abbrev(input, 500);
        s.startEpochMs = System.currentTimeMillis();
        s.startNanos = System.nanoTime();
        open.put(key, s);
        return s;
    }

    public void end(String key, String output) {
        end(key, output, null, 0, 0, 0);
    }

    public void end(String key, String output, String error, int inputTokens, int outputTokens, double llmTimeSec) {
        Span s = open.remove(key);
        if (s == null) {
            return;
        }
        s.output = abbrev(output, 500);
        s.error = error;
        s.status = error == null ? "OK" : "ERROR";
        s.inputTokens = inputTokens;
        s.outputTokens = outputTokens;
        s.llmTimeSec = llmTimeSec;
        s.endNanos = System.nanoTime();
        s.endEpochMs = System.currentTimeMillis();
        trace.spans.add(s);
        trace.totalInputTokens += inputTokens;
        trace.totalOutputTokens += outputTokens;
    }

    public Span root() {
        return root;
    }

    public Trace trace() {
        return trace;
    }

    public void setLane(String lane) {
        trace.lane = lane;
    }

    public void setDecision(String decisionJson) {
        trace.decisionJson = decisionJson;
    }

    public Trace finish() {
        trace.endEpochMs = System.currentTimeMillis();
        root.endNanos = System.nanoTime();
        root.endEpochMs = trace.endEpochMs;
        root.status = "OK";
        return trace;
    }

    public static String abbrev(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------ store

    /** 进程内 TraceStore（Session/User 维度的视图来自对 Trace 列表的聚合查询）。 */
    @org.springframework.stereotype.Component
    public static class Store {
        private final List<Trace> traces = new CopyOnWriteArrayList<>();

        public void add(Trace t) {
            traces.add(t);
        }

        public Trace get(String id) {
            return traces.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
        }

        public List<Trace> list(int limit) {
            int from = Math.max(0, traces.size() - limit);
            List<Trace> out = new java.util.ArrayList<>(traces.subList(from, traces.size()));
            java.util.Collections.reverse(out);
            return out;
        }

        public int size() {
            return traces.size();
        }
    }
}



