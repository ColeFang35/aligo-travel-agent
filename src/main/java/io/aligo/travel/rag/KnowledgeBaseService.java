package io.aligo.travel.rag;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 知识库服务（模拟 MaxKB 标准化 API + 多企业隔离）。
 *
 * <p>对应文章"周边生态 - RAG 知识库"章节：
 * <ul>
 *   <li>标准化 API 接口：{@code kb_search(tenantId, query, topK)}，让 RAG 子智能体快速接入</li>
 *   <li>多企业隔离：tenantId 自动映射到对应企业知识库（千人千面）</li>
 *   <li>本地容器化部署：内置语料，零外部依赖</li>
 * </ul>
 * 真实部署时把本服务替换为 MaxKB HTTP API 客户端即可，智能体代码零改动。
 */
@Component
public class KnowledgeBaseService {

    private record Entry(String tenant, String title, List<String> tags, String content) {
    }

    private static final List<Entry> CORPUS = List.of(
            new Entry("tenant-alibaba", "差旅住宿差标", List.of("差标", "住宿", "酒店", "标准"),
                    "阿里巴巴集团员工出差住宿差标：一线城市（北上广深）400 元/晚封顶；二线城市 350 元/晚；三线及以下 300 元/晚。总监及以上职级上浮 25%。"),
            new Entry("tenant-alibaba", "差旅交通差标", List.of("机票", "高铁", "交通", "差标"),
                    "国内差旅交通差标：经济舱机票；高铁二等座。因行程冲突产生改签费的，凭项目出差单按 80% 报销。旺季（十一、春节）错峰率超 60% 的机票不受价上限限制。"),
            new Entry("tenant-alibaba", "预算与差标的区别", List.of("预算", "差标", "区别"),
                    "预算是指本次差旅出行的整体预算费用。差标是指本次差旅中，出行人乘坐飞机以及入住酒店等差旅类目的费用标准。系统默认预算 ≤ 差标总和，超预算需要走二次审批。"),
            new Entry("tenant-alibaba", "出差申请审批流程", List.of("审批", "申请", "流程", "OA"),
                    "出差申请审批流程：提交行程 → 直属上级审批（24h）→ HR 备案（48h）。紧急出差可走'先出差后补单'，补单须在出差结束后 5 个工作日内完成。申请需包含目的地、时间、预算、出差事由。"),
            new Entry("tenant-alibaba", "差旅报销材料清单", List.of("报销", "发票", "材料", "清单"),
                    "差旅报销材料清单：1) 机票行程单或电子发票；2) 酒店发票（需注明住宿人）；3) 出差审批单截图；4) 高铁车票纸质原件或电子票。材料不全的报销单笔次将退回。"),
            new Entry("tenant-demo", "差旅通用政策", List.of("差标", "机票", "酒店"),
                    "示例企业差旅政策：境内机票经济舱、酒店 300 元封顶；申请需提前 1 个工作日提交并形成 OA 流。"),
            new Entry("tenant-demo", "紧急出差说明", List.of("紧急", "出差", "补单"),
                    "示例企业紧急出差说明：紧急出差可在起飞前 2 小时内提交'紧急流水号'申请，事后 3 天内补办完整手续。"));

    /** 关键词打分检索（本地简化向量）：tags 命中的权重 3，content 命中的权重 1。 */
    public List<String> search(String tenantId, String query, int topK) {
        record Scored(Entry entry, double score) {
        }
        List<Scored> ranked = new ArrayList<>();
        for (Entry e : CORPUS) {
            if (!e.tenant().equals(tenantId)) {
                continue;
            }
            double score = 0;
            for (String tag : e.tags()) {
                if (query.contains(tag)) {
                    score += 3;
                }
            }
            for (String word : tokenize(query)) {
                if (e.content().contains(word)) {
                    score += 1;
                }
            }
            if (score > 0) {
                ranked.add(new Scored(e, score));
            }
        }
        // tenant 为空的请求自动兜底 alibaba 租户（演示"多企业隔离自动映射"）
        if (ranked.isEmpty() && !"tenant-alibaba".equals(tenantId)) {
            return search("tenant-alibaba", query, topK);
        }
        ranked.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, ranked.size()); i++) {
            Entry e = ranked.get(i).entry();
            out.add("【" + e.title() + "】" + e.content());
        }
        if (out.isEmpty()) {
            out.add("未检索到相关政策条目（租户 " + tenantId + "）。可补齐文档后重试。");
        }
        return out;
    }

    private List<String> tokenize(String query) {
        List<String> words = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (char c : query.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c >= 0x4E00) {
                sb.append(c);
            } else if (sb.length() >= 2) {
                words.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.setLength(0);
            }
        }
        if (sb.length() >= 2) {
            words.add(sb.toString());
        }
        return words;
    }

    /** 知识库检索工具（RAG 子智能体挂载的唯一工具）。 */
    @Component
    public static class KnowledgeTool {

        private final KnowledgeBaseService kb;

        public KnowledgeTool(KnowledgeBaseService kb) {
            this.kb = kb;
        }

        @Tool(description = "差旅知识库检索。返回 topK 条最相关的差旅政策/制度条目（含来源）。")
        public String kb_search(
                @ToolParam(name = "tenantId", description = "企业租户 id，决定映射到哪个企业知识库") String tenantId,
                @ToolParam(name = "query", description = "检索问题") String query,
                @ToolParam(name = "topK", description = "返回条目数") int topK) {
            List<String> hits = kb.search(tenantId, query, topK);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"_card\":\"kb\",\"tenant\":\"").append(tenantId).append("\",\"hits\":[");
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                String t = hits.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("{\"idx\":").append(i + 1).append(",\"text\":\"").append(t).append("\"}");
            }
            return sb.append("]}").toString();
        }
    }
}
