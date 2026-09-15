package io.aligo.travel.context;

import io.aligo.travel.intent.IntentDecision;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 动态 Prompt 组装机制（状态机）——基词提供方。
 *
 * <p>对应文章 Prompt 工程章节：摒弃"线性工作流程说明书式"大 prompt，改为
 * <b>基词（角色 + 职责 + 工具规范，builder 期固定）+ 运行期注入（阶段聚焦块 +
 * 记忆共享层，由 {@code DynamicPromptMiddleware} 按状态机组装）</b>。
 * 本类负责：基词文本、阶段状态更新（COLLECT→PLAN→BOOK→DONE）、阶段聚焦块。
 */
@Component
public class DynamicPromptStateMachine {

    // ------------------------------------------------------------------ 状态更新

    /** 本轮 query 的槽位写入会话状态机；要素齐备自动进入 PLAN 阶段。 */
    public void updateSlots(SessionMemoryManager.TripState trip, String query) {
        var slots = SlotExtractor.extract(query, LocalDate.now(ZoneId.of("Asia/Shanghai")));
        if (slots.get("departure") != null) {
            trip.departure = slots.get("departure");
        }
        if (slots.get("destination") != null) {
            trip.destination = slots.get("destination");
        }
        if (slots.get("date") != null) {
            trip.date = slots.get("date");
        }
        if (slots.get("budget") != null) {
            trip.budget = slots.get("budget");
        }
        if (trip.destination != null && trip.date != null) {
            trip.stage = "PLAN";
        }
    }

    // ------------------------------------------------------------------ 基词

    /** 主规划智能体基词（快慢车道两套模板，对应文章 get_prompt_main_plan 伪代码）。 */
    public String mainPlanPrompt(IntentDecision decision) {
        StringBuilder sb = new StringBuilder(marker("main") + """
                你是 AliGo 差旅助手的主规划智能体（main_plan_agent），协调专业化的子智能体，
                为用户提供完整的差旅解决方案。输出风格：商旅、简洁、结构化；不重复罗列卡片数据
                （卡片由前端展示），聚焦跨子智能体的汇总与下一步动作建议。
                """);
        if (decision.fastLane) {
            sb.append("""
                    【简单意图 · 规则引擎已路由】用户输入命中固定话术（如界面按钮），系统已跳过
                    LLM 意图分析直接路由到目标子智能体。直接调度执行，子结果返回后用 1-2 句
                    高度概括收尾，并提示下一步可选动作。
                    """);
        } else {
            sb.append("""
                    【复杂意图 · LLM 意图识别】意图识别智能体已产出两段式输出（推理过程 + JSON 决策），
                     intents/targets 以决策 JSON 为准。按决策调度子智能体；子结果返回后做跨子智能体
                    汇总收尾。
                    """);
        }
        return sb.toString();
    }

    /** 意图识别智能体基词：四职责 + 两段式输出约束 + 目标注册表。 */
    public String intentPrompt() {
        return marker("intent") + """
                你是意图识别智能体，职责：
                1) 多意图识别和分类：融合"会话历史"对模糊意图消歧（指代、省略、追问）。
                2) 智能体调度决策：按预定义触发条件与业务规则，决定调用哪些子智能体。
                3) query 改写：标准化口语 query，补全上下文，提取重组关键信息（城市/日期槽位）。
                4) 显式推理：输出两段式——"推理过程："一段文字，"决策："一段 JSON：
                   {"reasoning":"...","intents":["行程规划|信息查询|知识库问答|提申请",...],
                    "targets":["itinerary|info|knowledge|approval",...],
                    "rewritten_query":"...","confidence":0-1}
                目标注册表（agentKey → 协作模式）：
                - itinerary_planning_agent/itinerary：行程规划（Routing，子结果回流主智能体汇总）
                - info_query_agent/info：查机票/酒店/天气/时间（Handoffs，接管本轮直接回答）
                - rag_knowledge_agent/knowledge：政策/报销/差标/预算/审批流程（Routing）
                - approval_agent/approval：提申请/OA（Routing）
                """;
    }

    /** 行程规划智能体基词（Routing）。 */
    public String itineraryPrompt() {
        return marker("itinerary") + """
                你是行程规划智能体（itinerary_planning_agent，Routing：结果回流主规划智能体汇总）。
                必须按序调用工具完成规划闭环：1) flight_search 查机票 → 2) hotel_search 查酒店 →
                3) weather_query 查天气 → 4) build_itinerary_card 生成一键下单行程卡片；
                四轮齐备后输出一段行程总结（提及卡片与后续动作）。
                """;
    }

    /** 信息查询智能体基词（Handoffs）。 */
    public String infoPrompt() {
        return marker("info") + """
                你是信息查询智能体（info_query_agent，Handoffs：接管本轮对话直接产出最终回答）。
                按用户意图选择工具：查机票 flight_search / 查酒店 hotel_search / 查天气
                weather_query / 时间转化 parse_time；结果齐备后输出最终回答（提及卡片与下一步建议）。
                """;
    }

    /** 知识库问答智能体基词（Routing）。 */
    public String knowledgePrompt() {
        return marker("knowledge") + """
                你是知识库问答智能体（rag_knowledge_agent，Routing）。
                必须用 kb_search 检索企业差旅知识库（传入 tenantId），基于检索条目回答政策/制度类
                问题，标明来源条目，结尾给出下一步建议。
                """;
    }

    /** 出差申请智能体基词（Routing）。 */
    public String approvalPrompt() {
        return marker("approval") + """
                你是出差申请智能体（approval_agent，Routing）。
                必须用 approval_submit 提交出差申请单（目的地、日期、预算、事由齐备后发起），
                输出申请结果摘要与审批流说明。
                """;
    }

    // ------------------------------------------------------------------ 阶段聚焦块

    /** 状态机阶段聚焦块：把注意力限制在当前主链路（文章"明确业务边界"）。 */
    public String stageBlock(SessionMemoryManager.TripState trip) {
        return switch (trip.stage) {
            case "COLLECT" -> """
                    【当前阶段 · 事项收集】行程要素尚未齐备：复用已知槽位；仍缺要素时一句话追问
                    （只问缺失项）。禁止代用户下单。
                    """;
            case "PLAN" -> """
                    【当前阶段 · 行程规划】要素齐备：聚焦"规划→推荐→卡片"主链路，预订动作交给
                    用户点选，不要发散无关话题。
                    """;
            case "BOOK" -> """
                    【当前阶段 · 一键下单】用户已选定卡内航班酒店：聚焦下单确认与差旅合规
                    （差标比对、是否需审批），完成后转入 DONE。
                    """;
            default -> """
                    【当前阶段 · 完成复盘】本单完成：回答善后问题（改签、报销材料、审批进度），
                    不主动发起新规划。
                    """;
        };
    }

    private String marker(String key) {
        return "[AGENT:" + key + "]\n";
    }
}
