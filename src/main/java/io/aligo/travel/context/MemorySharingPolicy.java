package io.aligo.travel.context;

import io.aligo.travel.context.SessionMemoryManager.TripState;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 记忆分层与共享策略（最小权限原则）。
 *
 * <p>对应文章上下文工程-记忆共享："系统默认各智能体独立管理自身对话历史；对高相关性
 * 的智能体动态开放必要的上下文信息"。本类给出三个分层：
 * <ul>
 *   <li>L1 会话精简对话记录：给意图识别（消歧）与 main_plan 汇总</li>
 *   <li>L2 行程槽位状态：给 itinerary / info / approval（复用已收集要素，避免重复追问）</li>
 *   <li>L3 租户差旅政策要点：给 itinerary / knowledge（贴合差标推荐）</li>
 * </ul>
 * 每层都不同 agent 各取所需，既隔离又不重复收集。
 */
@Component
public class MemorySharingPolicy {

    private final SessionMemoryManager memory;

    public MemorySharingPolicy(SessionMemoryManager memory) {
        this.memory = memory;
    }

    /** L1：精简对话记录（对话记录表最近 N 轮），意图识别消歧的原料。 */
    public String intentContext(String sessionId, int lastN) {
        List<SessionMemoryManager.TurnRow> rows = memory.turns(sessionId, lastN);
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【会话历史（理解指代与消歧的必要上下文）】\n");
        for (SessionMemoryManager.TurnRow r : rows) {
            sb.append("- 用户：").append(r.query).append("\n");
            if (r.intent != null) {
                sb.append("  意图：").append(r.intent).append("；调用：").append(r.agents).append("\n");
            }
        }
        return sb.toString();
    }

    /** L2：行程槽位状态，高相关性子智能体共享（信息收集不断片）。 */
    public String tripContext(TripState trip) {
        if (trip.destination == null && trip.departure == null && trip.date == null) {
            return "";
        }
        return String.format("【已知行程要素】出发=%s 到达=%s 日期=%s 预算=%s（阶段：%s）。"
                        + "若用户输入缺失要素，优先复用此状态，不要重复追问。",
                nullToDash(trip.departure), nullToDash(trip.destination), nullToDash(trip.date),
                nullToDash(trip.budget), trip.stage);
    }

    /** L3：租户差旅政策要点，行程/知识库智能体按此贴合差标回答。 */
    public String policyContext(String tenantId) {
        if ("tenant-demo".equals(tenantId)) {
            return "【当前企业差旅政策要点】境内机票经济舱；酒店 300 元/晚封顶；申请需提前 1 个工作日提交。";
        }
        return "【当前企业差旅政策要点】一线城市住宿差标 400 元/晚、二线 350 元、三线 300 元；"
                + "国内机票经济舱；高铁二等座；预算 ≤ 差标总和，超预算走二次审批。";
    }

    private String nullToDash(String s) {
        return s == null ? "-" : s;
    }
}
