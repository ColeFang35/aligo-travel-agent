package io.aligo.travel.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.aligo.travel.dao.Approval;
import io.aligo.travel.dao.ApprovalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 出差申请工具：发起审批流 + 查询申请记录。
 *
 * <p>持久化：审批单写入 <b>MySQL</b>（MyBatis 数据访问层），状态变更由数据库
 * <b>触发器</b>自动写入审计表；单号在 SQL 内计算；统计走<b>存储过程</b>与<b>函数</b>。
 * 查询：列表走 <b>Redis</b> 缓存（60s TTL），提交后主动失效，降低数据库压力。
 *
 * <p>"为我提申请"是规则引擎快车道的固定话术，命中后直达本智能体。
 */
@Component
public class ApprovalTool {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTool.class);
    private static final String CACHE_KEY = "aligo:approval:list";
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ApprovalMapper mapper;
    private final StringRedisTemplate redis;

    public ApprovalTool(ApprovalMapper mapper, StringRedisTemplate redis) {
        this.mapper = mapper;
        this.redis = redis;
    }

    @Tool(description = "提交出差申请单，发起审批流程。")
    public String approval_submit(
            @ToolParam(name = "destination", description = "出差目的地") String destination,
            @ToolParam(name = "date", description = "出差日期 yyyy-MM-dd") String date,
            @ToolParam(name = "budget", description = "预算金额，元；可空") String budget,
            @ToolParam(name = "reason", description = "出差事由") String reason) {

        Approval a = new Approval();
        a.setApplyId(mapper.nextApplyId());                       // 单号在 SQL 内生成
        a.setDestination(destination);
        a.setTravelDate(parseDate(date));
        a.setBudget(budget == null || budget.isBlank() ? "按差标默认" : budget);
        a.setReason(reason == null ? "" : reason);
        a.setStatus("审批中（直属上级）");
        a.setSla("24 小时内");
        a.setApplyTime(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        mapper.insert(a);                                          // 触发器自动写审计

        evictCache();
        return toJson(a);
    }

    @Tool(description = "查询当前用户全部出差申请记录列表。")
    public String approval_list() {
        String cached = getCache();
        if (cached != null) {
            log.debug("approval_list 命中 Redis 缓存");
            return cached;
        }
        List<Approval> records = mapper.findAll();
        String json = buildListJson(records);
        setCache(json);
        return json;
    }

    // ---------------- Redis 缓存（可选：Redis 不可用时自动跳过，不影响主流程） ----------------

    private String getCache() {
        try {
            return redis.opsForValue().get(CACHE_KEY);
        } catch (Exception e) {
            log.warn("Redis 读取失败，回退数据库: {}", e.getMessage());
            return null;
        }
    }

    private void setCache(String json) {
        try {
            redis.opsForValue().set(CACHE_KEY, json, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 写入失败: {}", e.getMessage());
        }
    }

    private void evictCache() {
        try {
            redis.delete(CACHE_KEY);
        } catch (Exception e) {
            log.warn("Redis 失效失败: {}", e.getMessage());
        }
    }

    // ---------------- 组装返回给前端的卡片 JSON ----------------

    private static LocalDate parseDate(String s) {
        try {
            return (s == null || s.isBlank()) ? null : LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String buildListJson(List<Approval> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"_card\":\"approval_list\",\"count\":").append(records.size()).append(",\"records\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) { sb.append(","); }
            sb.append(toJson(records.get(i)));
        }
        return sb.append("]}").toString();
    }

    private static String toJson(Approval r) {
        return new StringBuilder("{")
                .append("\"_card\":\"approval\",")
                .append("\"applyId\":\"").append(nz(r.getApplyId())).append("\",")
                .append("\"destination\":\"").append(nz(r.getDestination())).append("\",")
                .append("\"date\":\"").append(r.getTravelDate() == null ? "" : r.getTravelDate().format(D)).append("\",")
                .append("\"budget\":\"").append(nz(r.getBudget())).append("\",")
                .append("\"reason\":\"").append(nz(r.getReason())).append("\",")
                .append("\"status\":\"").append(nz(r.getStatus())).append("\",")
                .append("\"sla\":\"").append(nz(r.getSla())).append("\"")
                .append("}").toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
