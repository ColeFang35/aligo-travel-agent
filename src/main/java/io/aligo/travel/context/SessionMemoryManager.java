package io.aligo.travel.context;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 会话记忆管理（三张表，对应文章上下文工程-数据表关系）：
 * <ul>
 *   <li>会话管理表 {@link SessionRow}：会话基本信息与描述</li>
 *   <li>消息存储表 {@link MsgRow}：详细消息记录（本轮流式全过程的完整文本与卡片）</li>
 *   <li>对话记录表 {@link TurnRow}：精简的 user query → final answer 对（上下文共享的原料）</li>
 * </ul>
 *
 * <p>实现为进程内 Map（复现版）；生产换成 Redis/MySQL 只需替换本类的持久化实现，
 * 智能体与输出层无感知（对应文章"容器化、代码化支持灵活部署"）。
 */
@Component
public class SessionMemoryManager {

    public static class SessionRow {
        public String sessionId;
        public String userId;
        public String tenantId;
        public String title;
        public long createdAt = Instant.now().toEpochMilli();
        public long lastActiveAt = createdAt;
        /** 行程槽位状态（动态 Prompt 状态机的输入） */
        public TripState trip = new TripState();
    }

    public static class MsgRow {
        public long ts = Instant.now().toEpochMilli();
        public String role;    // user | assistant
        public String agent;   // 产出该消息的 agent 名
        public String text;
        public List<String> cards = new ArrayList<>();
    }

    public static class TurnRow {
        public long ts = Instant.now().toEpochMilli();
        public String query;
        public String answer;
        public String intent;   // 本轮识别出的意图（慢车道）/ 规则命中（快车道）
        public String agents;
    }

    public static class TripState {
        public String departure;
        public String destination;
        public String date;
        public String budget;
        public String stage = "COLLECT"; // COLLECT | PLAN | BOOK | DONE
    }

    private final Map<String, SessionRow> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<MsgRow>> messages = new ConcurrentHashMap<>();
    private final Map<String, List<TurnRow>> turns = new ConcurrentHashMap<>();

    public SessionRow getOrCreate(String sessionId, String userId, String tenantId) {
        SessionRow row = sessions.computeIfAbsent(sessionId == null || sessionId.isBlank()
                        ? UUID.randomUUID().toString().substring(0, 8) : sessionId,
                id -> {
                    SessionRow r = new SessionRow();
                    r.sessionId = id;
                    r.userId = userId;
                    r.tenantId = tenantId;
                    return r;
                });
        row.lastActiveAt = Instant.now().toEpochMilli();
        return row;
    }

    public SessionRow get(String sessionId) {
        return sessions.get(sessionId);
    }

    public List<SessionRow> listSessions() {
        List<SessionRow> all = new ArrayList<>(sessions.values());
        all.sort((a, b) -> Long.compare(b.lastActiveAt, a.lastActiveAt));
        return all;
    }

    public void appendMsg(String sessionId, String role, String agent, String text) {
        MsgRow m = new MsgRow();
        m.role = role;
        m.agent = agent;
        m.text = text;
        messages.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(m);
    }

    public void appendCard(String sessionId, String cardJson) {
        List<MsgRow> list = messages.get(sessionId);
        if (list != null && !list.isEmpty()) {
            list.get(list.size() - 1).cards.add(cardJson);
        }
    }

    public List<MsgRow> messages(String sessionId) {
        return messages.getOrDefault(sessionId, List.of());
    }

    public void appendTurn(String sessionId, String query, String answer, String intent, String agents) {
        TurnRow t = new TurnRow();
        t.query = query;
        t.answer = answer;
        t.intent = intent;
        t.agents = agents;
        turns.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(t);
    }

    public List<TurnRow> turns(String sessionId, int last) {
        List<TurnRow> all = turns.getOrDefault(sessionId, List.of());
        return all.subList(Math.max(0, all.size() - last), all.size());
    }

    public int count() {
        return sessions.size();
    }
}
