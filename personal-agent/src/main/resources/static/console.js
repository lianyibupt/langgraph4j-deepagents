"use strict";

const byId = id => document.getElementById(id);
const presets = byId("preset");
const providers = byId("provider");
const question = byId("question");
const maxTurns = byId("max-turns");
const providerNames = { replay: "协议回放", scripted: "固定响应", deepseek: "DeepSeek 实时" };
const statuses = { RUNNING: "运行中", PAUSED: "已暂停", COMPLETED: "已完成", FAILED: "失败", TURN_LIMIT: "已达轮次上限", RECOVERY_REQUIRED: "待核对", WAITING_INPUT: "等待回答", CANCELLED: "已停止", QUEUED: "等待槽位" };
const eventTypes = {
  "model.request": ["请求模型", "model", "flow"],
  "model.response": ["收到模型响应", "model", "flow"],
  "tool.start": ["执行工具", "tool", "terminal"],
  "tool.result": ["回填工具结果", "tool", "terminal"],
  "run.stop": ["Agent 停止", "done", "check"],
  "deepseek.request": ["发送协议请求", "model", "arrow"],
  "deepseek.response": ["收到协议响应", "model", "arrow"],
  "source.report": ["校验报告引用", "done", "check"],
  "plan.status": ["更新任务计划", "tool", "file"],
  "context.prepare": ["准备模型上下文", "model", "flow"],
  "artifact.saved": ["归档工具结果", "tool", "file"],
  "context.restored": ["恢复计划与产物", "tool", "file"],
  "checkpoint.input": ["绑定输入版本", "tool", "file"],
  "source.evidence": ["恢复引用证据", "tool", "file"],
  "knowledge.search": ["检索近期个人资料", "tool", "file"],
  "knowledge.selected": ["确认本次上下文来源", "tool", "check"],
  "knowledge.snapshot": ["固定选中资料版本", "done", "file"],
  "inbox.received": ["接收补充要求", "tool", "file"],
  "inbox.applied": ["应用补充要求", "done", "check"],
  "inbox.question": ["等待你的回答", "tool", "file"],
  "control.stop": ["请求停止任务", "tool", "file"],
  "control.pause": ["请求暂停任务", "tool", "file"],
  "parent-checkpoint": ["父 Agent 检查点", "tool", "file"],
  "delegate.start": ["委派给子 Agent", "tool", "arrow"],
  "delegate.finish": ["子 Agent 返回结果", "done", "check"]
};
const fullTime = new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
const eventTime = new Intl.DateTimeFormat("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", fractionalSecondDigits: 3, hour12: false });

let config;
let runs = [];
let activeDetail = null;
let selectedId = null;
let renderedId = null;
let renderedStatus = null;
let historyPage = false;
let currentPage = "console";
let renderedContent = "";
let renderedOmitted = 0;
let connected = false;
let pending = false;
let selectionVersion = 0;
let refreshing = false;
let refreshTimer;

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  use.setAttribute("href", `#icon-${name}`);
  svg.append(use);
  return svg;
}

async function api(path, body) {
  let response;
  try {
    response = await fetch(`/api/${path}`, body === undefined ? { cache: "no-store" } : {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body)
    });
  } catch {
    throw new Error("无法连接本地服务，请确认 Web 服务仍在运行。");
  }
  const raw = await response.text();
  let data;
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = null; }
  if (!response.ok || data === null) throw new Error(data?.message || `请求失败（HTTP ${response.status}）`);
  return data;
}

function notice(text = "") {
  byId("notice").textContent = text;
  byId("notice").hidden = !text;
}

function setConnection(ready) {
  connected = ready;
  byId("connection").dataset.state = ready ? "ready" : "down";
  byId("connection-label").textContent = ready ? "本地服务已连接" : "本地服务未连接";
  updateButtons();
}

function presetFor(id) { return config?.presets.find(preset => preset.id === id); }

function renderConfig() {
  presets.replaceChildren(...config.presets.filter(preset => preset.id !== "knowledge").map(preset => {
    const option = element("option", preset.name);
    option.value = preset.id;
    return option;
  }));
  renderModelConfig();
  for (const control of [presets, providers, question, maxTurns, byId("reset-question")]) control.disabled = false;
  applyPreset();
}

function renderModelConfig() {
  byId("config-key").textContent = config.deepSeekConfigured ? "已配置 · 连通性待实际运行验证" : "待配置";
  byId("config-model").textContent = config.model;
  providers.querySelector('[value="deepseek"]').disabled = !config.deepSeekConfigured;
  if (providers.selectedOptions[0].disabled) providers.value = "replay";
  updateProvider();
  if (typeof knowledgeButtons === "function") knowledgeButtons();
}

function applyPreset() {
  const preset = presetFor(presets.value);
  if (!preset) return;
  providers.querySelector('[value="scripted"]').disabled = preset.id !== "loop";
  if (providers.selectedOptions[0].disabled) providers.value = "replay";
  byId("preset-description").textContent = preset.description;
  byId("scope-path").textContent = preset.input;
  byId("pause-option").hidden = preset.id === "loop";
  byId("pause-label").textContent = ({ source: "读取证据后暂停", notes: "读取证据后暂停", long: "归档资料后暂停", delegate: "委派完成后暂停" })[preset.id] || "保存检查点后暂停";
  byId("pause").checked = false;
  byId("research-options").hidden = !["source", "notes"].includes(preset.id);
  byId("input-path").value = "";
  byId("ask-demo").checked = false;
  question.value = preset.question;
  maxTurns.value = preset.maxTurns;
  updateProvider();
}

function updateProvider() {
  const help = {
    replay: "回放模型协议并真实执行工具，无需联网。",
    scripted: "用固定响应验证 Agent 循环，无需联网。",
    deepseek: `使用 ${config?.model || "DeepSeek"}，任务和读取的资料可能发送给模型。`
  };
  byId("provider-description").textContent = help[providers.value];
}

function updateButtons() {
  const running = runs.filter(run => run.status === "RUNNING").length >= (config?.concurrency || 2);
  byId("start").disabled = !connected || !config || running || pending;
  byId("start-label").textContent = pending ? "正在提交…" : running ? "任务运行中…" : "运行任务";
  byId("resume").disabled = !connected || running || pending;
}

function showPage() {
  currentPage = ["history", "automation", "notifications", "goals", "knowledge"].includes(location.hash.slice(1)) ? location.hash.slice(1) : "console";
  historyPage = currentPage === "history";
  for (const link of document.querySelectorAll("[data-page]")) {
    if (link.dataset.page === currentPage) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  for (const id of ["stats", "workbench", "all-history", "console-heading"]) byId(id).hidden = currentPage !== "console";
  byId("history-heading").hidden = !historyPage;
  byId("breadcrumb").textContent = ({ console: "运行控制台", history: "运行历史", automation: "主动服务", notifications: "通知中心", goals: "长期目标", knowledge: "个人知识库" })[currentPage];
  byId("history-panel").hidden = !["console", "history"].includes(currentPage);
  byId("active-board").hidden = currentPage !== "console";
  for (const page of ["automation", "notifications", "goals", "knowledge"]) byId(`${page}-page`).hidden = currentPage !== page;
  if (currentPage === "knowledge" && typeof loadKnowledge === "function") loadKnowledge();
  byId("history-panel-title").textContent = historyPage ? "全部记录" : "最近运行";
  renderHistory();
}

function formatDuration(run) {
  if (!run?.finishedAt) return "—";
  const milliseconds = run.durationMillis;
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(1)} s`;
}

function statusBadge(run, target = element("span", undefined, "status")) {
  const status = run?.status;
  if (status) target.dataset.state = status;
  else delete target.dataset.state;
  target.replaceChildren(element("span", undefined, "dot"), document.createTextNode(statuses[status] || "待运行"));
  return target;
}

function renderHistory() {
  byId("history-description").textContent = config
    ? `查看本机持久保存的最近 ${config.historyLimit} 条运行记录、结果与执行轨迹。`
    : "查看本机保存的运行记录、结果与执行轨迹。";
  byId("stat-total").textContent = config ? runs.length : "—";
  byId("stat-complete").textContent = config ? runs.filter(run => run.status === "COMPLETED").length : "—";
  byId("stat-duration").textContent = formatDuration(runs.find(run => run.finishedAt));
  if (typeof renderActiveRuns === "function") renderActiveRuns();
  const tbody = byId("history-rows");
  const visible = historyPage ? runs : runs.slice(0, 5);
  if (!visible.length) {
    const text = connected ? "还没有运行记录。运行一个任务后，记录会出现在这里。" : "等待本地服务提供运行记录。";
    if (tbody.firstElementChild?.dataset.key === text) return;
    const row = element("tr");
    row.dataset.key = text;
    const cell = element("td", text, "history-empty");
    cell.colSpan = 6;
    row.append(cell);
    tbody.replaceChildren(row);
    return;
  }
  const existing = new Map([...tbody.children].map(row => [row.dataset.key, row]));
  const rows = visible.map(run => {
    const key = `${run.id}:${run.status}:${run.finishedAt}`;
    if (existing.has(key)) return existing.get(key);
    const row = element("tr");
    row.dataset.key = key;
    const button = element("button", "查看", "text-button");
    button.type = "button";
    button.setAttribute("aria-label", `查看${presetFor(run.preset)?.name || "任务"}运行 ${run.id.slice(0, 8)}`);
    button.append(icon("chevron"));
    button.addEventListener("click", () => selectRun(run.id));
    const statusCell = element("td");
    statusCell.append(statusBadge(run));
    const actionCell = element("td");
    actionCell.append(button);
    row.append(element("td", presetFor(run.preset)?.name || run.preset), element("td", providerNames[run.provider]), statusCell,
      element("td", fullTime.format(new Date(run.startedAt)), "history-time"), element("td", formatDuration(run), "history-time mono"), actionCell);
    return row;
  });
  if (rows.length !== tbody.children.length || rows.some((row, index) => row !== tbody.children[index])) tbody.replaceChildren(...rows);
}

function setTab(tab, focus = false) {
  for (const button of document.querySelectorAll("[data-tab]")) {
    const current = button.dataset.tab === tab;
    button.setAttribute("aria-selected", String(current));
    button.tabIndex = current ? 0 : -1;
    byId(button.dataset.tab).hidden = !current;
    if (current && focus) button.focus();
  }
}

function eventRow(event) {
  const child = event.text.startsWith("child.reader ");
  const key = event.text.split(" ")[child ? 1 : 0];
  const [label, tone, glyph] = eventTypes[key] || [key, "", "flow"];
  const marker = element("span", undefined, `event-icon ${tone}`);
  marker.append(icon(glyph));
  const main = element("div", undefined, "event-main");
  main.append(element("strong", child ? `子 Agent · ${label}` : label), element("code", event.text));
  const time = element("time", eventTime.format(new Date(event.at)), "event-time");
  time.dateTime = event.at;
  const row = element("li");
  row.append(marker, main, time);
  return row;
}

function renderOutput(detail) {
  const resultContent = byId("result-content");
  resultContent.replaceChildren();
  if (detail?.answer) {
    if (detail.note) {
      const note = element("div", undefined, "result-note");
      const download = element("a", "下载 Markdown", "button small");
      download.href = `/api/runs/${detail.run.id}/note`;
      download.prepend(icon("download"));
      note.append(download, element("code", detail.note));
      resultContent.append(note);
    }
    resultContent.append(element("pre", detail.answer));
  } else if (detail?.detail && detail.run.status !== "RUNNING") {
    resultContent.append(element("p", detail.detail, detail.run.status === "FAILED" ? "result-error" : ""));
  }
  resultContent.hidden = !resultContent.childElementCount;
  byId("result-empty").hidden = !resultContent.hidden;

  const messages = byId("message-list");
  const opened = new Set([...messages.querySelectorAll("details[open]")].map(node => node.dataset.index));
  const roles = { USER: "用户任务", ASSISTANT: "模型响应", TOOL: "工具结果" };
  messages.replaceChildren(...(detail?.messages || []).map((message, index) => {
    const card = element("details", undefined, "message");
    card.dataset.index = String(index);
    card.open = opened.has(String(index));
    const count = message.toolCalls.length ? ` · ${message.toolCalls.length} 个工具调用` : "";
    const tool = message.toolCallId ? ` · ${message.toolCallId}${message.toolError ? " · 执行失败" : ""}` : "";
    const summary = element("summary");
    summary.append(element("code", `${index + 1}. ${message.role}`), document.createTextNode(`${roles[message.role]}${count}${tool}`));
    const calls = message.toolCalls.map(call => `${call.name} (${call.id})\n${JSON.stringify(call.arguments, null, 2)}`);
    card.append(summary, element("pre", [message.text, ...calls].filter(Boolean).join("\n\n"), "message-body"));
    return card;
  }));
  messages.hidden = !messages.childElementCount;
  byId("messages-empty").hidden = !messages.hidden;
}

function renderDetail(detail) {
  activeDetail = detail;
  const run = detail?.run;
  const changedRun = (run?.id || null) !== renderedId;
  const changedStatus = (run?.status || null) !== renderedStatus;
  renderedId = run?.id || null;
  renderedStatus = run?.status || null;
  byId("viewer-subtitle").textContent = run
    ? `${presetFor(run.preset)?.name || run.preset} · ${providerNames[run.provider]}`
    : "选择一条运行记录，或提交一个新任务。";
  if (changedRun || changedStatus) statusBadge(run, byId("run-status"));
  byId("resume").hidden = run?.status !== "PAUSED";
  byId("run-metrics").hidden = !run;
  if (run) {
    byId("detail-turns").textContent = run.modelTurns;
    byId("detail-tools").textContent = run.toolCalls;
    byId("detail-id").textContent = run.id.slice(0, 8);
    byId("detail-id").title = run.id;
  }
  byId("trace-count").hidden = !run;
  byId("trace-count").textContent = run?.events || 0;
  const events = detail?.events || [];
  const list = byId("trace-list");
  if (changedRun || renderedOmitted !== (detail?.omittedEvents || 0)) {
    list.replaceChildren();
    byId("trace").scrollTop = 0;
  }
  renderedOmitted = detail?.omittedEvents || 0;
  list.append(...events.slice(list.children.length).map(eventRow));
  list.hidden = !events.length;
  byId("trace-empty").hidden = Boolean(events.length);
  byId("trace-empty").querySelector("h3").textContent = run?.status === "RUNNING" ? "正在等待首个事件" : run ? "本次运行没有轨迹事件" : "等待任务开始";
  byId("trace-omitted").hidden = !detail?.omittedEvents;
  byId("trace-omitted").textContent = `另有 ${detail?.omittedEvents || 0} 条事件已省略。`;
  const signature = `${run?.id}:${run?.status}:${run?.phase}:${detail?.messages?.length}:${detail?.inbox?.length}:${detail?.inbox?.filter(item => item.appliedAt).length}`;
  if (changedRun || changedStatus || signature !== renderedContent) {
    renderOutput(detail);
    if (typeof renderInteraction === "function") renderInteraction(detail);
    renderedContent = signature;
    loadRecovery(detail);
  }
  updateButtons();
}

async function loadRecovery(detail) {
  byId("checkpoint-phase").textContent = "—";
  byId("input-version").textContent = "—";
  byId("saved-plan").replaceChildren();
  byId("recovery-description").textContent = "选择任务后查看保存的恢复状态。";
  if (!detail) return;
  const id = detail.run.id;
  try {
    const recovery = await api(`runs/${id}/recovery`);
    if (activeDetail?.run.id !== id || activeDetail.run.status !== detail.run.status) return;
    if (detail.run.status === "FAILED" && ["READY", "COMPLETED"].includes(recovery.phase)) byId("resume").hidden = false;
    byId("checkpoint-phase").textContent = recovery.phase;
    byId("input-version").textContent = recovery.inputVersion || "本课未绑定输入版本";
    byId("recovery-description").textContent = recovery.detail;
    const names = { pending: "待执行", in_progress: "进行中", done: "已完成" };
    byId("saved-plan").replaceChildren(...recovery.plan.map(step => element("li", `${names[step.status] || step.status} · ${step.task}`)));
  } catch (error) {
    if (activeDetail?.run.id === id) byId("recovery-description").textContent = error.message;
  }
}

function remember(detail) {
  const { run } = detail;
  const index = runs.findIndex(item => item.id === run.id);
  if (index === -1) runs.unshift(run);
  else runs[index] = run;
  renderHistory();
}

async function selectRun(id) {
  const version = ++selectionVersion;
  notice();
  try {
    const detail = await api(`runs/${id}`);
    if (version !== selectionVersion) return;
    selectedId = id;
    if (currentPage !== "console") {
      location.hash = "console";
      showPage();
    }
    setTab("trace");
    remember(detail);
    renderDetail(detail);
    byId("viewer").scrollIntoView({ block: "nearest" });
  } catch (error) {
    if (version === selectionVersion) notice(error.message);
  }
}

async function submitRun(resume) {
  if (pending || !connected) return;
  const version = ++selectionVersion;
  pending = true;
  notice();
  updateButtons();
  try {
    const path = resume ? `runs/${selectedId}/resume` : "runs";
    const body = resume ? {} : { preset: presets.value, provider: providers.value,
      inputPath: byId("research-options").hidden ? null : byId("input-path").value || null,
      question: (byId("ask-demo").checked && !byId("research-options").hidden ? "[教学：先询问] 请先使用 ask_user 确认研究方向，收到回答后继续。\n" : "") + question.value,
      maxTurns: maxTurns.valueAsNumber, pause: !byId("pause-option").hidden && byId("pause").checked };
    const detail = await api(path, body);
    remember(detail);
    if (version !== selectionVersion) return;
    selectedId = detail.run.id;
    setTab("trace");
    renderDetail(detail);
  } catch (error) {
    if (version === selectionVersion) notice(error.message);
  } finally {
    pending = false;
    updateButtons();
    if (!refreshing && runs.some(run => ["RUNNING", "QUEUED"].includes(run.status))) scheduleRefresh(80);
  }
}

function scheduleRefresh(delay) {
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(refresh, delay);
}

async function refresh() {
  refreshing = true;
  const version = selectionVersion;
  try {
    if (!config || !connected) {
      const firstLoad = !config;
      config = await api("config");
      if (firstLoad) renderConfig();
      else renderModelConfig();
    }
    const current = await api("runs");
    if (typeof renderManagement === "function") renderManagement(await api("management"));
    if (version !== selectionVersion || pending) return;
    setConnection(true);
    runs = current;
    renderHistory();
    if (!selectedId) selectedId = runs.find(run => run.status === "RUNNING")?.id || runs[0]?.id || null;
    const selected = runs.find(run => run.id === selectedId);
    if (selected && (["RUNNING", "QUEUED"].includes(selected.status) || activeDetail?.run.status !== selected.status)) {
      const detail = await api(`runs/${selectedId}`);
      if (version === selectionVersion && !pending) { remember(detail); renderDetail(detail); }
    } else if (selectedId && !selected) {
      selectedId = null;
      renderDetail(null);
    }
    updateButtons();
  } catch {
    setConnection(false);
    renderHistory();
  } finally {
    refreshing = false;
    scheduleRefresh(runs.some(run => ["RUNNING", "QUEUED"].includes(run.status)) ? 350 : 2000);
  }
}

byId("run-form").addEventListener("submit", event => { event.preventDefault(); submitRun(false); });
byId("resume").addEventListener("click", () => submitRun(true));
byId("open-config").addEventListener("click", () => byId("config-dialog").showModal());
byId("reset-question").addEventListener("click", () => { question.value = presetFor(presets.value).question; question.focus(); });
presets.addEventListener("change", applyPreset);
providers.addEventListener("change", updateProvider);
for (const button of document.querySelectorAll("[data-tab]")) button.addEventListener("click", () => setTab(button.dataset.tab));
document.querySelector('[role="tablist"]').addEventListener("keydown", event => {
  const tabs = [...document.querySelectorAll("[data-tab]")];
  const index = tabs.indexOf(document.activeElement);
  if (index < 0) return;
  const direction = { ArrowLeft: -1, ArrowRight: 1 }[event.key];
  const next = event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : direction ? (index + tabs.length + direction) % tabs.length : -1;
  if (next < 0) return;
  event.preventDefault();
  setTab(tabs[next].dataset.tab, true);
});
window.addEventListener("hashchange", showPage);
showPage();
refresh();
