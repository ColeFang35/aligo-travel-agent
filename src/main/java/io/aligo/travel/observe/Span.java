package io.aligo.travel.observe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 追踪 Span（Langfuse 风格）：Agent / LLM / 工具 / 路由 / 意图识别。 */
public class Span {

    public enum Type {
        AGENT, LLM, TOOL, ROUTER, INTENT, SESSION
    }

    public String id;
    public String parentId;
    public Type type;
    public String name;
    public long startNanos;
    public long endNanos;
    public long startEpochMs;
    public long endEpochMs;
    public String input;
    public String output;
    public Map<String, Object> attrs = new ConcurrentHashMap<>();
    /** 本 span 内累计的 prompt/completion token（mock 时长为估算） */
    public int inputTokens;
    public int outputTokens;
    public double llmTimeSec;
    public String status = "RUNNING"; // RUNNING | OK | ERROR
    public String error;

    public double durationMs() {
        return (endNanos - startNanos) / 1_000_000.0;
    }
}

