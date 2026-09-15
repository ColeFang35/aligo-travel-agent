package io.aligo.travel.context;

/**
 * 记忆栈（出入栈维护智能体调用链的层级关系）。
 *
 * <p>对应文章上下文工程-记忆架构设计："通过出入栈的方式维护智能体调用链的层级关系
 * 以及共享 sessionId 来实现模块间的记忆共享"。每进入一个子智能体 push，退出 pop；
 * 栈深度即调用层级（TaskCollector 用它画思考链层级，Tracer 用它串 span 父子）。
 *
 * <p>不是 ThreadLocal：编排器异步回调单线程内按事件序调用，栈为普通对象由
 * OrchestratorService 显式持有并传递，避开反应式上下文问题。
 */
public class MemoryStack {

    private final java.util.Deque<Frame> stack = new java.util.ArrayDeque<>();
    private final String sessionId;

    public MemoryStack(String sessionId) {
        this.sessionId = sessionId;
    }

    public record Frame(String agentKey, String spanKey, int level) {
    }

    public Frame push(String agentKey, String spanKey) {
        Frame f = new Frame(agentKey, spanKey, stack.size());
        stack.push(f);
        return f;
    }

    public Frame pop() {
        return stack.isEmpty() ? null : stack.pop();
    }

    /** 当前层级（思考链缩进 + span 父级的来源）。 */
    public int level() {
        return stack.size();
    }

    /** 当前栈顶 span key（agent 内部 LLM/工具 span 的父级）。 */
    public String currentSpanKey() {
        return stack.peek() == null ? null : stack.peek().spanKey();
    }

    public String sessionId() {
        return sessionId;
    }
}
