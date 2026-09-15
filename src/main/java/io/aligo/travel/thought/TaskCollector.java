package io.aligo.travel.thought;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * TaskCollector：自定义任务状态收集器（对应文章同名组件）。
 *
 * <p>核心职责是统一管理思考链状态信息：
 * <ul>
 *   <li>管理任务完整生命周期（PENDING、DOING、DONE、FAILED）</li>
 *   <li>维护任务间层级关系（level + parentId，来自 MemoryStack 的出入栈）</li>
 *   <li>通过发布-订阅模式实时推送状态更新（SSE 思考链事件源）</li>
 *   <li>管理任务队列与订阅者列表</li>
 * </ul>
 */
public class TaskCollector {

    public enum Status {
        PENDING, DOING, DONE, FAILED
    }

    /** 思考链任务节点（计划节点=agent 阶段；工具节点=一次工具调用）。 */
    public static final class TaskNode {
        public String id = UUID.randomUUID().toString().substring(0, 8);
        public String parentId;
        public int level;
        public String agentKey;
        public String agentName;
        public String label;       // 计划名称 or 工具名
        public String toolInput;
        public Status status = Status.PENDING;
        public String output;
        public long startMs = System.currentTimeMillis();
        public long endMs;
        public double durationMs() {
            return endMs == 0 ? System.currentTimeMillis() - startMs : endMs - startMs;
        }
    }

    private final Map<String, TaskNode> nodes = new ConcurrentHashMap<>();
    private final List<TaskNode> ordered = new CopyOnWriteArrayList<>();
    private final List<Consumer<TaskNode>> subscribers = new CopyOnWriteArrayList<>();

    // ------------------ 文章方法：add_use / add_result / subscribe / unsubscribe

    /** 计划节点：PENDING（如"行程规划智能体"整体任务）。 */
    public TaskNode addPlanned(String agentKey, String agentName, String label, int level, String parentId) {
        TaskNode n = node(agentKey, agentName, label, level, parentId);
        publish(n);
        return n;
    }

    /** add_use()：工具调用任务（PENDING→DOING）。 */
    public TaskNode addUse(String agentKey, String agentName, String toolName, String toolInput,
                           int level, String parentId) {
        TaskNode n = node(agentKey, agentName, "tool:" + toolName, level, parentId);
        n.status = Status.DOING;
        n.toolInput = toolInput;
        publish(n);
        return n;
    }

    public void doing(String nodeId) {
        TaskNode n = nodes.get(nodeId);
        if (n != null && n.status == Status.PENDING) {
            n.status = Status.DOING;
            n.startMs = System.currentTimeMillis();
            publish(n);
        }
    }

    /** add_result()：记录工具执行结果（DOING→DONE/FAILED）。 */
    public void addResult(String nodeId, String output, boolean failed) {
        TaskNode n = nodes.get(nodeId);
        if (n == null) {
            return;
        }
        n.status = failed ? Status.FAILED : Status.DONE;
        n.output = output;
        n.endMs = System.currentTimeMillis();
        publish(n);
    }

    public void subscribe(Consumer<TaskNode> listener) {
        subscribers.add(listener);
    }

    public void unsubscribe(Consumer<TaskNode> listener) {
        subscribers.remove(listener);
    }

    public List<TaskNode> snapshot() {
        return List.copyOf(ordered);
    }

    // ------------------------------------------------------------------

    private TaskNode node(String agentKey, String agentName, String label, int level, String parentId) {
        TaskNode n = new TaskNode();
        n.agentKey = agentKey;
        n.agentName = agentName;
        n.label = label;
        n.level = level;
        n.parentId = parentId;
        nodes.put(n.id, n);
        ordered.add(n);
        return n;
    }

    private void publish(TaskNode n) {
        for (Consumer<TaskNode> c : subscribers) {
            try {
                c.accept(n);
            } catch (Exception ignore) {
                // 订阅者异常不影响主干流程
            }
        }
    }
}
