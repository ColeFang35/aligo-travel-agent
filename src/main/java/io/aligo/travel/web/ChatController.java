package io.aligo.travel.web;

import io.aligo.travel.agent.OrchestratorService;
import io.aligo.travel.agent.OrchestratorService.ChatRequest;
import io.aligo.travel.agent.OrchestratorService.EventSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天 SSE 端点（对应文章"流式输出层"-"构建结构化最终响应并生成符合 SSE 格式的输出流"）。
 *
 * <p>事件协议：
 * <ul>
 *   <li>{@code session_started} 会话与模型模式</li>
 *   <li>{@code meta} 快慢车道 + 意图决策（显式推理）</li>
 *   <li>{@code task_update} 思考链任务节点（TaskCollector 发布订阅）</li>
 *   <li>{@code think} / {@code text} 思考增量 / 回答增量</li>
 *   <li>{@code card} 结构化卡片（_card 标记抽取自 tool_result）</li>
 *   <li>{@code agent_start/agent_end/warn/error/done}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OrchestratorService orchestrator;
    private final ObjectMapper om = new ObjectMapper();

    public ChatController(OrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String sessionId,
                             @RequestParam(defaultValue = "demo-user") String userId,
                             @RequestParam(required = false) String tenantId,
                             @RequestParam String query) {
        SseEmitter emitter = new SseEmitter(180_000L);
        EventSink sink = new EventSink() {
            @Override
            public void send(String type, Object payload) {
                try {
                    emitter.send(SseEmitter.event().name(type).data(om.writeValueAsString(payload)));
                } catch (IOException e) {
                    log.debug("sse send failed: {}", e.getMessage());
                }
            }

            @Override
            public void complete() {
                emitter.complete();
            }
        };
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "chat-worker");
            t.setDaemon(true);
            return t;
        }).submit(() -> orchestrator.handle(new ChatRequest(sessionId, userId, tenantId, query), sink));
        return emitter;
    }
}
