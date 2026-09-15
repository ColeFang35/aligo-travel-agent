package io.aligo.travel.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 出差申请工具：发起审批流 + 查询申请记录（文件持久化，重启/断电不丢）。
 *
 * <p>文章"为我提申请"按钮话术恰好是规则引擎快车道的固定套路：命中后跳过
 * 意图分析直接路由到本智能体。仓库默认持久化到 data/approvals.json（进程内
 * 缓存 + 落盘），接入真实 OA 时替换为对审批系统的读写即可，代码路径完全一致。
 */
@Component
public class ApprovalTool {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final Map<String, Map<String, Object>> REPO = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final Path STORE = Path.of("data", "approvals.json");

    static {
        load();
    }

    /** 从本地文件恢复申请单（含自增序号），服务重启后记录不丢失。 */
    private static synchronized void load() {
        try {
            if (Files.exists(STORE)) {
                List<?> list = OM.readValue(STORE.toFile(), List.class);
                int max = 0;
                for (Object o : list) {
                    Map<String, Object> rec = (Map<String, Object>) o;
                    String id = String.valueOf(rec.get("applyId"));
                    REPO.put(id, rec);
                    int n = parseSeq(id);
                    if (n > max) { max = n; }
                }
                SEQ.set(max);
            }
        } catch (Exception e) {
            // 数据文件损坏时忽略，从空仓库重新开始
        }
    }

    private static int parseSeq(String id) {
        try {
            String[] parts = id.split("-");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 落盘全部申请单。 */
    private static synchronized void persist() {
        try {
            Files.createDirectories(STORE.getParent());
            List<Map<String, Object>> list = new ArrayList<>(REPO.values());
            list.sort((a, b) -> String.valueOf(b.get("applyId")).compareTo(String.valueOf(a.get("applyId"))));
            String json = OM.writeValueAsString(list);
            Files.write(STORE, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Tool(description = "提交出差申请单，发起审批流程。")
    public String approval_submit(
            @ToolParam(name = "destination", description = "出差目的地") String destination,
            @ToolParam(name = "date", description = "出差日期 yyyy-MM-dd") String date,
            @ToolParam(name = "budget", description = "预算金额，元；可空") String budget,
            @ToolParam(name = "reason", description = "出差事由") String reason) {
        String id = "OA-" + String.format("%05d", SEQ.incrementAndGet());
        String b = budget == null || budget.isBlank() ? "按差标默认" : budget;
        Map<String, Object> rec = new java.util.HashMap<>();
        rec.put("applyId", id);
        rec.put("destination", destination);
        rec.put("date", date);
        rec.put("budget", b);
        rec.put("reason", reason == null || reason.isBlank() ? "" : reason);
        rec.put("status", "审批中（直属上级）");
        rec.put("sla", "24 小时内");
        rec.put("applyTime", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        REPO.put(id, rec);
        persist();
        return toJson(rec);
    }

    @Tool(description = "查询当前用户全部出差申请记录列表。")
    public String approval_list() {
        List<Map<String, Object>> records = new ArrayList<>(REPO.values());
        records.sort((a, b) -> String.valueOf(b.get("applyId")).compareTo(String.valueOf(a.get("applyId"))));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"_card\":\"approval_list\",\"count\":").append(records.size()).append(",\"records\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) { sb.append(","); }
            sb.append(toJson(records.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String toJson(Map<String, Object> rec) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"_card\":\"approval\",");
        sb.append("\"applyId\":\"").append(rec.get("applyId")).append("\",");
        sb.append("\"destination\":\"").append(rec.get("destination")).append("\",");
        sb.append("\"date\":\"").append(rec.get("date")).append("\",");
        sb.append("\"budget\":\"").append(rec.get("budget")).append("\",");
        sb.append("\"reason\":\"").append(rec.get("reason")).append("\",");
        sb.append("\"status\":\"").append(rec.get("status")).append("\",");
        sb.append("\"sla\":\"").append(rec.get("sla")).append("\"");
        sb.append("}");
        return sb.toString();
    }
}
