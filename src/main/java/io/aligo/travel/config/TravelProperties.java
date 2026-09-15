package io.aligo.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配置项：
 * <ul>
 *   <li>{@code travel.llm.provider}：{@code mock}（剧本模型，零依赖可复现）或 {@code dashscope}（通义千问）</li>
 *   <li>{@code travel.llm.modelId}：AgentScope 模型 id，如 {@code dashscope:qwen-plus}</li>
 * </ul>
 * dashscope 模式需设置环境变量 {@code DASHSCOPE_API_KEY}（对应文章"对 Qwen 系列模型原生支持"的选型）。
 */
@Component
@ConfigurationProperties(prefix = "travel.llm")
public class TravelProperties {

    /** mock | dashscope */
    private String provider = "mock";
    /** AgentScope 模型 id（provider:model 由 ModelRegistry 解析） */
    private String modelId = "dashscope:qwen-plus";
    /** 是否容忍无 key 时自动降级到 mock */
    private boolean fallbackToMock = true;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public boolean isFallbackToMock() {
        return fallbackToMock;
    }

    public void setFallbackToMock(boolean fallbackToMock) {
        this.fallbackToMock = fallbackToMock;
    }

    /** 真实生效的 provider：配置了 dashscope 但没有 key 时自动降级到 mock。 */
    public String effectiveProvider() {
        if ("dashscope".equalsIgnoreCase(provider)) {
            String key = System.getenv("DASHSCOPE_API_KEY");
            if (key == null || key.isBlank()) {
                return fallbackToMock ? "mock" : "dashscope";
            }
        }
        return provider;
    }
}
