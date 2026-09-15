package io.aligo.travel.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** 每次请求的 Prompt 快照存储（调试页"Prompt 查看"数据源）。 */
@Component
public class PromptStore {

    public record Snapshot(String agentKey, String stage, String prompt, long ts) {
    }

    private final Map<String, List<Snapshot>> byTrace = new ConcurrentHashMap<>();

    public void put(String traceId, String agentKey, String stage, String prompt) {
        if (traceId == null) {
            return;
        }
        byTrace.computeIfAbsent(traceId, k -> new CopyOnWriteArrayList<>())
                .add(new Snapshot(agentKey, stage, prompt, System.currentTimeMillis()));
    }

    public List<Snapshot> list(String traceId) {
        return byTrace.getOrDefault(traceId, List.of());
    }

    public void remove(String traceId) {
        byTrace.remove(traceId);
    }
}
