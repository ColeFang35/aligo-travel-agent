// ============================================================================
// AliGo 智能差旅助手 - 前端逻辑
// 对话 + 实时思考链 + 卡片 + 内嵌调试（火焰图/流程图/Prompt/记忆）
// ============================================================================
const $ = (id) => document.getElementById(id);
const chat = $("chat");
let curSession = localStorage.getItem("aligo_session") || "";
let es = null;
let curAgentMsg = null;      // 当前 assistant 消息容器 {agentKey: {msgEl, bodyEl, textEl, thinkEl}}
let taskNodes = new Map();   // taskId -> node
let lastTraceId = null;
let textQueue = [];          // 打字机队列
let typeTimer = null;

// ---------- provider badge ----------
fetch("/api/debug/provider").then(r => r.json()).then(j => {
  const b = $("providerBadge");
  b.textContent = "LLM: " + (j.provider === "mock" ? "剧本模型(mock)" : "通义千问(dashscope)");
  b.className = "badge " + j.provider;
});

// ---------- 快捷演示 ----------
function demo(btn, lane) {
  const text = btn.textContent.replace(/快车道|慢车道多意图|Handoffs|RAG/g, "").trim();
  $("input").value = text;
  send();
}

// ---------- 发送 ----------
$("input").addEventListener("keydown", (e) => { if (e.key === "Enter") send(); });
$("sendBtn").addEventListener("click", send);


// ---------- 加载历史（多轮对话回显）----------
function loadHistory() {
  if (!curSession) return;
  fetch(`/api/debug/memory/${curSession}`).then(r => r.json()).then(m => {
    if (!(m && m.session)) return;
    const msgs = m.messages || [];
    if (!msgs.length) return;
    chat.innerHTML = "";
    const last = { el: null };
    msgs.forEach(x => {
      if (x.role === "user") { addMsg("user", x.text, ""); }
      else if (x.role === "assistant") {
        if (!last.el || last.el.agentKey !== x.agent) {
          const a = addMsg("assistant", "", agentDisplayName(x.agent || "artisan"));
          a.agentKey = x.agent; last.el = a;
        }
        if (x.text) last.el.textEl.textContent += x.text;
        (x.cards || []).forEach(cardJson => {
          try { renderCard(last.el.bodyEl, JSON.parse(cardJson)); }
          catch (e) { var c = document.createElement("div"); c.className="card"; c.textContent=cardJson; last.el.bodyEl.appendChild(c); }
        });
      }
    });
    curAgentMsg = last.el;
    chat.scrollTop = chat.scrollHeight;
  });
}

function send() {
  const q = $("input").value.trim();
  if (!q || es) return;
  $("input").value = "";
  $("sendBtn").disabled = true;
  addMsg("user", q, "");
  resetPanels();
  colsReset();
  const tenant = $("tenant").value;
  const url = `/api/chat/stream?query=${encodeURIComponent(q)}&tenantId=${tenant}`
            + (curSession ? `&sessionId=${curSession}` : "");
  es = new EventSource(url);
  ["session_started","meta","task_update","think","text","card","agent_start","agent_end","warn","error","done"]
    .forEach(t => es.addEventListener(t, onEvent));
}

function onEvent(e) {
  const type = e.type;
  const d = JSON.parse(e.data);
  if (type === "session_started") {
    curSession = d.sessionId;
    localStorage.setItem("aligo_session", curSession);
  } else if (type === "meta") {
    onMeta(d);
  } else if (type === "task_update") {
    taskNodes.set(d.id, d);
    renderThinking();
  } else if (type === "think") {
    agentTarget(d.agent, d.agentName, "think");
    appendThink(d.delta);
  } else if (type === "text") {
    agentTarget(d.agent, d.agentName, "text");
    enqueueType(d.delta);
  } else if (type === "card") {
    agentTarget(d.agent, agentDisplayName(d.agent), "card");
    renderCard(curAgentMsg.bodyEl, d.data);
  } else if (type === "warn") {
    addMsg("assistant", "⚠ " + d.message, "");
  } else if (type === "error") {
    addMsg("assistant", "出错了：" + d.message, "");
    closeStream();
  } else if (type === "done") {
    lastTraceId = d.traceId;
    loadDebugPanels();
    closeStream();
  }
}

function closeStream() {
  if (es) { es.close(); es = null; }
  $("sendBtn").disabled = false;
  flushType();
}

// ---------- 对话气泡 ----------
const AGENT_NAMES = {
  main: "主规划智能体 main_plan_agent",
  intent: "意图识别智能体 intent_recognition_agent",
  itinerary: "行程规划智能体 itinerary_planning_agent (Routing)",
  info: "信息查询智能体 info_query_agent (Handoffs)",
  knowledge: "知识库问答智能体 rag_knowledge_agent (Routing)",
  approval: "出差申请智能体 approval_agent (Routing)"
};
function agentDisplayName(k) { return AGENT_NAMES[k] || k; }

function addMsg(role, text, agentName) {
  const el = document.createElement("div");
  el.className = "msg " + role;
  if (agentName) {
    const who = document.createElement("div");
    who.className = "who" + (agentName.includes("Handoffs") ? " handoff" : "");
    who.textContent = agentName;
    el.appendChild(who);
  }
  const body = document.createElement("div");
  body.className = "body";
  el.appendChild(body);
  const t = document.createElement("div");
  t.className = "ans";
  t.textContent = text;
  body.appendChild(t);
  chat.appendChild(el);
  chat.scrollTop = chat.scrollHeight;
  return { el, bodyEl: body, textEl: t };
}

function agentTarget(agentKey, agentName, kind) {
  if (!curAgentMsg) {
    curAgentMsg = addMsg("assistant", "", agentDisplayName(agentKey));
    curAgentMsg.agentKey = agentKey;
  }
  if (curAgentMsg.agentKey !== agentKey) {
    curAgentMsg = addMsg("assistant", "", agentDisplayName(agentKey));
    curAgentMsg.agentKey = agentKey;
  }
}
function colsReset() { curAgentMsg = null; textQueue = []; }

function appendThink(delta) {
  if (!curAgentMsg.thinkEl) {
    const box = document.createElement("span");
    box.className = "thinkbox";
    box.innerHTML = "💭 思考过程（点击展开）<span class='c'></span>";
    box.onclick = () => box.classList.toggle("open");
    curAgentMsg.bodyEl.insertBefore(box, curAgentMsg.textEl);
    curAgentMsg.thinkEl = box.querySelector(".c");
  }
  curAgentMsg.thinkEl.textContent += delta;
}

// 打字机：mock 单 chunk 也有流式观感
function enqueueType(delta) {
  textQueue.push(delta);
  if (!typeTimer) {
    typeTimer = setInterval(() => {
      let chunk = textQueue.shift();
      if (chunk != null && curAgentMsg) {
        curAgentMsg.textEl.textContent += chunk.length > 8 ? chunk.slice(0, 8) : chunk;
        if (chunk.length > 8) textQueue.unshift(chunk.slice(8));
        chat.scrollTop = chat.scrollHeight;
      }
      if (textQueue.length === 0) { clearInterval(typeTimer); typeTimer = null; }
    }, 24);
  }
}
function flushType() {
  if (typeTimer) { clearInterval(typeTimer); typeTimer = null; }
  while (textQueue.length) { if (curAgentMsg) curAgentMsg.textEl.textContent += textQueue.shift(); }
}

// ---------- 卡片渲染 ----------
function renderCard(parent, data) {
  const type = data._card;
  const el = document.createElement("div");
  el.className = "card";
  if (type === "flight") {
    el.innerHTML = `<div class="t">✈ 机票候选 · ${data.route} · ${data.date}</div><table>
      <tr><th>航班</th><th>起飞</th><th>到达</th><th>舱位</th><th>价格</th></tr>` +
      data.flights.map(f => `<tr><td>${f.flightNo}</td><td>${f.dep}</td><td>${f.arr}</td>
        <td>${f.cabin}</td><td class="price">¥${f.price}</td></tr>`).join("") + `</table>`;
  } else if (type === "hotel") {
    el.innerHTML = `<div class="t">🏨 酒店候选 · ${data.city} · 入住 ${data.checkInDate}（${data.nights} 晚）</div><table>
      <tr><th>酒店</th><th>星级</th><th>价格/晚</th><th>差标内</th></tr>` +
      data.hotels.map(h => `<tr><td>${h.name}</td><td>${"★".repeat(h.stars)}</td>
        <td class="price">¥${h.price}</td><td>${h.withinPolicy ? "<span class='ok'>✓</span>" : "<span class='no'>✗</span>"}</td></tr>`).join("") + `</table>`;
  } else if (type === "weather") {
    el.innerHTML = `<div class="t">🌤 目的地天气 · ${data.city}</div>
      <div class="kv"><div><b>${data.condition}</b></div><div>温度 <b>${data.low}℃ ~ ${data.high}℃</b></div><div>${data.tip}</div></div>`;
  } else if (type === "time") {
    el.innerHTML = `<div class="t">🕘 时间解析</div>
      <div class="kv"><div>原文 <b>${data.expression}</b></div><div>日期 <b>${data.parsedDate}</b>（${data.weekday}）${data.slot}</div></div>`;
  } else if (type === "itinerary") {
    el.innerHTML = `<div class="t">🧳 行程卡片 · ${data.departure} → ${data.destination} · ${data.date}</div>
      <div class="kv"><div>航班 <b>${data.flightNo}</b> <span class="price">¥${data.flightPrice}</span></div>
      <div>酒店 <b>${data.hotelName}</b> <span class="price">¥${data.hotelPrice}</span></div>
      <div>预估总计 <b class="price">¥${data.totalEstimate}</b></div></div>
      <button class="order" onclick="this.textContent='已下单（演示）';this.disabled=true">一键下单</button>`;
  } else if (type === "approval_list") {
    var rows = data.records ? data.records.map(function(r){return "<tr><td>"+r.applyId+"</td><td>"+r.destination+"</td><td>"+r.date+"</td><td>"+r.budget+"</td><td class=\"ok\">"+r.status+"</td></tr>";}).join("") : "";
    el.innerHTML = "<div class=\"t\">📂 出差申请记录 · 共 " + data.count + " 条</div>" + (rows ? "<table><tr><th>单号</th><th>目的地</th><th>日期</th><th>预算</th><th>状态</th></tr>" + rows + "</table>" : "<div class=\"kv\"><div>暂无申请记录</div></div>");
  } else if (type === "approval") {
    el.innerHTML = `<div class="t">📋 出差申请单</div><table>
      <tr><td>申请单号</td><td>${data.applyId}</td></tr>
      <tr><td>目的地</td><td>${data.destination}</td></tr>
      <tr><td>日期</td><td>${data.date}</td></tr>
      <tr><td>预算</td><td>${data.budget}</td></tr>
      <tr><td>事由</td><td>${data.reason}</td></tr>
      <tr><td>状态</td><td class="ok">${data.status}</td></tr>
      <tr><td>SLA</td><td>${data.sla}</td></tr></table>`;
  } else if (type === "kb") {
    el.innerHTML = `<div class="t">📚 知识库命中 · 租户 ${data.tenant}</div>` +
      data.hits.map(h => `<div style="font-size:12.5px;line-height:1.6;margin:4px 0">${h.idx}. ${escapeHtml(h.text)}</div>`).join("") +
      `<div class="src">来源：内置差旅知识库（模拟 MaxKB 标准化 API）</div>`;
  } else {
    el.textContent = JSON.stringify(data);
  }
  parent.appendChild(el);
  chat.scrollTop = chat.scrollHeight;
}
function escapeHtml(s) { const d = document.createElement("div"); d.textContent = s; return d.innerHTML; }

// ---------- 思考链渲染 ----------
function renderThinking() {
  const box = $("thinking");
  $("thinkHint").style.display = taskNodes.size ? "none" : "block";
  box.innerHTML = "";
  [...taskNodes.values()].forEach(n => {
    const el = document.createElement("div");
    el.className = "tnode " + n.status;
    el.style.setProperty("--lv", n.level);
    const icon = n.status === "PENDING" ? "⏸" : n.status === "DOING" ? "▶" : n.status === "DONE" ? "✓" : "✗";
    el.innerHTML = `<div class="head"><span class="status">${icon} ${n.status}</span>
      <span class="name">${n.agentName}</span><span class="lbl">${escapeHtml(n.label)}</span>
      <span class="time">${n.endMs ? Math.round(n.endMs - n.startMs) + "ms" : "…"}</span></div>`;
    if (n.toolInput || n.output) {
      const io = document.createElement("div");
      io.className = "io";
      let txt = "";
      if (n.toolInput) txt += "▸ 输入 " + n.toolInput + "\n";
      if (n.output) txt += "▸ 输出 " + n.output.slice(0, 400);
      io.textContent = txt;
      el.appendChild(io);
    }
    box.appendChild(el);
  });
}

// 让 DOING 节点的耗时动起来
setInterval(() => {
  document.querySelectorAll(".tnode.DOING .time").forEach(el => {
    el.textContent = "…";
  });
}, 300);

// ---------- 右侧 tab 切换 ----------
function switchTab(tabName) {
  document.querySelectorAll(".right .tabs button").forEach(x => x.classList.toggle("active", x.dataset.tab === tabName));
  ["think","trace","flame","flow","prompt","memory"].forEach(t => {
    $("panel-" + t).style.display = (tabName === t) ? "block" : "none";
  });
  if (tabName === "memory") loadMemory();
}
document.querySelectorAll(".right .tabs button").forEach(b => {
  b.addEventListener("click", () => switchTab(b.dataset.tab));
});

// ---------- Trace / 火焰图 / 流程图 / Prompt / 记忆 ----------
function onMeta(d) {
  const lane = d.lane === "fast"
    ? "<span class='lane-fast'>快车道 · 规则引擎直接路由</span>"
    : "<span class='lane-slow'>慢车道 · 意图识别智能体（显式推理两段式）</span>";
  $("traceMeta").innerHTML = `<b>意图识别与调度决策</b><br>${lane}<br>
    意图：<code>${d.intents.join(" / ")}</code>　置信度：${d.confidence}<br>
    调度：${d.targets.map(t => `<code>${t.agentName}</code>(${t.mode})`).join("，")}<br>
    改写 query：<code>${escapeHtml(d.rewrittenQuery || "")}</code><br>
    推理过程：${escapeHtml(d.reasoning || "")}`;
}

function resetPanels() {
  taskNodes = new Map();
  renderThinking();
  $("thinking").innerHTML = "";
  $("traceDetail").innerHTML = "";
  $("promptList").innerHTML = "";
  $("memView").innerHTML = "";
  $("flameTip").style.display = "none";
  $("flowSrc").textContent = "";
  $("flowSvg").innerHTML = "";
}

function loadDebugPanels() { if (lastTraceId) loadTrace(lastTraceId); }
function loadTrace(tid) {
  // Trace 详情
  fetch(`/api/debug/trace/${tid}`).then(r => r.json()).then(t => {
    const spans = t.spans || [];
    let html = `<div class="meta"><b>Trace ${t.traceId}</b><br>
      lane=${t.lane}　总耗时=${Math.round(t.durationMs)}ms　span 数=${spans.length}<br>
      Token：输入 ${t.totalInputTokens} / 输出 ${t.totalOutputTokens}</div>`;
    spans.sort((a,b) => a.startMs - b.startMs);
    html += spans.map(s => `<div class="tnode ${s.status === "OK" ? "DONE" : "FAILED"}" style="--lv:${s.type === "TOOL" ? 2 : s.type === "LLM" ? 2 : s.type === "AGENT" ? 1 : 0}">
      <div class="head"><span class="status">[${s.type}]</span><span class="name">${escapeHtml(s.name)}</span>
      <span class="time">${s.startMs | 0}ms → ${s.endMs | 0}ms（${s.durationMs.toFixed(0)}ms）${s.inputTokens ? `　tok ${s.inputTokens}/${s.outputTokens}` : ""}</span></div>
      ${(s.input || s.output) ? `<div class="io">${s.input ? "in: " + escapeHtml(s.input.slice(0,150)) + "\n" : ""}${s.output ? "out: " + escapeHtml(s.output.slice(0,150)) : ""}</div>` : ""}
      </div>`).join("");
    $("traceDetail").innerHTML = html;
    drawFlame(t);
  });
  // 流程图
  fetch(`/api/debug/trace/${tid}/flow`).then(r => r.json()).then(j => {
    $("flowSrc").textContent = j.mermaid;
    try {
      if (window.mermaid) {
        mermaid.render("flowG", j.mermaid).then(r2 => { $("flowSvg").innerHTML = r2.svg; });
      }
    } catch (e) { /* 保留源码显示 */ }
  });
  // Prompt 快照
  fetch(`/api/debug/prompts/${tid}`).then(r => r.json()).then(arr => {
    $("promptList").innerHTML = (arr || []).map(p =>
      `<div class="tnode DONE" style="--lv:0"><div class="head"><span class="name">${escapeHtml(p.agentKey)}</span>
       <span class="lbl">stage=${p.stage} · ${new Date(p.ts).toLocaleTimeString()}</span></div>
       <div class="io" style="max-height:220px">${escapeHtml(p.prompt)}</div></div>`).join("") || "<div class='empty'>无快照</div>";
  });
}

function loadMemory() {
  if (!curSession) {
    fetch("/api/debug/sessions").then(r => r.json()).then(arr => {
      if (arr && arr.length && arr[0].sessionId) { curSession = arr[0].sessionId; loadMemory(); }
      else $("memView").innerHTML = "<div class='empty'>尚无会话，请先发送一条消息</div>"; return;
    });
    return;
  }
  fetch(`/api/debug/memory/${curSession}`).then(r => r.json()).then(m => {
    let html = "";
    if (m.session) {
      const tr = m.session.trip;
      html += `<div class="meta"><b>会话管理表</b><br>sessionId=${m.session.sessionId}　userId=${m.session.userId}　tenant=${m.session.tenantId}<br>
        阶段状态机：<b>${tr.stage}</b>　槽位：出发=${tr.departure || "-"} 到达=${tr.destination || "-"} 日期=${tr.date || "-"} 预算=${tr.budget || "-"}</div>`;
    }
    html += `<div class="tnode DONE" style="--lv:0"><div class="head"><span class="name">对话记录表（精简 QA 对）</span></div>
      <div class="io" style="max-height:160px">${(m.turns || []).map(t => `[${t.intent}] ${escapeHtml(t.query)} ⇒ ${escapeHtml((t.answer||"").slice(0,120))}`).join("\n") || "空"}</div></div>`;
    html += `<div class="tnode DONE" style="--lv:0"><div class="head"><span class="name">消息存储表（详细记录，含卡片）</span></div>
      <div class="io" style="max-height:200px">${(m.messages || []).map(x => `${x.role}(${x.agent}): ${escapeHtml((x.text||"").slice(0,120))}${x.cards.length ? " +卡片x" + x.cards.length : ""}`).join("\n") || "空"}</div></div>`;
    $("memView").innerHTML = html;
  });
}

// ---------- 迷你火焰图 ----------
const FLAME_COLORS = { AGENT: "#4f9cf9", LLM: "#a06ee0", TOOL: "#3fc785", ROUTER: "#f9b44f", INTENT: "#f97070", SESSION: "#666" };
function drawFlame(t) {
  const cv = $("flame");
  const ctx = cv.getContext("2d");
  const W = cv.width = cv.clientWidth * 2, H = cv.height = 400;
  ctx.clearRect(0, 0, W, H);
  const total = Math.max(t.durationMs, 1);
  const spans = t.spans.slice().sort((a, b) => a.startMs - b.startMs);
  // 层级：parent 关系推导 depth
  const depthOf = {};
  spans.forEach(s => {
    let d = s.type === "ROUTER" ? 0 : s.type === "AGENT" ? 1 : s.type === "TOOL" || s.type === "LLM" ? 2 : 1;
    s._d = d;
  });
  const rowH = 22, pad = 6;
  cv.__spans = spans;
  spans.forEach((s, i) => {
    const x = (s.startMs / total) * (W - 20) + 10;
    const w = Math.max((s.durationMs / total) * (W - 20), 3);
    const y = 20 + (s._d * (rowH + pad)) + (spans.filter(o => o._d === s._d && o.startMs < s.startMs).length % 3) * 0; // 同行只按深度排
    ctx.fillStyle = FLAME_COLORS[s.type] || "#999";
    ctx.fillRect(x, y, w, rowH);
    ctx.fillStyle = "#0e0f13";
    ctx.font = "11px sans-serif";
    const label = `${s.name} (${s.durationMs.toFixed(0)}ms)`;
    if (ctx.measureText(label).width < w - 6) {
      ctx.save(); ctx.translate(x + 4, y + 15); ctx.fillText(label, 0, 0); ctx.restore();
    }
    s._x = x; s._w = w; s._y = y;
  });
  cv.onclick = (ev) => {
    const r = cv.getBoundingClientRect();
    const mx = (ev.clientX - r.left) * 2, my = (ev.clientY - r.top) * 2;
    const hit = spans.find(s => mx >= s._x && mx <= s._x + s._w && my >= s._y && my <= s._y + rowH);
    if (hit) {
      const tip = $("flameTip");
      tip.style.display = "block";
      tip.innerHTML = `<b>[${hit.type}] ${escapeHtml(hit.name)}</b>　${hit.durationMs.toFixed(1)}ms<br>
        ${hit.input ? "in: " + escapeHtml(hit.input.slice(0,300)) + "<br>" : ""}
        ${hit.output ? "out: " + escapeHtml(hit.output.slice(0,300)) : ""}
        ${hit.inputTokens ? "<br>token: " + hit.inputTokens + "/" + hit.outputTokens : ""}`;
    }
  };
}

// ---------- 演示直链：?q=... 打开页面自动发送（用于演示/截图） ----------
const params = new URLSearchParams(location.search);
const autoQ = params.get("q");
const autoTab = params.get("tab");
const autoTrace = params.get("trace");
window.addEventListener("load", () => { loadHistory();
  if (autoTrace) { lastTraceId = autoTrace; loadTrace(autoTrace); }
  if (autoTab) switchTab(autoTab);
  if (autoQ) { $("input").value = autoQ; setTimeout(send, 300); }
});
