package io.aligo.travel.model;

import io.aligo.travel.context.SlotExtractor;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 剧本导演（mock 模式的心脏）：决定每个 Agent 在 ReAct 第 N 轮产出什么。
 *
 * <p>它按"系统提示中的 [AGENT:xxx] 标记 + 会话中已出现的工具轮次"推进剧本，
 * 从而在没有真实 LLM 的情况下，让思考链、工具调用、卡片生成、RAG 检索、
 * 流程图、火焰图、Token 统计全部真实走通 —— 行为对应 Qwen 在文章 Prompt
 * 设计下的典型输出，保证演示 100% 可复现；接入真实模型时代码路径完全一致。
 */
public final class ScriptDirector {

    private ScriptDirector() {
    }

    /** 一次"模型响应"的剧本规格：thinking + 工具调用 or 纯文本收束。 */
    public static final class ResponseSpec {
        public String thinking;
        public String toolName;
        public Map<String, Object> toolInput;
        public String text;
        public String tenantId = "tenant-alibaba";

        public boolean isFinal() {
            return toolName == null;
        }
    }

    /** 从 messages（最后一条 user + 历史 tool 轮次 + 系统标记）推导下一步。 */
    public static ResponseSpec nextStep(String agentKey, String userText, int toolRound, String tenantId) {
        ResponseSpec spec = new ResponseSpec();
        spec.tenantId = tenantId;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        switch (agentKey) {
            case "intent" -> {
                spec.thinking = "融合会话上下文对 query 做消歧，识别意图并给出调度决策…";
                spec.text = intentText(userText);
                return spec;
            }
            case "itinerary" -> {
                String dep = pick(userText, "departure", today);
                String dst = pick(userText, "destination", today);
                String date = pick(userText, "date", today);
                switch (toolRound) {
                    case 0 -> {
                        spec.thinking = "先查机票：" + dep + " → " + dst + "，日期 " + date;
                        spec.toolName = "flight_search";
                        spec.toolInput = Map.of("departure", dep, "destination", dst, "date", date);
                    }
                    case 1 -> {
                        spec.thinking = "机票有了，接着查 " + dst + " 的酒店";
                        spec.toolName = "hotel_search";
                        spec.toolInput = Map.of("city", dst, "checkInDate", date, "nights", 1);
                    }
                    case 2 -> {
                        spec.thinking = "再查目的地天气，评估出行体验";
                        spec.toolName = "weather_query";
                        spec.toolInput = Map.of("city", dst);
                    }
                    case 3 -> {
                        spec.thinking = "信息齐全，生成行程卡片（一键下单入口）";
                        spec.toolName = "build_itinerary_card";
                        spec.toolInput = Map.of(
                                "departure", dep, "destination", dst, "date", date,
                                "flightNo", "", "hotelName", "");
                    }
                    default -> {
                        spec.text = finalItineraryText(dep, dst, date);
                    }
                }
                return spec;
            }
            case "info" -> {
                if (mentions(userText, "机票", "航班", "飞机")) {
                    if (toolRound == 0) {
                        spec.thinking = "用户在查机票，先解析时间再查航班";
                        spec.toolName = "flight_search";
                        spec.toolInput = Map.of(
                                "departure", pick(userText, "departure", today),
                                "destination", pick(userText, "destination", today),
                                "date", pick(userText, "date", today));
                        return spec;
                    }
                    spec.text = "已通过机票查询工具拿到结果（见上方机票卡片）。选哪一班我可以继续帮你预订；"
                            + "如果还想看酒店或天气，直接说一声即可。";
                    return spec;
                }
                if (mentions(userText, "酒店", "住宿", "宾馆")) {
                    if (toolRound == 0) {
                        spec.thinking = "用户在查酒店";
                        spec.toolName = "hotel_search";
                        spec.toolInput = Map.of(
                                "city", pick(userText, "destination", today),
                                "checkInDate", pick(userText, "date", today), "nights", 1);
                        return spec;
                    }
                    spec.text = "已查到酒店列表（见上方酒店卡片），价格按差标从高到低排序，"
                            + "看中哪一家告诉我，我来下单。";
                    return spec;
                }
                if (mentions(userText, "天气", "气温", "下雨")) {
                    if (toolRound == 0) {
                        spec.thinking = "用户在问天气";
                        spec.toolName = "weather_query";
                        spec.toolInput = Map.of("city", pick(userText, "destination", today));
                        return spec;
                    }
                    spec.text = "天气结果见上方卡片。出行前记得按天气准备衣物；需要改签或调整行程我可以继续。";
                    return spec;
                }
                if (mentions(userText, "时间", "几号", "日期", "什么时候")) {
                    if (toolRound == 0) {
                        spec.thinking = "日期时间转化：把口语时间落成具体日期";
                        spec.toolName = "parse_time";
                        spec.toolInput = Map.of("expression", userText.length() > 24 ? "后天上午" : userText);
                        return spec;
                    }
                    spec.text = "时间解析结果见上方卡片。要继续规划行程吗？告诉我城市即可。";
                    return spec;
                }
                spec.text = "收到。你可以让我查机票、酒店、天气，或把时间表达转成具体日期；"
                        + "也可以说'帮我规划 X 到 Y 的行程'，我会走规划流程。";
                return spec;
            }
            case "knowledge" -> {
                if (toolRound == 0) {
                    spec.thinking = "RAG：在租户 " + tenantId + " 的差旅知识库检索相关政策";
                    spec.toolName = "kb_search";
                    spec.toolInput = Map.of("tenantId", tenantId, "query", userText, "topK", 3);
                    return spec;
                }
                spec.text = "已检索到相关政策条目（见上方知识卡片，含来源）。如需按差标下单，我可以基于政策直接推荐。";
                return spec;
            }
            case "approval" -> {
                if (isApprovalQuery(userText)) {
                    if (toolRound == 0) {
                        spec.thinking = "识别到申请记录查询意图，拉取全部申请单";
                        spec.toolName = "approval_list";
                    } else {
                        spec.text = "已拉取你的全部出差申请记录（见上方列表卡片）。"
                                + "如有需要，可针对其中某一张补充说明、修改或催办。";
                    }
                    return spec;
                }
                if (toolRound == 0) {
                    spec.thinking = "生成出差申请单：目的地/日期/预算要素齐全后发起审批流";
                    spec.toolName = "approval_submit";
                    spec.toolInput = Map.of(
                            "destination", pick(userText, "destination", today),
                            "date", pick(userText, "date", today),
                            "budget", pick(userText, "budget", today),
                            "reason", userText.length() > 40 ? userText.substring(0, 40) : userText);
                    return spec;
                }
                spec.text = "出差申请已提交（审批单见上方卡片），审批通过后会短信通知。"
                        + "通过后我可以直接帮你一键下单机票酒店。";
                return spec;
            }
case "main" -> {
                    // 主规划智能体汇总（Routing 收尾）：从"子智能体结果汇总"输入提取要点做商旅风格收尾
                    String q = extractAfter(userText, "原计划请求：");
                    StringBuilder bullets = new StringBuilder();
                    if (userText.contains("itinerary_planning_agent")) {
                        bullets.append("行程规划：机票/酒店/天气与一键下单卡片已生成；");
                    }
                    if (userText.contains("rag_knowledge_agent")) {
                        bullets.append("政策问答：知识库条目已检索（见知识卡片）；");
                    }
                    if (userText.contains("approval_agent")) {
                        bullets.append("出差申请：审批单已提交，等待上级审批；");
                    }
                    if (userText.contains("info_query_agent")) {
                        bullets.append("信息查询：查询结果见卡片；");
                    }
                    if (bullets.length() == 0) {
                        bullets.append("各子智能体结果见上方卡片与详情；");
                    }
                    spec.thinking = "子智能体结果已回流，按主链路做跨智能体汇总收尾";
                    spec.text = "已完成对「" + (q.isBlank() ? userText : q) + "」的统筹处理。"
                            + bullets + "如需调整（时间/酒店档次/改签）直接告诉我；若本次为差旅，"
                            + "我可以同时提交出差申请走审批。";
                    return spec;
                }
                default -> {
                    spec.text = "收到，我是 AliGo 差旅助手。可以帮你规划行程、查机票酒店天气、"
                            + "解答差旅政策，或提交出差申请。";
                    return spec;
                }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String pick(String userText, String slot, LocalDate today) {
        Map<String, String> s = SlotExtractor.extract(userText, today);
        String v = s.get(slot);
        if (v != null) {
            return v;
        }
        return switch (slot) {
            case "departure" -> "杭州";
            case "destination" -> "上海";
            case "date" -> today.plusDays(2).toString();
            default -> "";
        };
    }

    private static boolean mentions(String text, String... words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 意图识别智能体的两段式输出：显式推理 + JSON 决策（对应文章"显式推理"设计）。
     * mock 剧本按关键词覆盖多意图组合，行为可复现。
     */
    private static String intentText(String userText) {
        List<String> intents = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        boolean approvalQuery = mentions(userText, "申请", "提单", "审批")
                && (mentions(userText, "记录", "列表", "所有", "全部")
                || mentions(userText, "查看", "查询") || userText.contains("查"));
        if (approvalQuery) {
            intents.add("申请记录查询");
            targets.add("approval");
            String rationaleQ = "用户输入「" + userText + "」：命中 申请/审批 + 查询 关键词，判定「申请记录查询」，"
                    + "直接路由 approval 智能体查询全部申请记录（列表卡片），避免误当作新建申请。";
            String rewrittenQ = userText;
            String jsonQ = String.format(
                    """
                    {"reasoning": %s, "intents": %s, "targets": %s, "rewritten_query": %s, "confidence": 0.93}""",
                    quotes(rationaleQ), jsonArray(intents), jsonArray(targets), quotes(rewrittenQ));
            return "推理过程：" + rationaleQ + "\n\n决策：" + jsonQ;
        }

        if (mentions(userText, "规划", "行程", "出差", "差旅")) {
            intents.add("行程规划");
            targets.add("itinerary");
        }
        if (mentions(userText, "机票", "航班", "酒店", "天气", "查")) {
            intents.add("信息查询");
            targets.add("info");
        }
        if (mentions(userText, "政策", "报销", "差标", "预算", "审批流程", "规定")) {
            intents.add("知识库问答");
            targets.add("knowledge");
        }
        if (mentions(userText, "申请", "提单", "审批")) {
            intents.add("提申请");
            targets.add("approval");
        }
        if (intents.isEmpty()) {
            intents.add("闲聊/通用");
            targets.add("info");
        }

        String rationale = String.format(
                "用户输入「%s」%s。关键词覆盖：%s，判定为 %d 个意图。补全槽位后改写 query，决定调用 %s。",
                userText,
                userText.length() > 12 ? "(长句慢车道)" : "(短句)",
                String.join("/", intents), intents.size(), String.join("+", targets));

        // 纠正一个事实：改写就是标准化口语 query 补全上下文（文章 "query 改写" 职责）
        String rewritten = userText;
        if (userText.contains("后天") && intents.contains("行程规划")) {
            rewritten = userText + "（日期已解析为后天，即 " + LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2) + "）";
        }

        String json = String.format(
                """
                {"reasoning": %s, "intents": %s, "targets": %s, "rewritten_query": %s, "confidence": 0.92}""",
                quotes(rationale), jsonArray(intents), jsonArray(targets), quotes(rewritten));

        return "推理过程：" + rationale + "\n\n决策：" + json;
    }

    /** 截取 anchor 之后到行末的文本（main 汇总剧本用）。 */
    private static String extractAfter(String text, String anchor) {
        int i = text.indexOf(anchor);
        if (i < 0) {
            return "";
        }
        String tail = text.substring(i + anchor.length());
        int e = tail.indexOf(10);
        return (e < 0 ? tail : tail.substring(0, e)).trim();
    }

    private static String finalItineraryText(String dep, String dst, String date) {
        return String.format(
                "已为您规划 %s → %s（%s）的行程：机票、酒店、天气见上方卡片。"
                        + "可直接在一键下单。如需调整出发时间或酒店档次，告诉我即可；"
                        + "若本次为差旅，我还可以同时提交出差申请走审批。",
                dep, dst, date);
    }

    private static String quotes(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static String jsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quotes(items.get(i)));
        }
        return sb.append(']').toString();
    }
    /** 判断是否为"查询申请记录"而非"提交申请"。 */
    private static boolean isApprovalQuery(String userText) {
        if (userText == null || userText.isBlank()) {
            return false;
        }
        return mentions(userText, "记录", "列表", "历史", "查看", "查询", "所有", "全部");
    }

}