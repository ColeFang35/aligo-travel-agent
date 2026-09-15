const $ = (id) => document.getElementById(id);
const FLAME_COLORS = { AGENT: "#4f9cf9", LLM: "#a06ee0", TOOL: "#3fc785", ROUTER: "#f9b44f", INTENT: "#f97070", SESSION: "#666" };
function esc(s) { const d = document.createElement("div"); d.textContent = s == null ? "" : s; return d.innerHTML; }

// ---------- Trace 列表 ----------
let opened = null;
function loadList() {
  fetch("/api/debug/traces").then(r => r.json()).then(arr => {
    $("traceList").innerHTML = arr.length ? arr.map(t => `
      <div class="trace-item ${opened === t.traceId ? 'sel' : ''}" onclick="openTrace('${t.traceId}','${t.sessionId}')">
        <div class="q">${esc(t.query.slice(0, 40))}</div>
        <div class="m">
          <span class="lb ${t.lane}">${t.lane === "fast" ? "快车道" : t.lane === "slow" ? "慢车道" : "-"}</span>
          ${t.traceId} · ${Math.round(t.durationMs)}ms · span=${t.spans} · tok=${t.inputTokens}/${t.outputTokens}
        </div>
      </div>`).join("") : "<div class='empty'>暂无 Trace —— 去对话页发一条消息</div>";
  });
}
setInterval(loadList, 4000);

function openTrace(id, sessionId) {
  opened = id;
  fetch(`/api/debug/trace/${id}`).then(r => r.json()).then(t => {
    // 摘要
    const dec = t.decision ? JSON.parse(t.decision) : null;
    $("traceSummary").innerHTML = `<b>${esc(t.query)}</b><br>
      lane=<b>${t.lane}</b>　耗时=${Math.round(t.durationMs)}ms　span=${t.spans.length}　
      Token=${t.totalInputTokens}/${t.totalOutputTokens}<br>
      session=${t.sessionId}　user=${t.userId}　tenant=${t.tenantId}<br>
      ${dec ? `意图决策：<span style="color:#a06ee0">${esc(JSON.stringify(dec.intents))} → ${esc(JSON.stringify(dec.targets))}</span>　置信度=${dec.confidence}<br>
      推理过程：<span class="sub">${esc(dec.reasoning)}</span><br>改写：<span class="sub">${esc(dec.rewritten_query)}</span>` : "<span class='sub'>（快车道：规则引擎决策，无 LLM 意图 JSON）</span>"}`;
    drawFlame(t);
    drawTokens(t);
    drawLlms(t);
  });
  fetch(`/api/debug/trace/${id}/flow`).then(r => r.json()).then(j => {
    $("flowSrc").textContent = j.mermaid;
    $("flowSrc").style.display = "block";
    try {
      if (window.mermaid) mermaid.render("flowG", j.mermaid).then(r2 => { $("flowSvg").innerHTML = r2.svg; });
    } catch (e) {}
  });
  fetch(`/api/debug/prompts/${id}`).then(r => r.json()).then(arr => {
    $("prompts").innerHTML = (arr || []).map(p => `
      <div class="tnode DONE" style="--lv:0;margin-bottom:6px">
        <div class="head"><span class="name">${esc(p.agentKey)}</span><span class="lbl">stage=${p.stage}</span></div>
        <div class="io" style="max-height:200px">${esc(p.prompt)}</div></div>`).join("") || "<div class='empty'>无快照</div>";
  });
  if (sessionId) loadMem(sessionId);
}

function drawTokens(t) {
  const byType = {};
  t.spans.forEach(s => {
    const k = s.type;
    byType[k] = byType[k] || { inp: 0, out: 0 };
    byType[k].inp += s.inputTokens || 0;
    byType[k].out += s.outputTokens || 0;
  });
  const total = Math.max(1, Object.values(byType).reduce((a, b) => a + b.inp + b.out, 0));
  $("tokens").innerHTML = Object.entries(byType).map(([k, v]) => {
    const p = Math.round((v.inp + v.out) / total * 100);
    return `<div class="bar"><span class="name">${k}</span>
      <span class="v" style="width:${p * 2.2}px;background:${FLAME_COLORS[k]}"></span>
      <span>${v.inp} / ${v.out}（${p}%）</span></div>`;
  }).join("") + `<div class="sub">总计：输入 ${t.totalInputTokens} · 输出 ${t.totalOutputTokens}（mock 为长度估算）</div>`;
}

function drawLlms(t) {
  const llms = t.spans.filter(s => s.type === "LLM").sort((a, b) => a.startMs - b.startMs);
  $("llmRows").innerHTML = llms.length ? llms.map((s, i) => `
    <div class="eval-case">#${i + 1} <b>${s.inputTokens}</b> in / <b>${s.outputTokens}</b> out
      ・${(s.llmTimeSec || 0).toFixed(2)}s ・${s.durationMs.toFixed(0)}ms</div>`).join("")
    : "<div class='empty'>本轮无 LLM 调用（纯快车道：规则引擎直接路由）</div>";
}

function loadMem(sessionId) {
  fetch(`/api/debug/memory/${sessionId}`).then(r => r.json()).then(m => {
    const tr = m.session ? m.session.trip : null;
    let html = tr ? `<div class="meta">阶段状态机：<b>${tr.stage}</b>　槽位：出发=${tr.departure || "-"} 到达=${tr.destination || "-"} 日期=${tr.date || "-"} 预算=${tr.budget || "-"}</div>` : "";
    html += `<div class="sub" style="white-space:pre-wrap;max-height:300px;overflow:auto">` +
      `【对话记录表】\n${(m.turns || []).map(t => `[${t.intent}] ${t.query} ⇒ ${(t.answer || "").slice(0, 80)}`).join("\n") || "空"}\n\n` +
      `【消息存储表】\n${(m.messages || []).map(x => `${x.role}(${x.agent}): ${(x.text || "").slice(0, 80)}${x.cards.length ? " +卡片x" + x.cards.length : ""}`).join("\n") || "空"}` + `</div>`;
    $("mem").innerHTML = html;
  });
}

// ---------- 评测 ----------
fetch("/api/debug/eval/intent").then(r => r.json()).then(j => {
  const pct = (x) => (x * 100).toFixed(0) + "%";
  $("eval").innerHTML =
    `<div class="bar"><span class="name">v1 单智能体（对照）</span><span class="v" style="width:${j.v1Accuracy * 300}px;background:#f97070"></span><span>${pct(j.v1Accuracy)}</span></div>
     <div class="bar"><span class="name">v2 快慢车道架构</span><span class="v" style="width:${j.v2Accuracy * 300}px;background:#3fc785"></span><span>${pct(j.v2Accuracy)}</span></div>
     <div class="sub">${j.note}</div>` +
    j.cases.map(c => `<div class="eval-case"><span class="${c.v2pass ? 'pass' : 'fail'}">${c.v2pass ? '✓' : '✗'}</span>
      ${esc(c.query)}<span class="sub">（期望 ${c.expect}，路由：${esc(c.route)}）</span></div>`).join("");
});

// ---------- 火焰图（深度布局版） ----------
function drawFlame(t) {
  const cv = $("flame");
  const ctx = cv.getContext("2d");
  const W = cv.width = cv.clientWidth * 2, H = cv.height = 360;
  ctx.clearRect(0, 0, W, H);
  const total = Math.max(t.durationMs, 1);
  const spans = t.spans.slice().sort((a, b) => a.startMs - b.startMs);
  // 计算每个 span 的深度（parentId → 父 span 的 id）
  const byId = {};
  spans.forEach(s => byId[s.id] = s);
  spans.forEach(s => {
    let d = 0, p = s.parentId;
    while (p && byId[p]) { d++; p = byId[p].parentId; }
    if (s.type === "ROUTER") d = 0;
    s._d = d;
  });
  // 同行避让：按深度分组，同一深度若时间重叠则下移
  const rowH = 26, pad = 4;
  const rows = [];
  spans.forEach(s => {
    let row = s._d;
    while (true) {
      const placed = rows[row] || (rows[row] = []);
      const clash = placed.some(o => !(s.endMs <= o.startMs || s.startMs >= o.endMs));
      if (!clash) { placed.push(s); s._row = row; break; }
      row++;
    }
  });
  spans.forEach(s => {
    const x = (s.startMs / total) * (W - 24) + 12;
    const w = Math.max((s.durationMs / total) * (W - 24), 3);
    const y = 16 + s._row * (rowH + pad);
    ctx.fillStyle = FLAME_COLORS[s.type] || "#999";
    ctx.fillRect(x, y, w, rowH);
    ctx.strokeStyle = "rgba(255,255,255,.25)";
    ctx.strokeRect(x, y, w, rowH);
    ctx.fillStyle = "#fff";
    ctx.font = "12px sans-serif";
    const label = `${s.name} ${s.durationMs.toFixed(0)}ms`;
    if (ctx.measureText(label).width < w - 8) ctx.fillText(label, x + 5, y + 17);
    s._x = x; s._w = w; s._y = y;
  });
  // 图例
  let lx = 12;
  Object.entries(FLAME_COLORS).forEach(([k, c]) => {
    if (k === "SESSION") return;
    ctx.fillStyle = c; ctx.fillRect(lx, H - 22, 12, 12);
    ctx.fillStyle = "#cfd5e6"; ctx.fillText(k, lx + 16, H - 12); lx += 76;
  });
  cv.onclick = (ev) => {
    const r = cv.getBoundingClientRect();
    const mx = (ev.clientX - r.left) * 2, my = (ev.clientY - r.top) * 2;
    const hit = spans.find(s => mx >= s._x && mx <= s._x + s._w && my >= s._y && my <= s._y + rowH);
    if (hit) {
      const tip = $("flameTip");
      tip.style.display = "block";
      tip.innerHTML = `<b>[${hit.type}] ${esc(hit.name)}</b>　${hit.durationMs.toFixed(1)}ms<br>
        ${hit.input ? "in: " + esc(hit.input.slice(0, 400)) + "<br>" : ""}
        ${hit.output ? "out: " + esc(hit.output.slice(0, 400)) : ""}
        ${hit.inputTokens ? "<br>token: " + hit.inputTokens + "/" + hit.outputTokens + " ・llm " + (hit.llmTimeSec || 0).toFixed(2) + "s" : ""}`;
    }
  };
}

loadList();
// 地址栏锚点支持：debug.html#traceId
if (location.hash) openTrace(location.hash.slice(1), null);
