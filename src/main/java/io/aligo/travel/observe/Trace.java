package io.aligo.travel.observe;

import java.util.ArrayList;
import java.util.List;

/** 一次对话的全链路追踪（火焰图与流程图的数据源）。 */
public class Trace {
    public String id;
    public String sessionId;
    public String userId;
    public String tenantId;
    public String query;
    public long startEpochMs;
    public long endEpochMs;
    public final List<Span> spans = new ArrayList<>();
    public int totalInputTokens;
    public int totalOutputTokens;
    public String lane; // fast | slow（快慢车道标识，对应文章分层处理策略）
    public String decisionJson; // 意图决策 JSON（显式推理两段式中的决策部分）

    public double durationMs() {
        return endEpochMs - startEpochMs;
    }
}
