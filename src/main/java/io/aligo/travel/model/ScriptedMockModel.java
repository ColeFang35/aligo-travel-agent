package io.aligo.travel.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import io.aligo.travel.model.ScriptDirector.ResponseSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 剧本模型：实现 AgentScope {@link Model} 接口，用确定性剧本替代真实 LLM。
 *
 * <p>价值：agent 的 Hook、事件流、工具执行、思考链、Trace 火焰图等全部走真实
 * 代码路径（ReActAgent 并不感知模型是真是假），使得系统在没有 API Key 的离线
 * 环境下也能完整复现文章架构；一旦配置 {@code DASHSCOPE_API_KEY}，同一套编排
 * 立即切换为通义千问（文章选型：AgentScope 对 Qwen 原生支持）。
 */
public class ScriptedMockModel implements Model {

    /** Agent 身份标记：写在 sysPrompt 开头，mock 版据此走剧本。 */
    public static final String AGENT_MARKER = "[AGENT:%s]";

    private final String tenantId;

    public ScriptedMockModel(String tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String agentKey = agentKeyOf(messages);
        String userText = lastUserText(messages);
        int toolRound = countToolRounds(messages);

        ResponseSpec spec = ScriptDirector.nextStep(agentKey, userText, toolRound, tenantId);
        ChatResponse response = toChatResponse(spec, inputTokensOf(messages));

        // 模拟推理耗时：思考链有节奏地展开，火焰图上也能看到 LLM 时间片
        return Flux.just(response).delayElements(Duration.ofMillis(600));
    }

    @Override
    public String getModelName() {
        return "mock:scripted-qwen";
    }

    // ------------------------------------------------------------------

    private ChatResponse toChatResponse(ResponseSpec spec, int inputTokens) {
        var content = new ArrayList<io.agentscope.core.message.ContentBlock>();
        if (spec.thinking != null && !spec.thinking.isBlank()) {
            content.add(ThinkingBlock.builder().thinking(spec.thinking).build());
        }
        if (spec.isFinal()) {
            content.add(TextBlock.builder().text(spec.text).build());
        } else {
            // content 字段是工具调用的“原始 JSON 参数”（对应真实模型流式输出的 arguments），
            // 框架 ToolValidator 用它做 JSON Schema 校验；input Map 用于实际执行。
            content.add(ToolUseBlock.builder()
                    .id(UUID.randomUUID().toString())
                    .name(spec.toolName)
                    .input(spec.toolInput)
                    .content(JsonUtils.getJsonCodec().toJson(spec.toolInput))
                    .build());
        }
        int outTokens = Math.max(16, totalLen(spec) / 4);
        return ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .content(content)
                .usage(new ChatUsage(inputTokens, outTokens, 0.6))
                .finishReason("stop")
                .build();
    }

    private int totalLen(ResponseSpec spec) {
        int len = 0;
        if (spec.thinking != null) {
            len += spec.thinking.length();
        }
        if (spec.text != null) {
            len += spec.text.length();
        }
        if (spec.toolInput != null) {
            len += spec.toolInput.toString().length();
        }
        return len;
    }

    private int inputTokensOf(List<Msg> messages) {
        int chars = 0;
        for (Msg m : messages) {
            String t = m.getTextContent();
            if (t != null) {
                chars += t.length();
            }
        }
        return Math.max(32, chars / 4);
    }

    private String agentKeyOf(List<Msg> messages) {
        if (!messages.isEmpty()) {
            String sys = messages.get(0).getTextContent();
            if (sys != null && sys.contains("[AGENT:")) {
                int i = sys.indexOf("[AGENT:");
                int j = sys.indexOf(']', i);
                if (j > i) {
                    return sys.substring(i + 7, j);
                }
            }
        }
        return "unknown";
    }

    private String lastUserText(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (m.getRole() == MsgRole.USER) {
                String t = m.getTextContent();
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        return messages.isEmpty() ? "" : "" + messages.get(messages.size() - 1).getTextContent();
    }

    private int countToolRounds(List<Msg> messages) {
        int n = 0;
        for (Msg m : messages) {
            if (!m.getContentBlocks(ToolUseBlock.class).isEmpty()) {
                n++;
            }
        }
        return n;
    }
}

