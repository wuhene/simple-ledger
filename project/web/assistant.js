import { EXPENSE, INCOME, categoryTotals, dayCount, localToday, money, shiftDate, sortEntries, totals } from "./model.js";
import { clearLedger, loadAssistantHistory, putEntry, putMeta, queryEntries, readEntry, removeEntry, saveAssistantSession } from "./storage.js";
import { cleanConversationTurns, cleanResultReferences, isResultDateFollowUp, requestedRank, validateIntent } from "./assistant-model.js";
import { cleanClues, currentLedgerLocation, noteWithPlace } from "./ledger-search.js";
import { groupLedger, programTerms, recallLedger } from "./ledger-skills.js";

const paragraph = (escape, value) => '<p class="micro">' + escape(value) + '</p>';
const typeName = type => type === INCOME ? "收入" : "支出";

export function createAssistant({ getData, escape: E, render, reload, isOpen, openChat, openPlacePicker }) {
  let config = { base: "", model: "", key: "" };
  const bookingModeKey = "ai-ledger-booking-mode";
  const autoLocationKey = "ai-ledger-auto-location";
  let bookingMode = localStorage.getItem(bookingModeKey) === "auto" ? "auto" : "confirm";
  let autoLocation = localStorage.getItem(autoLocationKey) === "on";
  let status = "", input = "", busy = false, testing = false, context = null, draft = null, request = null;
  let progress = "", requestText = "";
  let chat = [], previousInput = "";
  let pickedPlace = null;
  let activeId = "", historyError = "";
  const sessions = new Map();
  const say = (text, user = false) => {
    chat.push({ kind: "text", text, user });
  };
  const refresh = () => { if (isOpen()) render(); };
  const button = (action, label, style = "outline", disabled = false) => '<button type="button" class="' +
    style + '" data-assistant="' + action + '"' + (disabled ? " disabled" : "") + ">" + E(label) + "</button>";
  const displayMoney = minor => getData().preferences.hideAmountsByDefault ? "••••" : "¥" + money(minor);
  const available = () => getData().categories.filter(category => !category.hidden);
  const configured = () => Boolean(config.base && config.model);

  function restoreSession(id, session) {
    activeId = id;
    chat = session?.chat || [];
    context = session?.context || null;
    previousInput = session?.previousInput || "";
    draft = session?.draft || null;
    pickedPlace = null;
    input = session?.input || "";
  }

  async function initHistory() {
    try {
      const stored = await loadAssistantHistory();
      for (const session of stored.sessions) sessions.set(session.id, session);
      const latest = stored.sessions.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))[0];
      const id = stored.activeId || latest?.id || crypto.randomUUID();
      restoreSession(id, sessions.get(id));
    } catch {
      activeId = crypto.randomUUID();
      historyError = "无法读取历史会话。";
    }
  }

  function saveHistory(operation) {
    return operation.catch(() => {
      if (!historyError) {
        historyError = "会话暂时无法保存到浏览器。";
        if (isOpen()) render(false);
      }
    });
  }

  function persistSession() {
    if (!chat.length && !draft) return;
    const session = sessionSnapshot(chat, draft);
    sessions.set(activeId, session);
    return saveHistory(saveAssistantSession(session));
  }

  function sessionSnapshot(messages, currentDraft) {
    const firstMessage = messages.find(item => item.kind === "text" && item.user);
    return structuredClone({ id: activeId, chat: messages, context, draft: currentDraft, input, previousInput,
      title: [...(firstMessage?.text || "新会话")].slice(0, 26).join(""),
      updatedAt: new Date().toISOString() });
  }

  function stopRequest() {
    if (!request) return;
    request.abort();
    request = null;
    busy = false;
    progress = "";
    input ||= requestText;
    requestText = "";
    say("已停止生成。");
  }

  async function switchSession(id) {
    if (id === activeId) return;
    stopRequest();
    busy = true;
    try {
      await persistSession();
      await saveHistory(putMeta("activeAssistantSession", id));
      const session = sessions.get(id);
      restoreSession(id, session ? structuredClone(session) : null);
    } finally {
      busy = false;
    }
  }

  async function clearLocalData() {
    if (busy && !request) throw new Error("请等待记账操作完成后再清空数据。");
    request?.abort();
    if (request) busy = false;
    request = null;
    progress = requestText = "";
    await clearLedger();
    sessions.clear();
    restoreSession(crypto.randomUUID(), null);
    historyError = "";
  }

  function configPage() {
    return '<form id="assistant-config" class="card ai-config">' +
      '<label>API 地址<input class="field" name="base" type="url" placeholder="https://服务地址/v1" value="' + E(config.base) + '"></label>' +
      '<label>模型 ID<input class="field" name="model" maxlength="150" value="' + E(config.model) + '" placeholder="填服务商提供的模型 ID"></label>' +
      '<label>API Key<input class="field" name="key" type="password" autocomplete="off" maxlength="512" placeholder="' +
      (config.key ? "已设置 · 留空沿用" : "模型需要认证时填写") + '"></label>' +
      '<label>AI 记账模式<select class="field" name="bookingMode"><option value="confirm"' +
      (bookingMode === "confirm" ? " selected" : "") + '>需确认</option><option value="auto"' +
      (bookingMode === "auto" ? " selected" : "") + '>自动记账</option></select></label>' +
      paragraph(E, "自动记账会直接保存信息完整的流水，保存后可在聊天中撤销。") +
      '<label><input type="checkbox" name="autoLocation"' + (autoLocation ? " checked" : "") + '> AI 入账时附上当前位置</label>' +
      paragraph(E, "保存手机或浏览器当时的位置，和你说的消费地点分别记录。定位不可用时仍可入账。") +
      paragraph(E, "聊天会向模型发送输入和必要上下文。分析账本时会用到相关汇总，原始流水和备注由本机展示。配置在刷新前有效。") +
      '<div class="ai-actions"><button class="primary" name="action" value="save"' + (testing ? " disabled" : "") + '>保存设置</button>' +
      '<button class="outline" name="action" value="test"' + (testing ? " disabled" : "") + '>' + (testing ? "测试中…" : "测试连接") + '</button>' +
      button("clear-config", "清除配置", "plain", testing) + '</div><p role="status" class="micro">' + E(status) + '</p></form>';
  }

  function draftCard() {
    if (!draft) return "";
    const choices = available().filter(item => item.type === draft.type);
    return '<form class="card ai-draft" id="assistant-draft"><div class="row"><strong>' + typeName(draft.type) +
      '草稿</strong><span class="micro">待确认</span></div><div class="ai-fields">' +
      '<label>金额（元）<input class="field" name="amount" inputmode="decimal" value="' + E(draft.amount) + '" required></label>' +
      '<label>日期<input class="field" name="date" type="date" min="1900-01-01" max="' + localToday() + '" value="' + E(draft.date) + '" required></label>' +
      '<label>分类<select class="field" name="categoryId" required><option value="">请选择</option>' +
      choices.map(category => '<option value="' + E(category.id) + '"' + (category.id === draft.categoryId ? " selected" : "") + ">" + E(category.name) + "</option>").join("") +
      '</select></label><label>备注<input class="field" name="note" maxlength="200" value="' + E(draft.note) + '"></label></div>' +
      (draft.sourceText ? '<label>检索线索<textarea class="field" name="clueText" rows="2" maxlength="1776">' +
        E(draft.clueText) + '</textarea><span class="micro">每行一个「类型：内容」，可修改或删除。</span></label>' : "") +
      '<div class="ai-actions"><button class="primary"' + (busy ? " disabled" : "") + '>确认入账</button>' +
      button("cancel", "取消草稿", "outline", busy) + '</div></form>';
  }

  async function analysisSnapshot(query, step = () => {}) {
    const { categories } = getData();
    const type = query.metric === "income" ? INCOME : EXPENSE;
    const categoryIds = new Set(categories.filter(item => item.name === query.category &&
      (query.metric === "overview" || item.type === type)).map(item => item.id));
    let nearby;
    if (query.nearbyKm) {
      step("定位当前位置");
      const origin = await currentLedgerLocation();
      if (!origin) throw new Error("无法获取当前位置，请允许定位后重试。");
      nearby = { km: query.nearbyKm, origin };
      step("按入账坐标筛选 · " + nearby.km + " 公里内");
    }
    const useText = (query.kind === "search" && !nearby) || query.matchText || (!query.groupBy && !query.category && !query.filters?.length && query.terms?.length);
    const scopedRows = async bounds => (await recallLedger(queryEntries, { ...bounds, filters: query.filters, nearby }, query.terms, query.programTerms, useText)).filter(item =>
      (query.metric === "overview" || item.type === type) &&
      (!query.category || categoryIds.has(item.categoryId)));
    const days = dayCount(query);
    const referenceDays = days === 1 ? 7 : days;
    const reference = query.comparison || (query.kind === "insight" || query.view === "comparison"
      ? { start: shiftDate(query.start, -referenceDays), end: shiftDate(query.start, -1) } : null);
    step("查流水 · " + (useText ? "双路关键词取并集" : "按条件查询完整范围"));
    const rows = await scopedRows(query);
    step("汇总账本 · " + rows.length + " 笔符合条件");
    if (reference) step("对比账期 · " + reference.start + " 至 " + reference.end);
    const prior = reference ? await scopedRows(reference) : [];
    if (query.groupBy) step("排行 · 按" + query.groupBy + (query.groupOrder === "amount" ? "和金额" : "和笔数"));
    const groupRank = query.groupBy ? groupLedger(rows, query.groupBy, query.groupOrder, query.groupLimit || 10) : null;
    const { income, expense } = totals(rows);
    const previous = totals(prior);
    const baseline = reference ? { ...reference, days: dayCount(reference), count: prior.length,
      recordedDays: new Set(prior.map(item => item.businessDate)).size, incomeMinor: previous.income, expenseMinor: previous.expense } : null;
    const names = new Map(categories.map(item => [item.id, item.name]));
    const ranking = query.category ? [] : categoryTotals(rows, categories, type).slice(0, 8)
      .map(item => ({ name: item.category.name, amountMinor: item.amountMinor, percent: item.percent }));
    const ordered = query.order === "amount-desc"
      ? rows.sort((a, b) => b.amountMinor - a.amountMinor || b.businessDate.localeCompare(a.businessDate) ||
        b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id))
      : query.kind === "search" && query.order !== "latest" && !nearby ? rows : sortEntries(rows);
    const facts = { start: query.start, end: query.end, days, count: rows.length, category: query.category,
      metric: query.metric, view: query.view, incomeMinor: income, expenseMinor: expense,
      ...(query.order === "amount-desc" ? { order: query.order, limit: query.limit } : {}),
      largestMinor: rows.reduce((largest, item) => Math.max(largest, item.amountMinor), 0), baseline,
      top: ranking.slice(0, 5).map(item => ({ amountMinor: item.amountMinor, percent: Math.round(item.percent * 10) / 10 })) };
    return {
      query, facts, groupRank, entries: ordered.slice(0, facts.limit || 20).map(item => ({
        id: item.id, date: item.businessDate, note: item.note, amountMinor: item.amountMinor,
        ...(query.kind === "search" && item.clues?.length ? { clues: item.clues.slice(0, 4).map(clue => clue.value).join(" · ") } : {}),
        category: names.get(item.categoryId) || "未知分类", type: item.type,
      })),
      ranking,
    };
  }

  function fallbackAnswer(query, snapshot, hidden) {
    const facts = snapshot.facts;
    const label = (facts.category || "") + (facts.metric === "income" ? "收入" : "支出");
    const blocks = [];
    let text;
    if (hidden) text = "账本金额已隐藏，可以查看这次查询的日期和记录。";
    else if (!facts.count) text = "这段时间没有找到" + (facts.category ? "「" + facts.category + "」的" : "符合条件的") + "记录。";
    else if (facts.order === "amount-desc") text = "按金额从高到低列出" + (facts.category || "相关") + "流水前 " + snapshot.entries.length + " 笔。";
    else if (query.kind === "search") text = "找到 " + facts.count + (query.nearbyKm ? " 笔在当前位置 " + query.nearbyKm + " 公里内入账的流水。" : " 笔相关流水，点击记录可查看详情。");
    else if (facts.metric === "overview" && !facts.category) {
      text = "这段时间记了 " + displayMoney(facts.incomeMinor) + " 收入、" + displayMoney(facts.expenseMinor) + " 支出。";
    } else {
      text = label + "共 " + facts.count + " 笔，合计 " + displayMoney(facts.metric === "income" ? facts.incomeMinor : facts.expenseMinor) + "。";
    }
    if (query.kind === "insight" && facts.baseline && facts.baseline.recordedDays < Math.min(3, facts.baseline.days)) {
      text += " 对比期记录偏少，暂时不好判断是否比平时高。";
    }
    blocks.push({ type: "paragraph", text });
    if (snapshot.groupRank) blocks.push({ type: "groupRank" });
    else if (facts.count && (query.view === "entries" || (query.category && !query.view))) blocks.push({ type: "entries" });
    else if (query.view === "categories" && snapshot.ranking.length) blocks.push({ type: "categories" });
    else if (facts.baseline?.count && (query.kind === "insight" || query.view === "comparison")) blocks.push({ type: "comparison" });
    else if (query.view === "totals") blocks.push({ type: "totals" });
    return blocks;
  }

  function reportCard(report) {
    const { query, amount, type, ranking } = report;
    const leader = ranking[0];
    const tied = ranking.filter(item => item.amountMinor === leader?.amountMinor);
    const matched = ranking.find(item => item.category.id === report.selected);
    const breakdown = matched ? report.details.get(matched.category.id) : null;
    const detail = matched
      ? '<div class="ai-detail"><strong>' + E(matched.category.name) + '明细</strong>' +
        breakdown.rows.map(item =>
          '<div class="row"><span>' + E(item.businessDate + " · " + (item.note || matched.category.name)) +
          '</span><span>' + displayMoney(item.amountMinor) + '</span></div>').join("") +
        (breakdown.count > 20 ? paragraph(E, "这里展示最近 20 笔。") : "") + '</div>'
      : query.category && !ranking.some(item => item.category.name === query.category) ? paragraph(E, "这个区间没有该分类明细。") : "";
    return '<section class="card ai-report"><strong>收支统计</strong>' + paragraph(E, query.start + " 至 " + query.end) +
      '<div class="ai-fields"><div>收入 <strong>' + displayMoney(amount.income) + '</strong></div><div>支出 <strong>' +
      displayMoney(amount.expense) + '</strong></div></div>' +
      (report.empty ? paragraph(E, "这个区间没有记录。") : !leader ? paragraph(E, "这个区间没有" + typeName(type) + "记录。") :
        paragraph(E, typeName(type) + "占比最高：" + tied.map(item => item.category.name).join("、") +
          (tied.length > 1 ? "（并列）" : "") + " · " + leader.percent.toFixed(1) + "%")) +
      ranking.map(item => '<button type="button" class="ai-rank" data-assistant="detail" data-id="' + E(report.id) + '" data-category="' + E(item.category.id) +
        '"><span class="row"><span>' + E(item.category.name) + '</span><span>' + displayMoney(item.amountMinor) + ' · ' +
        item.percent.toFixed(1) + '% ›</span></span><span class="ai-track" aria-hidden="true"><span style="width:' +
        item.percent.toFixed(1) + '%"></span></span></button>').join("") + detail + '</section>';
  }

  function shortDate(date, showYear = false) {
    const [year, month, day] = date.split("-").map(Number);
    return (showYear ? year + "年" : "") + month + "月" + day + "日";
  }

  function rangeText(start, end) {
    const showYear = start.slice(0, 4) !== end.slice(0, 4) || start.slice(0, 4) !== localToday().slice(0, 4);
    return shortDate(start, showYear) + (start === end ? "" : " — " + shortDate(end, showYear));
  }

  function richAnswer(item) {
    const snapshot = item.snapshot;
    const facts = snapshot?.facts;
    const panel = (label, body, count = "") => '<section class="ai-data-block"><div class="ai-block-header"><strong>' +
      E(label) + '</strong>' + (count ? '<span class="ai-block-count">' + E(count) + '</span>' : "") + '</div>' + body + '</section>';
    const row = (label, value) => '<div class="row"><span>' + E(label) + '</span><strong>' + E(value) + '</strong></div>';
    const period = facts ? paragraph(E, facts.start === "1900-01-01" ? "历史至 " + facts.end : rangeText(facts.start, facts.end)) : "";
    const seen = new Set();
    const dataTypes = ["totals", "categories", "entries", "comparison", "groupRank"];
    const formatText = text => E(text.replace(/\{\{category([1-5])\}\}/g, (_, number) =>
      snapshot?.ranking[Number(number) - 1]?.name || "该分类").replace(/\{\{group([1-5])\}\}/g, (_, number) =>
      snapshot?.groupRank?.groups[Number(number) - 1]?.name || "该组"));
    const blocks = facts && getData().preferences.hideAmountsByDefault
      ? [{ type: "paragraph", text: "账本金额已隐藏。此处展示查询记录。" }, ...item.blocks.filter(block => dataTypes.includes(block.type))]
      : item.blocks;
    const parts = blocks.map(block => {
      if (block.type === "heading") return '<h3>' + formatText(block.text) + '</h3>';
      if (block.type === "paragraph") return '<p>' + formatText(block.text) + '</p>';
      if (block.type === "bullets") return '<ul>' + block.items.map(text => '<li>' + formatText(text) + '</li>').join("") + '</ul>';
      if (!snapshot || seen.has(block.type)) return "";
      seen.add(block.type);
      if (block.type === "groupRank" && snapshot.groupRank) {
        const { dimension, covered, missing, groups } = snapshot.groupRank;
        return panel(dimension + "排行", period + paragraph(E, "有线索 " + covered + " 笔 · 缺失 " + missing + " 笔") +
          groups.map((group, index) => row((index + 1) + ". " + group.name,
            group.count + " 笔 · " + displayMoney(group.amountMinor))).join(""));
      }
      if (block.type === "entries" && facts.count) {
        const ranked = facts.order === "amount-desc";
        const showYear = ranked || item.forceYear || facts.start.slice(0, 4) !== facts.end.slice(0, 4);
        const entryRows = entries => entries.map((entry, index) =>
          (ranked || ((showYear || facts.start !== facts.end) && (index === 0 || entry.date !== entries[index - 1].date))
            ? '<div class="ai-entry-day">' + E((ranked ? "第 " + (index + 1) + " 名 · " : "") +
              shortDate(entry.date, showYear || entry.date.slice(0, 4) !== localToday().slice(0, 4))) + '</div>' : "") +
          (entry.id ? '<button class="ai-entry-link" data-action="detail" data-id="' + E(entry.id) + '">' +
            row(entry.note || entry.category, (entry.type === INCOME ? "+" : "−") + displayMoney(entry.amountMinor)) + '</button>' :
            row(entry.note || entry.category, (entry.type === INCOME ? "+" : "−") + displayMoney(entry.amountMinor))) +
          (entry.clues ? paragraph(E, entry.clues) : "")).join("");
        const entries = snapshot.entries;
        if (ranked) return panel((facts.category || "相关") + "金额排行", period + entryRows(entries) +
          (facts.count > entries.length ? paragraph(E, "共匹配 " + facts.count.toLocaleString("zh-CN") + " 笔，显示金额最高的 " + entries.length + " 笔。") : ""),
          entries.length + " 笔");
        const more = entries.length > 5 ? '<details><summary>展开其余 ' + (entries.length - 5) + ' 笔</summary>' +
          entryRows(entries.slice(5)) + '</details>' : "";
        return panel((facts.category || "相关") + "明细", period + entryRows(entries.slice(0, 5)) + more +
          (facts.count > entries.length ? paragraph(E, "这里显示其中 " + entries.length + " 笔。") : ""), facts.count + " 笔");
      }
      if (block.type === "categories" && snapshot.ranking.length) {
        return panel("分类分布", period + snapshot.ranking.map(entry =>
          row(entry.name, displayMoney(entry.amountMinor) + " · " + entry.percent.toFixed(1) + "%") +
          '<div class="ai-track" aria-hidden="true"><span style="width:' + entry.percent.toFixed(1) + '%"></span></div>').join(""));
      }
      if (block.type === "totals") {
        return panel((facts.category || "") + "汇总", period +
          (facts.metric !== "expense" ? row("收入", displayMoney(facts.incomeMinor)) : "") +
          (facts.metric !== "income" ? row("支出", displayMoney(facts.expenseMinor)) : "") +
          row("记录", facts.count + " 笔"));
      }
      if (block.type === "comparison" && facts.baseline) {
        const prior = facts.baseline;
        const metric = facts.metric === "income" ? "incomeMinor" : "expenseMinor";
        const average = prior.count && facts.days > 0 && prior.days > 0 ?
          row(facts.metric === "income" ? "日均收入" : "日均支出",
            displayMoney(Math.round(facts[metric] / facts.days)) + " / " + displayMoney(Math.round(prior[metric] / prior.days))) +
          paragraph(E, "对比期 " + prior.recordedDays + " 天有记录") : "";
        return panel("账期对比", period + row("本期笔数", facts.count + " 笔") +
          row("对比期 " + prior.start + " 至 " + prior.end, prior.count + " 笔") +
          (facts.metric !== "expense" ? row("收入", displayMoney(facts.incomeMinor) + " / " + displayMoney(prior.incomeMinor)) : "") +
          (facts.metric !== "income" ? row("支出", displayMoney(facts.expenseMinor) + " / " + displayMoney(prior.expenseMinor)) : "") + average);
      }
      return "";
    }).join("");
    return '<div class="ai-rich-answer">' + (parts || '<p>这次没有可展示的内容。</p>') + '</div>';
  }

  function chatItem(item) {
    if (item.kind === "answer") return richAnswer(item);
    if (item.kind === "report") return reportCard(item);
    if (item.kind === "receipt") {
      return '<section class="card ai-receipt"><div class="ai-receipt-main"><span class="ai-receipt-icon" aria-hidden="true">' +
        (item.undone ? "↶" : "✓") + '</span><div><strong>' + (item.undone ? "已撤销" : "已入账") +
        '</strong>' + (item.entry.note ? paragraph(E, item.entry.note) : "") +
        (item.entry.location ? paragraph(E, "已附入账定位") : "") + '</div><strong class="ai-receipt-value">' +
        E(displayMoney(item.entry.amountMinor)) + '</strong></div>' +
        (item.undone ? "" : '<button type="button" class="outline" data-assistant="undo" data-id="' +
          E(item.entry.id) + '"' + (busy ? " disabled" : "") + '>撤销入账</button>') + '</section>';
    }
    return '<p class="ai-bubble ' + (item.user ? "ai-user" : "") + '">' + E(item.text) + '</p>';
  }

  function sessionButton() {
    return button("new-session", "新会话", "ai-entry", busy && !request);
  }

  function transcript() {
    let html = "", reply = "";
    const group = contents => '<div class="ai-turn"><div class="ai-turn-label"><span class="ai-spark" aria-hidden="true">✦</span>爱记账助手</div>' + contents + '</div>';
    for (const item of chat) {
      if (item.kind === "text" && item.user) {
        if (reply) html += group(reply);
        reply = "";
        html += chatItem(item);
      } else {
        if (item.steps?.length) reply += '<details class="ai-skill-trace"><summary>已执行 ' + item.steps.length + ' 个账本步骤</summary><ol>' +
          item.steps.map(step => "<li>" + E(step) + "</li>").join("") + "</ol></details>";
        reply += chatItem(item);
      }
    }
    reply += draftCard();
    if (request) reply += '<div class="ai-progress" role="status"><span class="ai-progress-dot" aria-hidden="true"></span>' + E(progress) + '</div>';
    return html + (reply ? group(reply) : "");
  }

  function starter() {
    if (!configured() && chat.length) return '<div class="ai-connect-note">历史可查看<button type="button" class="plain" data-action="assistant-settings">连接模型继续聊天</button></div>';
    if (chat.length) return "";
    return '<div class="ai-starter"><span class="ai-starter-mark" aria-hidden="true">✦</span><h2>想记一笔，还是聊聊花销？</h2>' +
      (!configured() ? '<p>连接模型后，就可以用一句话记账或查账。</p><button type="button" class="primary" data-action="assistant-settings">连接模型</button>' :
        '<div class="ai-starter-actions">' + button("example-record", "午餐花了 28 元") + button("example-stats", "这个月主要花在哪？") + '</div>') + '</div>';
  }

  function historyPage() {
    const items = [...sessions.values()].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
    return (historyError ? '<p role="status" class="error">' + E(historyError) + '</p>' : "") +
      (items.length ? '<div class="list ai-history">' + items.map(item =>
        '<button type="button" class="menu" data-assistant="restore-session" data-id="' + E(item.id) +
        '"' + (busy && !request ? " disabled" : "") + '><span><strong>' + E(item.title) + '</strong><span class="micro">' +
        E(new Date(item.updatedAt).toLocaleString("zh-CN")) + '</span></span><span class="chev">' +
        (item.id === activeId ? "当前" : "›") + '</span></button>').join("") + '</div>' :
        '<div class="card"><strong>暂无历史会话</strong><p class="micro">发送消息后，会话会保存在这个浏览器。</p></div>');
  }

  function page() {
    return '<div class="assistant-thread" role="region" aria-label="聊天记录" tabindex="0"><div class="ai-chat">' +
      starter() +
      (historyError ? '<p role="status" class="error">' + E(historyError) + '</p>' : "") +
      transcript() + '</div></div>' +
      '<div class="assistant-dock"><button type="button" class="chat-latest" data-action="assistant-latest" hidden>回到最新 ↓</button>' +
      '<form id="assistant-chat" class="ai-composer"><button type="button" aria-label="选择地点" class="plain place-picker-icon" data-assistant="pick-place"' +
      (busy ? " disabled" : "") + ' aria-pressed="' + Boolean(pickedPlace?.name || pickedPlace?.location || draft?.location) + '">⌖</button><textarea aria-label="聊天消息" name="message" class="field" rows="1" maxlength="600" ' +
      'placeholder="输入一笔账，或聊聊花销"' + (!configured() ? " disabled" : "") + '>' + E(input) + '</textarea>' +
      (request ? button("stop", "停止", "ai-stop") : '<button type="submit" class="primary"' +
        (busy || !configured() ? " disabled" : "") + '>发送</button>') + '</form>' +
      (pickedPlace?.name ? paragraph(E, "地点 · " + pickedPlace.name) : "") + '</div>';
  }

  function previousEntryResult() {
    const index = chat.findLastIndex(item => item.snapshot?.entries?.length && item.blocks?.some(block => block.type === "entries"));
    if (index < 0) return null;
    const result = chat[index];
    const question = result.question || chat.slice(0, index).findLast(item => item.kind === "text" && item.user)?.text || "";
    return { ...result, question };
  }

  function conversationContext() {
    const turns = [], results = [];
    const hidden = getData().preferences.hideAmountsByDefault;
    for (let i = chat.length - 2; i >= 0 && (turns.length < 12 || results.length < 6); i--) {
      const item = chat[i];
      let turn;
      if (item.snapshot?.facts || item.kind === "report") {
        const id = "r" + (i + 1);
        if (results.length < 6) {
          const { facts, entries } = item.snapshot || { facts: item.query, entries: [] };
          const blocks = item.blocks?.map(block => block.type) || ["totals", "categories"];
          const question = item.question || chat.slice(0, i).findLast(entry => entry.kind === "text" && entry.user)?.text;
          results.push({ id, question, start: facts.start, end: facts.end, category: facts.category,
            metric: facts.metric, view: facts.view, blocks, order: facts.order || item.snapshot?.query?.order, limit: facts.limit,
            count: facts.count, shown: blocks.includes("entries") ? entries.length : undefined,
            dates: blocks.includes("entries") ? [...new Set(entries.map(entry => entry.date))].slice(0, 8) : [],
            comparison: facts.baseline && blocks.includes("comparison")
              ? { start: facts.baseline.start, end: facts.baseline.end, days: facts.baseline.days,
                ...(!hidden ? { incomeMinor: facts.baseline.incomeMinor, expenseMinor: facts.baseline.expenseMinor } : {}) } : undefined,
            ...(!hidden ? {
              interpretation: item.blocks?.filter(block => block.type === "paragraph").map(block => block.text).join(" "),
              incomeMinor: (facts.metric !== "expense" && blocks.includes("totals")) || blocks.includes("comparison")
                ? facts.incomeMinor ?? item.amount?.income : undefined,
              expenseMinor: (facts.metric !== "income" && blocks.includes("totals")) || blocks.includes("comparison")
                ? facts.expenseMinor ?? item.amount?.expense : undefined,
              categories: blocks.includes("categories") ? item.snapshot?.ranking : undefined,
            } : {}),
          });
          turn = { role: "assistant", resultId: id };
        }
      } else if (item.kind === "text") {
        turn = { role: item.user ? "user" : "assistant", text: item.localOnly ? "已在本机核对入账定位。" : item.text };
      } else if (item.kind === "answer") {
        const text = item.blocks.flatMap(block => block.type === "bullets" ? block.items : block.text || []).join("\n");
        turn = { role: "assistant", text };
      } else if (item.kind === "receipt") {
        turn = { role: "assistant", recordState: item.undone ? "undone" : "saved", date: item.entry.businessDate, type: item.entry.type };
      }
      if (turn && turns.length < 12) turns.push(turn);
    }
    return { recentTurns: cleanConversationTurns(turns.reverse()), resultRefs: cleanResultReferences(results.reverse()) };
  }

  function referencedResult(id, resultRefs) {
    const reference = resultRefs.find(item => item.id === id);
    if (!reference) return null;
    const item = chat[Number(id.slice(1)) - 1];
    return { ...item, question: reference.question };
  }

  async function resultWithDates(previous) {
    const original = previous.snapshot, facts = original.facts;
    const limit = facts.limit || requestedRank(previous.question);
    const query = original.query || { kind: "stats", start: facts.start, end: facts.end, category: facts.category, metric: facts.metric };
    const snapshot = limit && facts.order !== "amount-desc"
      ? await analysisSnapshot({ ...query, view: "entries", order: "amount-desc", limit })
      : original;
    return { kind: "answer", forceYear: true, question: previous.question, snapshot, blocks: [
      { type: "paragraph", text: "你问的是刚才那张结果里每笔的年份。我沿用原来的查询范围，把完整日期补上。" },
      { type: "entries" },
    ] };
  }

  async function callModel(settings, action, text = "", signal, details) {
    const today = action === "test" ? "2026-01-02" : localToday();
    const result = action === "interpret" ? previousEntryResult() : null;
    const resultFacts = result?.snapshot.facts;
    let response;
    try {
      response = await fetch("./ai/api", { method: "POST", signal, headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action, base: settings.base, model: settings.model, key: settings.key,
          ...(action === "interpret" ? { text, today, lastQuery: context?.lastQuery, pending: context?.pending, previousInput,
            ...details, lastResult: resultFacts && { question: result.question,
              start: resultFacts.start, end: resultFacts.end, category: resultFacts.category,
              order: resultFacts.order, limit: resultFacts.limit, view: "entries" } } :
            action === "analyze" ? { text, ...details } : {}) }) });
    } catch {
      throw new Error("无法连接本机调试服务，请运行 npm start。");
    }
    const data = await response.json().catch(() => null);
    if (!response.ok || !data) throw new Error(data?.error || "无法连接本机调试服务，请运行 npm start。");
    const intent = validateIntent(data.intent, today);
    if (action === "analyze" && !["answer", "message"].includes(intent.kind)) throw new Error("模型未返回分析");
    return intent;
  }

  async function submitConfig(form, action) {
    if (testing) return;
    const fields = new FormData(form);
    if (action !== "test") {
      const nextMode = fields.get("bookingMode") === "auto" ? "auto" : "confirm";
      localStorage.setItem(bookingModeKey, nextMode);
      bookingMode = nextMode;
      autoLocation = fields.has("autoLocation");
      localStorage.setItem(autoLocationKey, autoLocation ? "on" : "off");
    }
    const base = fields.get("base").trim(), key = fields.get("key").trim();
    const next = { base, model: fields.get("model").trim(),
      key: key || (base === config.base ? config.key : "") };
    if (!next.base || !next.model) {
      form.querySelector('[role="status"]').textContent = action !== "test" && !next.base && !next.model ?
        "记账模式已保存。填写 API 地址和模型 ID 后即可使用助手。" : "请填写 API 地址和模型 ID。";
      return;
    }
    config = next;
    if (action !== "test") {
      status = "设置已保存。";
      refresh();
      return;
    }
    status = "";
    testing = true;
    refresh();
    try {
      const result = await callModel(next, "test");
      if (result.kind !== "record" || result.amountMinor !== 2800 || result.date !== "2026-01-02" || result.type !== EXPENSE) {
        throw new Error("模型已响应，但未正确识别连接测试示例。");
      }
      status = "连接成功，模型已正确识别示例账单。";
    } catch (error) {
      status = error.message;
    } finally {
      testing = false;
      refresh();
    }
  }

  async function send(value) {
    const text = value.trim();
    if (busy || !text) return;
    if (!configured()) return;
    if (draft) {
      say("请先确认或取消上一笔草稿。");
      refresh();
      return;
    }
    say(text, true);
    input = "";
    const settings = config;
    const automatic = bookingMode === "auto";
    const currentRequest = new AbortController();
    request = currentRequest;
    requestText = text;
    progress = "正在理解你的问题";
    const steps = [];
    const step = detail => {
      if (request !== currentRequest) return;
      steps.push(detail);
      progress = detail;
      refresh();
    };
    busy = true;
    persistSession();
    refresh();
    try {
      if (/^(?:你)?(?:没(?:有)?|有)?记录坐标[吗么？?。]*$|(?:刚才|刚刚|上笔|上一笔|这笔).*(?:位置|地点|在哪|定位|坐标)/.test(text)) {
        const receipt = chat.findLast(item => item.kind === "receipt" && !item.undone);
        if (receipt) {
          const entry = await readEntry(receipt.entry.id);
          if (request !== currentRequest) return;
          say(entry ? bookingLocationReply(entry) : "这笔流水已经不存在。");
          chat[chat.length - 1].localOnly = true;
          return;
        }
      }
      const conversation = conversationContext();
      const previousResult = previousEntryResult();
      const anotherCategory = previousResult?.snapshot.facts.category &&
        getData().categories.some(category => category.name !== previousResult.snapshot.facts.category && text.includes(category.name));
      const intent = previousResult && !anotherCategory && isResultDateFollowUp(text) && !/上上|第.{1,3}张|更早/.test(text) ? { kind: "result-dates" } :
        await callModel(settings, "interpret", text, currentRequest.signal, conversation);
      if (request !== currentRequest) return;
      previousInput = text.slice(0, 300);
      const reference = intent.resultId ? referencedResult(intent.resultId, conversation.resultRefs) : previousResult;
      if (intent.kind === "result-dates" && reference?.snapshot?.entries?.length) {
        const answer = await resultWithDates(reference);
        if (request !== currentRequest) return;
        context = { lastQuery: answer.snapshot.query || context?.lastQuery };
        chat.push(answer);
        return;
      }
      if (intent.kind === "result-follow-up" && reference) {
        const source = reference.snapshot;
        const facts = source?.facts || reference.query;
        const query = { ...(source?.query || { kind: "stats", start: facts.start, end: facts.end, category: facts.category,
          metric: facts.metric || "overview", comparison: facts.baseline }), view: intent.view };
        if (intent.view !== "entries") Object.assign(query, { order: "", limit: 0 });
        if (intent.view !== "groupRank") query.groupBy = "";
        const snapshot = await analysisSnapshot(query, step);
        if (request !== currentRequest) return;
        const blocks = fallbackAnswer(query, snapshot, getData().preferences.hideAmountsByDefault);
        chat.push({ kind: "answer", blocks, snapshot, question: reference.question, steps });
        context = { lastQuery: query };
        return;
      }
      if (intent.kind === "record") {
        const category = available().find(item => item.name === intent.category && item.type === intent.type);
        const previous = context?.pending?.original;
        const followsUp = intent.usesPending || /^(?:[¥￥]\s*)?\d+(?:\.\d+)?\s*(?:元|块钱?)?[。！!]*$/.test(text);
        draft = { id: crypto.randomUUID(), type: intent.type, amount: (intent.amountMinor / 100).toFixed(2),
          date: intent.date, categoryId: category?.id || "", note: intent.note,
          sourceText: previous && followsUp ? (previous + "\n" + text).slice(0, 1200) : text,
          clueText: intent.clues.map(clue => clue.type + "：" + clue.value).join("\n") };
        if (pickedPlace) {
          setDraftPlace(pickedPlace);
          pickedPlace = null;
        }
        context = { lastQuery: context?.lastQuery };
        if (automatic && category) {
          // A write in progress cannot be interrupted or moved to another session.
          request = null;
          progress = requestText = "";
          refresh();
          try {
            await bookDraft(["校验记账信息", "自动入账"]);
          } catch {
            say(draft ? "自动记账未能保存，可以核对草稿后重试。" : "已入账，账本页面稍后刷新。");
          } finally {
            busy = false;
            persistSession();
            refresh();
          }
          return;
        }
        say(intent.text || (category ? "识别到一笔记录，请核对后入账。" : "识别到一笔记录，请先选分类。"));
        chat[chat.length - 1].steps = ["生成记账草稿 · 等待确认"];
      } else if (["search", "stats", "insight"].includes(intent.kind)) {
        intent.programTerms = intent.nearbyKm ? [] : programTerms(text);
        if (intent.kind === "search" && !intent.nearbyKm && !intent.terms.length && !intent.programTerms.length && !intent.filters.length) throw new Error("请提供想查找的关键词。");
        const limit = intent.groupBy ? 0 : requestedRank(text) || intent.limit;
        if (limit) Object.assign(intent, { order: "amount-desc", limit, view: "entries" });
        const snapshot = await analysisSnapshot(intent, step);
        if (request !== currentRequest) return;
        const hidden = getData().preferences.hideAmountsByDefault;
        let blocks = fallbackAnswer(intent, snapshot, hidden);
        if (config === settings && !hidden && !limit) {
          progress = "正在分析账本";
          refresh();
          try {
            const groupRank = snapshot.groupRank;
            const answer = await callModel(settings, "analyze", text, currentRequest.signal, { ...conversation, facts: {
              ...snapshot.facts, groupRank: groupRank && { dimension: groupRank.dimension, covered: groupRank.covered, missing: groupRank.missing,
                groups: groupRank.groups.slice(0, 5).map(({ count, amountMinor }) => ({ count, amountMinor })) },
            } });
            blocks = answer.kind === "answer" ? answer.blocks :
              [{ type: "paragraph", text: answer.text }, ...blocks.filter(block => block.type !== "paragraph")];
          } catch {
            // The local answer still works when the analysis request fails.
          }
          if (request !== currentRequest) return;
        }
        if (snapshot.groupRank && !blocks.some(block => block.type === "groupRank")) blocks.push({ type: "groupRank" });
        if (snapshot.facts.baseline && !blocks.some(block => block.type === "comparison")) blocks.push({ type: "comparison" });
        if (intent.kind === "search" && snapshot.entries.length && !blocks.some(block => block.type === "entries")) blocks.push({ type: "entries" });
        chat.push({ kind: "answer", blocks, snapshot, question: text, steps });
        context = { lastQuery: intent };
      } else if (intent.kind === "clarify") {
        const pending = { original: context?.pending?.original || text, latest: text, question: intent.question };
        context = { lastQuery: context?.lastQuery, pending };
        say(intent.question);
      } else {
        context = { lastQuery: context?.lastQuery };
        if (intent.kind === "answer") chat.push({ kind: "answer", blocks: intent.blocks });
        else say(intent.text || "我没找到你指的那张结果。可以告诉我那次查的分类或时间范围。");
      }
    } catch (error) {
      if (request === currentRequest) {
        input ||= text;
        say(error.message);
      }
    } finally {
      if (request === currentRequest) {
        request = null;
        progress = requestText = "";
        busy = false;
        persistSession();
        refresh();
      }
    }
  }

  async function saveDraft(form) {
    if (busy || !draft) return;
    const fields = new FormData(form);
    for (const name of ["amount", "note", "date", "categoryId", "clueText"]) draft[name] = fields.get(name);
    busy = true;
    try {
      await bookDraft();
    } catch (error) {
      say(error.message || "账单保存失败，请重试。");
    } finally {
      busy = false;
      persistSession();
      refresh();
    }
  }

  async function bookDraft(steps) {
    const record = validateIntent({ ...draft, category: "", kind: "record" }, localToday());
    if (!available().some(item => item.id === draft.categoryId && item.type === draft.type)) {
      throw new Error("请选择当前收支类型下的有效分类。");
    }
    const now = new Date().toISOString();
    const location = draft.location || (autoLocation && !draft.locationOmitted ? await currentLedgerLocation() : null);
    const clues = cleanClues(String(draft.clueText || "").split("\n").flatMap(line => {
      const divider = line.search(/[:：]/);
      return divider > 0 ? [{ type: line.slice(0, divider), value: line.slice(divider + 1) }] : [];
    }));
    const entry = { id: draft.id, type: record.type, amountMinor: record.amountMinor,
      businessDate: record.date, categoryId: draft.categoryId, note: record.note, createdAt: now, updatedAt: now,
      ...(draft.sourceText ? { sourceText: draft.sourceText } : {}), ...(clues.length ? { clues } : {}), ...(location ? { location } : {}) };
    const receipt = { kind: "receipt", entry, undone: false, steps };
    const session = sessionSnapshot([...chat, receipt], null);
    await putEntry(entry, session);
    sessions.set(activeId, session);
    draft = null;
    chat.push(receipt);
    await reload();
  }

  function bookingLocationReply(entry) {
    const named = entry.clues?.filter(clue => ["城市", "地点", "区县", "商家"].includes(clue.type)).map(clue => clue.value).join(" · ");
    const location = entry.location;
    const label = location && [location.city, location.district].filter(Boolean).join(" · ");
    return (named ? "你提到的消费地点线索是 " + named + "。" : "这笔账没有明确的消费地点线索。") +
      (location ? "入账时保存了手机定位" + (label ? "（" + label + "）" : "") + "，坐标为 " +
        location.latitude.toFixed(5) + ", " + location.longitude.toFixed(5) + "，精度约 " + Math.round(location.accuracy) + " 米。"
        : "这笔未保存入账定位。");
  }

  function setDraftPlace({ name, location }) {
    if (name && !/^(城市|区县|地点)[:：]/m.test(draft.clueText)) {
      draft.clueText = "地点：" + name + (draft.clueText ? "\n" + draft.clueText : "");
    }
    draft.note = noteWithPlace(draft.note, name);
    draft.location = location;
    draft.locationOmitted = !location && !name;
  }

  async function act(control) {
    const action = control.dataset.assistant;
    if (action === "pick-place" && !busy) {
      openPlacePicker({ selected: Boolean(pickedPlace?.name || pickedPlace?.location || draft?.location), apply: selected => {
        if (draft) {
          setDraftPlace(selected || { name: "", location: null });
          persistSession();
        } else pickedPlace = selected || { name: "", location: null };
      } });
    } else if (action === "new-session" && (!busy || request)) {
      await switchSession(crypto.randomUUID());
      refresh();
    } else if (action === "stop" && request) {
      const hadFocus = control.matches(":focus");
      stopRequest();
      persistSession();
      refresh();
      if (hadFocus) document.querySelector("#assistant-chat textarea")?.focus({ preventScroll: true });
    } else if (action === "restore-session" && (!busy || request) && sessions.has(control.dataset.id)) {
      await switchSession(control.dataset.id);
      openChat();
    } else if (action === "clear-config" && !testing) {
      config = { base: "", model: "", key: "" };
      status = "已清除配置。";
      refresh();
    } else if (action === "example-record") {
      await send("午餐花了 28 元");
    } else if (action === "example-stats") {
      await send("这个月主要花在哪？");
    } else if (action === "cancel" && !busy) {
      draft = null;
      say("已取消草稿。");
      persistSession();
      refresh();
    } else if (action === "detail") {
      const report = chat.find(item => item.kind === "report" && item.id === control.dataset.id);
      if (report) {
        report.selected = report.selected === control.dataset.category ? "" : control.dataset.category;
        persistSession();
        render(false);
      }
    } else if (action === "undo" && !busy) {
      const receipt = chat.find(item => item.kind === "receipt" && item.entry.id === control.dataset.id && !item.undone);
      if (!receipt) return;
      const saved = receipt.entry;
      busy = true;
      try {
        await removeEntry(saved.id, saved.updatedAt);
        receipt.undone = true;
        await reload();
      } catch (error) {
        say(error.message || "撤销失败，请重试。");
      } finally {
        busy = false;
        persistSession();
        if (isOpen()) render(false);
      }
    }
  }

  function submit(event) {
    const form = event.target;
    if (!["assistant-chat", "assistant-draft", "assistant-config"].includes(form.id)) return;
    event.preventDefault();
    if (form.id === "assistant-chat") void send(new FormData(form).get("message"));
    else if (form.id === "assistant-draft") void saveDraft(form);
    else void submitConfig(form, event.submitter?.value || "save");
  }

  function onInput(event) {
    const field = event.target;
    if (field.form?.id === "assistant-chat") input = field.value;
    if (field.form?.id === "assistant-draft" && draft) draft[field.name] = field.value;
  }

  function onKeyDown(event) {
    if (event.target.form?.id !== "assistant-chat" || event.key !== "Enter" || event.shiftKey ||
      event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    if (!busy) event.target.form.requestSubmit();
  }

  return { page, configPage, historyPage, initHistory, clearLocalData, sessionButton, act, submit, onInput, onKeyDown };
}
