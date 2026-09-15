package io.aligo.travel.thought;

import io.agentscope.core.hook.ActingEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.aligo.travel.observe.Span.Type;
import io.aligo.travel.observe.FlowTracer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * TaskPrintHook：对应文章"ReActAgent Hook + 前置打印钩子函数"的设计。
 *
 * <p>在 Agent 执行过程中实时拦截消息：
 * <ul>
 *   <li>捕获 tool_use（PreActingEvent）→ TaskCollector.add_use + Tracer 工具 span 开始</li>
 *   <li>捕获 tool_result（PostActingEvent）→ add_result + span 结束 + 卡片 JSON 抽取</li>
 * </ul>
 * 从而实现对工具调用及其结果的细粒度追踪与状态同步（实时思考链的数据源）。
 */
public class TaskPrintHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(TaskPrintHook.class);

    private final String agentKey;
    private final String agentName;
    private final TaskCollector collector;
    private final FlowTracer tracer;
    private final int level;
    private final String parentSpanKey;
    private final String parentNodeId;
    private final Consumer<String> cardSink;

    private final Map<String, String> nodeByToolCall = new ConcurrentHashMap<>();
    private final Map<String, String> spanByToolCall = new ConcurrentHashMap<>();

    public TaskPrintHook(String agentKey, String agentName, TaskCollector collector, FlowTracer tracer,
                         int level, String parentSpanKey, String parentNodeId, Consumer<String> cardSink) {
        this.agentKey = agentKey;
        this.agentName = agentName;
        this.collector = collector;
        this.tracer = tracer;
        this.level = level;
        this.parentSpanKey = parentSpanKey;
        this.parentNodeId = parentNodeId;
        this.cardSink = cardSink;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        try {
            if (event instanceof PreActingEvent e) {
                onToolUse(e);
            } else if (event instanceof PostActingEvent e) {
                onToolResult(e);
            }
        } catch (Exception ex) {
            log.warn("TaskPrintHook error", ex);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 300;
    }

    private void onToolUse(PreActingEvent e) {
        var toolUse = cu(e);
        String callId = toolUse.getId();
        String name = toolUse.getName();
        String input = toolUse.getContent() != null ? toolUse.getContent() : String.valueOf(toolUse.getInput());
        // add_use()：思考链出现 DOING 工具节点；Tracer 同步开 TOOL span
        var node = collector.addUse(agentKey, agentName, name, input, level, parentNodeId);
        nodeByToolCall.put(callId, node.id);
        if (tracer != null) {
            var span = tracer.start("tool:" + callId, Type.TOOL, name, parentSpanKey, input);
            spanByToolCall.put(callId, "tool:" + callId);
        }
    }

    private void onToolResult(PostActingEvent e) {
        String callId = e.getToolResult() != null ? e.getToolResult().getId() : null;
        StringBuilder out = new StringBuilder();
        if (e.getToolResult() != null && e.getToolResult().getOutput() != null) {
            for (ContentBlock cb : e.getToolResult().getOutput()) {
                if (cb instanceof TextBlock tb) {
                    out.append(tb.getText());
                }
            }
        }
        String result = out.toString();
        String nodeId = callId != null ? nodeByToolCall.remove(callId) : null;
        boolean failed = false;
        if (nodeId != null) {
            collector.addResult(nodeId, result, false);
        }
        if (tracer != null && callId != null) {
            String spanKey = spanByToolCall.remove(callId);
            if (spanKey != null) {
                tracer.end(spanKey, result);
            }
        }
        // 卡片数据与任务映射：tool_result 中带 _card 标记的 JSON 直接推给输出层
        if (cardSink != null && result.contains("_card")) {
            cardSink.accept(result);
        }
    }

    private io.agentscope.core.message.ToolUseBlock cu(ActingEvent e) {
        return (io.agentscope.core.message.ToolUseBlock) e.getToolUse();
    }
}

