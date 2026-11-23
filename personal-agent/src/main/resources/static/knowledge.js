"use strict";

let knowledgeState = null;
let knowledgeSearch = null;
let knowledgeLoading = false;
let knowledgeBusy = false;
let knowledgeSelection = new Map();
let knowledgeSearchVersion = 0;

function knowledgeButtons() {
  const selected = knowledgeSelection.size > 0;
  byId("knowledge-run-replay").disabled = knowledgeBusy || !selected;
  byId("knowledge-run-lmstudio").disabled = knowledgeBusy || !selected || !config?.lmStudioConfigured;
  byId("knowledge-run-deepseek").disabled = knowledgeBusy || !selected || !config?.deepSeekConfigured;
  byId("knowledge-search-button").disabled = knowledgeBusy || !knowledgeState?.configured;
  byId("knowledge-refresh").disabled = knowledgeBusy || !knowledgeState?.configured;
}

function renderKnowledgeState(state) {
  knowledgeState = state;
  byId("knowledge-policy").textContent = state.policy;
  byId("knowledge-root").textContent = state.stats?.root || "尚未配置个人知识库目录";
  const stats = state.stats;
  byId("knowledge-stats").replaceChildren(...(stats ? [
    element("span", `${stats.since} 至 ${stats.through}`), element("span", `${stats.documents} 篇近期资料`),
    element("span", `${stats.chunks} 个带来源片段`), element("span", `${(stats.bytes / 1024).toFixed(0)} KB 原文`)
  ] : []));
  const excluded = stats?.skipped || {};
  const labels = { older: "历史日期或年份", undated: "日期待确认", future: "晚于范围", excludedDirectories: "排除目录", symbolicLinks: "符号链接", largeFiles: "文件过大", unreadable: "无法读取", scanLimit: "扫描达到上限", depthLimit: "目录深度上限", indexBytes: "索引容量上限" };
  byId("knowledge-date-note").textContent = stats ? `索引更新于 ${fullTime.format(new Date(stats.indexedAt))}。${Object.entries(excluded).map(([key, count]) => `${labels[key] || key} ${count}`).join(" · ")}。日期待确认的导入笔记不按修改时间自动纳入。` : "";
  const folder = byId("knowledge-folder"), previous = folder.value;
  const any = element("option", "最近一年全部可检索目录"); any.value = "";
  folder.replaceChildren(any, ...(stats?.folders || []).map(path => { const option = element("option", path); option.value = path; return option; }));
  if ([...folder.options].some(option => option.value === previous)) folder.value = previous;
  knowledgeButtons();
}

async function loadKnowledge(force = false) {
  if (knowledgeLoading || (knowledgeState && !force)) { knowledgeButtons(); return; }
  knowledgeLoading = true;
  try { renderKnowledgeState(await api(force ? "knowledge/refresh" : "knowledge", force ? {} : undefined)); }
  catch (error) { notice(error.message); }
  finally { knowledgeLoading = false; }
}

function renderKnowledgeSelection() {
  const selected = [...knowledgeSelection.values()];
  byId("knowledge-selection-summary").textContent = selected.length ? `已选择 ${selected.length} 篇 · 全文 ${(selected.reduce((sum, item) => sum + item.bytes, 0) / 1024).toFixed(1)} KB` : "尚未选择资料";
  byId("knowledge-selection-list").replaceChildren(...selected.map(item => element("li", item.path)));
  knowledgeButtons();
}

async function previewKnowledge(item) {
  const version = knowledgeSearch?.version;
  try {
    const preview = await api("knowledge/preview", { id: item.id, version });
    if (version !== knowledgeSearch?.version) return;
    byId("knowledge-preview-title").textContent = preview.title;
    byId("knowledge-preview-path").textContent = preview.path;
    byId("knowledge-preview-date").textContent = `${preview.date} · ${preview.dateBasis} · 本地索引中的原文版本`;
    byId("knowledge-preview-content").textContent = preview.lines.map(line => `${String(line.number).padStart(4)}  ${line.text}`).join("\n");
    byId("knowledge-preview-dialog").showModal();
  } catch (error) { notice(error.message); }
}

function renderKnowledgeResults(result) {
  knowledgeSearch = result;
  knowledgeSelection.clear();
  byId("knowledge-result-status").textContent = result.matches.length ? `“${result.query}”找到 ${result.matches.length} 篇相关近期资料，最多展示前 30 篇。先预览并选择本次来源。` : "未找到匹配的近期资料。可以缩短关键词或调整目录；不会凭空创建研究上下文。";
  byId("knowledge-results").replaceChildren(...result.matches.map(item => {
    const node = element("article", undefined, "management-card knowledge-hit");
    const label = element("label", undefined, "knowledge-choice");
    const check = element("input"); check.type = "checkbox"; check.setAttribute("aria-label", `选择资料 ${item.title}`);
    check.addEventListener("change", () => {
      if (check.checked) {
        if (knowledgeSelection.size >= 6 || [...knowledgeSelection.values()].reduce((sum, value) => sum + value.bytes, item.bytes) > 2 * 1024 * 1024) {
          check.checked = false; notice("每次最多选择 6 篇资料，全文合计不超过 2 MiB。"); return;
        }
        knowledgeSelection.set(item.id, item);
      } else knowledgeSelection.delete(item.id);
      renderKnowledgeSelection();
    });
    label.append(check, element("strong", item.title));
    const actions = element("div", undefined, "card-actions");
    actions.append(button("预览全文与行号", () => previewKnowledge(item)));
    node.append(label, element("code", item.path, "path-text"), smallText(`${item.date} · ${item.dateBasis} · ${item.totalLines} 行 · ${(item.bytes / 1024).toFixed(1)} KB`),
      smallText(`命中 ${item.matchedTerms.join("、")} · 第 ${item.startLine}–${item.endLine} 行 · 匹配分 ${item.score.toFixed(1)}`), element("pre", item.excerpt, "knowledge-excerpt"), actions);
    return node;
  }));
  renderKnowledgeSelection();
}

async function searchKnowledge() {
  if (knowledgeBusy) return;
  const version = ++knowledgeSearchVersion;
  knowledgeBusy = true; knowledgeButtons(); notice();
  try {
    const result = await api("knowledge/search", { query: byId("knowledge-query").value, folder: byId("knowledge-folder").value });
    if (version === knowledgeSearchVersion) renderKnowledgeResults(result);
  } catch (error) { notice(error.message); }
  finally { knowledgeBusy = false; knowledgeButtons(); }
}

async function startKnowledge(provider) {
  if (knowledgeBusy || !knowledgeSearch || !knowledgeSelection.size) return;
  knowledgeBusy = true; knowledgeButtons(); notice();
  try {
    const detail = await api("knowledge/runs", { query: knowledgeSearch.query, folder: knowledgeSearch.folder,
      version: knowledgeSearch.version, documentIds: [...knowledgeSelection.keys()], question: byId("knowledge-question").value,
      provider, confirmExternal: provider === "deepseek" });
    remember(detail);
    await selectRun(detail.run.id);
    scheduleRefresh(50);
  } catch (error) { notice(error.message); }
  finally { knowledgeBusy = false; knowledgeButtons(); }
}

byId("knowledge-search-form").addEventListener("submit", event => { event.preventDefault(); searchKnowledge(); });
byId("knowledge-refresh").addEventListener("click", async () => {
  if (knowledgeBusy) return;
  knowledgeSearchVersion++;
  knowledgeBusy = true; knowledgeButtons(); notice();
  try { await loadKnowledge(true); knowledgeSearch = null; knowledgeSelection.clear(); byId("knowledge-results").replaceChildren(); byId("knowledge-result-status").textContent = "索引已刷新，请重新检索、预览并选择资料。"; renderKnowledgeSelection(); }
  finally { knowledgeBusy = false; knowledgeButtons(); }
});
byId("knowledge-run-replay").addEventListener("click", () => startKnowledge("replay"));
byId("knowledge-run-lmstudio").addEventListener("click", () => startKnowledge("lmstudio"));
byId("knowledge-run-deepseek").addEventListener("click", () => startKnowledge("deepseek"));
for (const button of document.querySelectorAll("[data-knowledge-query]")) button.addEventListener("click", () => {
  byId("knowledge-query").value = button.dataset.knowledgeQuery; searchKnowledge();
});
if (currentPage === "knowledge") loadKnowledge();
