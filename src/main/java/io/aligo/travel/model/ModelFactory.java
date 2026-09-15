package io.aligo.travel.model;

import io.agentscope.core.model.Model;
import io.aligo.travel.config.TravelProperties;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型工厂：按配置产出 Mock 剧本模型或通义千问（DashScope）。
 *
 * <p>对应文章选型决策："AgentScope 对 Qwen 系列模型原生支持"；无 API Key 时自动
 * 降级 Mock，保证演示在任何环境可复现。
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final TravelProperties props;

    public ModelFactory(TravelProperties props) {
        this.props = props;
    }

    /** 每次构建新的 Model 实例（tenant 隔离以 mock 更自然）。 */
    public Model create(String tenantId) {
        String provider = props.effectiveProvider();
        if ("dashscope".equals(provider)) {
            log.info("LLM provider=DashScope modelId={}", props.getModelId());
            return DashScopeChatModel.builder()
                    .modelName(stripPrefix(props.getModelId()))
                    .build();
        }
        log.info("LLM provider=mock (scripted)");
        return new ScriptedMockModel(tenantId);
    }

    /** 生效模式（UI badge 用）。 */
    public String activeProvider() {
        return props.effectiveProvider();
    }

    private String stripPrefix(String modelId) {
        int i = modelId.indexOf(':');
        return i >= 0 ? modelId.substring(i + 1) : modelId;
    }
}
