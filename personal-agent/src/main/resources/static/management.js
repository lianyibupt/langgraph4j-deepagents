"use strict";

let management;
const phaseNames = {
  STARTING: "组装任务与工具", MODEL_REQUEST: "正在请求模型", TOOL_RUNNING: "正在执行工具",
  TOOL_COMPLETED: "工具结果已回填", INPUT_APPLIED: "补充要求已进入上下文", WAITING_INPUT: "等待你的回答",
  PAUSE_REQUESTED: "将在安全边界暂停", STOP_REQUESTED: "正在收尾并停止", QUEUED: "等待执行槽位",
  PAUSED: "已保存检查点并暂停", COMPLETED: "执行已完成", FAILED: "执行失败，需要检查",
  CANCELLED: "已按要求停止", TURN_LIMIT: "已达到轮次上限", RECOVERY_REQUIRED: "需要核对中断操作",
  PUBLICATION_FAILED: "回答已生成，报告发布失败"
};
const managementStatuses = {
  ACTIVE: "持续关注中", STOPPED: "已停止", OBSERVED: "等待单次批准", IGNORED: "未命中规则",
  DISPATCHING: "正在派发", WAITING_BUDGET: "等待每日额度", SENT: "已送达站内通知", PENDING: "待投递",
  WITHDRAWN: "已撤回", PROPOSED: "待确认建议", ACCEPTED: "已建立关注", DISMISSED: "已拒绝", ...statuses,
  ENABLED: "目标已启用", DISABLED: "目标已停用"
};

function stateLabel(status) { return managementStatuses[status] || status; }
function smallText(text) { return element("p", text, "card-description"); }
function button(text, action, style = "") {
  const node = element("button", text, `button small ${style}`);
  node.type = "button";
  node.addEventListener("click", action);
  return node;
}
function runButton(id) { return button("查看执行过程", () => selectRun(id)); }
function card(title, status, ...content) {
  const node = element("article", undefined, "management-card");
  const header = element("div", undefined, "card-heading");
  header.append(element("strong", title), element("span", stateLabel(status), `status-chip ${status}`));
  node.append(header, ...content);
  return node;
}
function stableList(id, value, build, empty) {
  const target = byId(id);
  const key = JSON.stringify(value);
  if (target.dataset.renderKey === key) return;
  target.dataset.renderKey = key;
  target.replaceChildren(...(value.length ? value.map(build) : [smallText(empty)]));
}
async function managementAction(path, body = {}) {
  notice();
  try {
    await api(path, body);
    renderManagement(await api("management"));
    scheduleRefresh(50);
  } catch (error) { notice(error.message); }
}

function renderManagement(data) {
  management = data;
  byId("management-health").hidden = !data.health;
  byId("management-health").textContent = data.health || "";
  byId("unread-count").textContent = data.notices.filter(item => item.status === "SENT" && !item.readAt).length;
  byId("delivery-state").textContent = data.deliveryEnabled ? "站内通知投递已启用" : "投递已暂停，成果和待发送消息继续保留";
  byId("toggle-delivery").textContent = data.deliveryEnabled ? "暂停投递" : "恢复投递";
  for (const prefix of ["watch", "goal"]) {
    const selector = byId(`${prefix}-provider`);
    selector.querySelector('[value="deepseek"]').disabled = !config?.deepSeekConfigured;
    selector.querySelector('[value="lmstudio"]').disabled = !config?.lmStudioConfigured;
    if (!byId(`${prefix}-path`).value) defaultScope(prefix);
  }
  stableList("watch-list", data.watches.toReversed().filter(watch => byId("watch-filter").value === "all" || (byId("watch-filter").value === "stopped" ? watch.status === "STOPPED" : watch.status !== "STOPPED")), watch => {
    const actions = element("div", undefined, "card-actions");
    if (watch.status === "ACTIVE") actions.append(button("立即检查", () => managementAction(`watches/${watch.id}/scan`)), button("暂停关注", () => managementAction(`watches/${watch.id}/pause`)));
    else actions.append(button("启用关注", () => managementAction(`watches/${watch.id}/enable`)));
    if (watch.status !== "STOPPED") actions.append(button("停止委托", () => managementAction(`watches/${watch.id}/stop`)));
    const checked = watch.lastCheckedAt ? fullTime.format(new Date(watch.lastCheckedAt)) : "尚未检查";
    return card(watch.title, watch.status, element("code", watch.inputPath, "path-text"), smallText(watch.goal),
      smallText(`${watch.mode === "auto" ? "自动只读研究" : "观察并逐次批准"} · 每 ${watch.intervalSeconds} 秒检测 · 每日最多 ${watch.dailyLimit} 次`),
      smallText(`最近检查 ${checked} · 已检测 ${watch.sequence} 次变化`),
      ...(watch.lastError ? [element("p", watch.lastError, "result-error")] : []), actions);
  }, "建立一项关注后，服务会持续检测目录变化。");
  stableList("trigger-list", data.triggers.toReversed().map(trigger => ({ ...trigger,
    delivery: data.notices.filter(notice => notice.runId === trigger.runId).map(notice => stateLabel(notice.status)).join("、")
  })), trigger => {
    const actions = element("div", undefined, "card-actions");
    if (trigger.status === "OBSERVED") actions.append(button("批准本次研究", () => managementAction(`triggers/${trigger.id}/approve`), "primary"));
    if (trigger.runId) actions.append(runButton(trigger.runId));
    const evidence = element("details", undefined, "event-evidence");
    evidence.append(element("summary", "查看输入版本与快照"), element("code", trigger.version, "path-text"), element("code", trigger.snapshot, "path-text"));
    return card(trigger.title, trigger.status, smallText(fullTime.format(new Date(trigger.at))),
      smallText(`变化文件 ${trigger.changed.join("、")}`), smallText(trigger.decision),
      smallText(`交付状态 ${trigger.delivery || "尚未产生通知"}`), evidence, actions);
  }, "检测到变化后，这里会依次记录事件、规则判断和执行结果。");
  stableList("notice-list", data.notices.toReversed(), item => {
    const actions = element("div", undefined, "card-actions");
    actions.append(runButton(item.runId));
    if (item.status === "SENT" && !item.readAt) actions.append(button("标为已读", () => managementAction(`notices/${item.id}/read`)));
    if (item.status === "FAILED") actions.append(button("重试投递", () => managementAction(`notices/${item.id}/retry`)));
    const body = element("details", undefined, "notice-body");
    body.append(element("summary", item.kind.startsWith("question:") ? "查看待回答问题" : "查看消息内容"), element("pre", item.body));
    return card(item.title, item.status, smallText(`${fullTime.format(new Date(item.createdAt))} · ${item.readAt ? "已读" : "未读"} · 重试 ${item.attempts} 次`),
      ...(item.error ? [element("p", item.error, "result-error")] : []), body, actions);
  }, "研究完成或需要你回答时，消息会出现在这里。");
  stableList("goal-list", data.goals.toReversed().filter(goal => goal.enabled || byId("goal-filter").value === "all"), goal => {
    const actions = element("div", undefined, "card-actions");
    actions.append(button(goal.enabled ? "停用目标" : "启用目标", () => managementAction(`goals/${goal.id}`, { enabled: !goal.enabled })));
    return card(goal.title, goal.enabled ? "ENABLED" : "DISABLED", smallText(goal.content), element("code", goal.inputPath, "path-text"),
      smallText(`关键词 ${goal.keywords} · ${goal.mode === "auto" ? "允许在此范围自动建立关注" : "先展示持续关注建议"}`), actions);
  }, "保存目标后，同一目录的相关研究会带上你的偏好。");
  stableList("suggestion-list", data.suggestions.toReversed().filter(item => item.status === "PROPOSED" || byId("goal-filter").value === "all"), suggestion => {
    const actions = element("div", undefined, "card-actions");
    actions.append(runButton(suggestion.runId));
    if (suggestion.status === "PROPOSED") actions.append(button("启用持续关注", () => managementAction(`suggestions/${suggestion.id}`, { enabled: true }), "primary"), button("拒绝建议", () => managementAction(`suggestions/${suggestion.id}`, { enabled: false })));
    return card("从相关研究发现持续服务机会", suggestion.status, smallText(suggestion.reason), actions);
  }, "相关研究命中长期目标后，这里会显示关注建议及匹配依据。");
  renderRunDelivery();
}

function renderActiveRuns() {
  const active = runs.filter(run => ["RUNNING", "WAITING_INPUT", "QUEUED", "PAUSED", "RECOVERY_REQUIRED"].includes(run.status));
  stableList("active-board", active, run => {
    const node = card(presetFor(run.preset)?.name || run.preset, run.status,
      smallText(`第 ${run.modelTurns} 轮 · ${phaseNames[run.phase] || stateLabel(run.status)}`), runButton(run.id));
    return node;
  }, "当前没有待处理任务。可以提交研究，也可以建立持续关注。");
}

function renderInteraction(detail) {
  const run = detail?.run;
  byId("execution-state").hidden = !run;
  byId("live-phase").textContent = run ? `第 ${run.modelTurns} 轮 · ${phaseNames[detail.phase] || stateLabel(run.status)}${detail.activeTool ? ` · ${detail.activeTool.name}` : ""}` : "";
  byId("live-scope").textContent = detail?.inputPath ? `本次读取范围 ${detail.inputPath}` : "";
  const steps = ["接收任务", "模型请求", "工具执行", "结果回填", "等待或交付"];
  const phaseStep = { STARTING: 0, INPUT_APPLIED: 0, MODEL_REQUEST: 1, TOOL_RUNNING: 2, TOOL_COMPLETED: 3 };
  const current = phaseStep[detail?.phase] ?? 4;
  byId("execution-steps").replaceChildren(...steps.map((text, index) => element("span", text, index === current ? "active" : "")));
  byId("pause-run").hidden = run?.status !== "RUNNING";
  byId("stop-run").hidden = !run || !["RUNNING", "WAITING_INPUT", "PAUSED", "QUEUED"].includes(run.status);
  byId("waiting-question").hidden = !detail?.waitingQuestion;
  byId("waiting-question").textContent = detail?.waitingQuestion ? `Agent 正在等待回答：${detail.waitingQuestion}` : "";
  byId("inbox-list").replaceChildren(...(detail?.inbox || []).map(input => {
    const row = element("li");
    row.append(element("strong", input.appliedAt ? "已应用到模型上下文" : "已接收，等待安全边界"), smallText(input.text));
    return row;
  }));
  const interactive = run && ["RUNNING", "WAITING_INPUT", "PAUSED", "QUEUED"].includes(run.status);
  byId("send-input").disabled = !interactive;
  byId("followup").disabled = !interactive;
  byId("memory-context").hidden = !detail?.background;
  byId("memory-context").textContent = detail?.background ? `本次采用的长期目标与偏好\n${detail.background}` : "";
  const inputList = byId("model-input-list");
  const openInputs = new Set([...inputList.querySelectorAll("details[open]")].map(node => node.dataset.index));
  inputList.replaceChildren(...(detail?.modelInput || []).map((message, index) => {
    const node = element("details", undefined, "message");
    node.dataset.index = String(index);
    node.open = openInputs.has(String(index));
    const calls = message.toolCalls.map(call => `${call.name}\n${JSON.stringify(call.arguments, null, 2)}`).join("\n");
    node.append(element("summary", `${index + 1}. ${message.role}${message.toolCallId ? ` · ${message.toolCallId}` : ""}`), element("pre", [message.text, calls].filter(Boolean).join("\n\n"), "message-body"));
    return node;
  }));
  renderRunDelivery();
}

function renderRunDelivery() {
  const notices = (management?.notices || []).filter(item => item.runId === activeDetail?.run.id && !item.kind.startsWith("question:"));
  const latest = notices.at(-1);
  byId("live-delivery").textContent = `交付状态 ${latest ? stateLabel(latest.status) + (latest.readAt ? " · 已读" : " · 未读") : "等待成果或问题"}`;
}

function defaultScope(prefix) {
  const preset = presetFor(byId(`${prefix}-preset`).value);
  if (preset && config?.projectPath) byId(`${prefix}-path`).value = `${config.projectPath}/${preset.input}`;
}
function formBody(form) {
  const data = Object.fromEntries(new FormData(form));
  for (const name of ["intervalSeconds", "dailyLimit"]) if (data[name] !== undefined) data[name] = Number(data[name]);
  return data;
}
for (const prefix of ["watch", "goal"]) {
  byId(`${prefix}-preset`).addEventListener("change", () => defaultScope(prefix));
  byId(`${prefix}-form`).addEventListener("submit", async event => {
    event.preventDefault();
    const submit = event.currentTarget.querySelector('[type="submit"]');
    submit.disabled = true;
    try { await managementAction(prefix === "watch" ? "watches" : "goals", formBody(event.currentTarget)); }
    finally { submit.disabled = false; }
  });
}
byId("toggle-delivery").addEventListener("click", () => managementAction("delivery", { enabled: !management.deliveryEnabled }));
byId("inbox-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (!selectedId) return;
  try {
    const detail = await api(`runs/${selectedId}/input`, { text: byId("followup").value });
    byId("followup").value = "";
    remember(detail); renderDetail(detail); scheduleRefresh(50);
  } catch (error) { notice(error.message); }
});
for (const action of ["pause", "stop"]) byId(`${action}-run`).addEventListener("click", async () => {
  if (!selectedId) return;
  try {
    const detail = await api(`runs/${selectedId}/actions/${action}`, {});
    remember(detail); renderDetail(detail); scheduleRefresh(50);
  } catch (error) { notice(error.message); }
});

for (const id of ["watch-filter", "goal-filter"]) byId(id).addEventListener("change", () => { if (management) renderManagement(management); });
