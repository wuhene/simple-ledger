import { EXPENSE, INCOME, categoryTotals, dayCount, entriesInBounds, localToday, money, shiftDate, sortEntries, totals } from "./model.js";
import { clearLedger, loadAssistantHistory, putEntry, putMeta, removeEntry, saveAssistantSession } from "./storage.js";
import { validateIntent } from "./assistant-model.js";

const paragraph = (escape, value) => '<p class="micro">' + escape(value) + '</p>';
const typeName = type => type === INCOME ? "收入" : "支出";

export function createAssistant({ getData, escape: E, render, reload, isOpen, openChat }) {
  let config = { base: "", model: "", key: "" };
  let status = "", input = "", busy = false, testing = false, context = null, draft = null, request = null;
  let chat = [], previousInput = "";
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
    const firstMessage = chat.find(item => item.kind === "text" && item.user);
    const session = structuredClone({ id: activeId, chat, context, draft, input, previousInput,
      title: [...(firstMessage?.text || "新会话")].slice(0, 26).join(""),
      updatedAt: new Date().toISOString() });
    sessions.set(activeId, session);
    return saveHistory(saveAssistantSession(session));
  }

  async function switchSession(id) {
    if (id === activeId) return;
    if (request) {
      request.abort();
      request = null;
      busy = false;
      say("本次请求已停止。");
    }
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
      '<div class="ai-actions"><button class="primary"' + (busy ? " disabled" : "") + '>确认入账</button>' +
      button("cancel", "取消草稿", "outline", busy) + '</div></form>';
  }

  function analysisSnapshot(query) {
    const { entries, categories } = getData();
    const type = query.metric === "income" ? INCOME : EXPENSE;
    const category = query.category ? categories.find(item => item.name === query.category && item.type === type) : null;
    const scopedRows = bounds => entriesInBounds(entries, bounds).filter(item =>
      (query.metric === "overview" && !query.category || item.type === type) &&
      (!query.category || item.categoryId === category?.id));
    const rows = scopedRows(query);
    const { income, expense } = totals(rows);
    const days = dayCount(query);
    const referenceDays = days === 1 ? 7 : days;
    const reference = query.comparison || (query.kind === "insight" || query.view === "comparison"
      ? { start: shiftDate(query.start, -referenceDays), end: shiftDate(query.start, -1) } : null);
    const prior = reference ? scopedRows(reference) : [];
    const previous = totals(prior);
    const baseline = reference ? { ...reference, days: dayCount(reference), count: prior.length,
      recordedDays: new Set(prior.map(item => item.businessDate)).size, incomeMinor: previous.income, expenseMinor: previous.expense } : null;
    const names = new Map(categories.map(item => [item.id, item.name]));
    const ranking = query.category ? [] : categoryTotals(rows, categories, type).slice(0, 8)
      .map(item => ({ name: item.category.name, amountMinor: item.amountMinor, percent: item.percent }));
    const facts = { start: query.start, end: query.end, days, count: rows.length, category: query.category,
      metric: query.metric, view: query.view, incomeMinor: income, expenseMinor: expense,
      largestMinor: rows.reduce((largest, item) => Math.max(largest, item.amountMinor), 0), baseline,
      top: ranking.slice(0, 5).map(item => ({ amountMinor: item.amountMinor, percent: Math.round(item.percent * 10) / 10 })) };
    return {
      facts, entries: sortEntries(rows).slice(0, 20).map(item => ({
        date: item.businessDate, note: item.note, amountMinor: item.amountMinor,
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
    else if (facts.metric === "overview" && !facts.category) {
      text = "这段时间记了 " + displayMoney(facts.incomeMinor) + " 收入、" + displayMoney(facts.expenseMinor) + " 支出。";
    } else {
      text = label + "共 " + facts.count + " 笔，合计 " + displayMoney(facts.metric === "income" ? facts.incomeMinor : facts.expenseMinor) + "。";
    }
    if (query.kind === "insight" && facts.baseline && facts.baseline.recordedDays < Math.min(3, facts.baseline.days)) {
      text += " 对比期记录偏少，暂时不好判断是否比平时高。";
    }
    blocks.push({ type: "paragraph", text });
    if (facts.count && (query.view === "entries" || (query.category && !query.view))) blocks.push({ type: "entries" });
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

  function richAnswer(item) {
    const snapshot = item.snapshot;
    const facts = snapshot?.facts;
    const panel = (label, body) => '<section class="ai-data-block"><strong>' + E(label) + '</strong>' + body + '</section>';
    const row = (label, value) => '<div class="row"><span>' + E(label) + '</span><strong>' + E(value) + '</strong></div>';
    const period = facts ? paragraph(E, facts.start + " 至 " + facts.end) : "";
    const seen = new Set();
    const dataTypes = ["totals", "categories", "entries", "comparison"];
    const formatText = text => E(text.replace(/\{\{category([1-5])\}\}/g, (_, number) =>
      snapshot?.ranking[Number(number) - 1]?.name || "该分类"));
    const blocks = facts && getData().preferences.hideAmountsByDefault
      ? [{ type: "paragraph", text: "账本金额已隐藏。此处展示查询记录。" }, ...item.blocks.filter(block => dataTypes.includes(block.type))]
      : item.blocks;
    const parts = blocks.map(block => {
      if (block.type === "heading") return '<h3>' + formatText(block.text) + '</h3>';
      if (block.type === "paragraph") return '<p>' + formatText(block.text) + '</p>';
      if (block.type === "bullets") return '<ul>' + block.items.map(text => '<li>' + formatText(text) + '</li>').join("") + '</ul>';
      if (!snapshot || seen.has(block.type)) return "";
      seen.add(block.type);
      if (block.type === "entries" && facts.count) {
        const rows = snapshot.entries.map(entry => row(entry.date + " · " + (entry.note || entry.category),
          (entry.type === INCOME ? "+" : "−") + displayMoney(entry.amountMinor)));
        const more = rows.length > 5 ? '<details><summary>展开其余 ' + (rows.length - 5) + ' 笔</summary>' + rows.slice(5).join("") + '</details>' : "";
        return panel((facts.category || "相关") + "明细 · " + facts.count + " 笔", period + rows.slice(0, 5).join("") + more +
          (facts.count > rows.length ? paragraph(E, "这里显示最近 " + rows.length + " 笔。") : ""));
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
      if (block.type === "comparison" && facts.baseline?.count) {
        const isIncome = facts.metric === "income";
        const metric = isIncome ? "incomeMinor" : "expenseMinor";
        return panel(isIncome ? "日均收入对比" : "日均支出对比", period +
          row("本期", displayMoney(Math.round(facts[metric] / facts.days))) +
          row(facts.baseline.start + " 至 " + facts.baseline.end, displayMoney(Math.round(facts.baseline[metric] / facts.baseline.days))) +
          paragraph(E, "对比期 " + facts.baseline.recordedDays + " 天有记录"));
      }
      return "";
    }).join("");
    return '<div class="ai-rich-answer">' + (parts || '<p>这次没有可展示的内容。</p>') + '</div>';
  }

  function chatItem(item) {
    if (item.kind === "answer") return richAnswer(item);
    if (item.kind === "report") return reportCard(item);
    if (item.kind === "receipt") {
      return '<section class="card ai-receipt"><strong>' + (item.undone ? "已撤销" : "已入账") +
        '</strong>' + paragraph(E, item.entry.note + " · " + displayMoney(item.entry.amountMinor)) +
        (item.undone ? "" : '<button type="button" class="outline" data-assistant="undo" data-id="' +
          E(item.entry.id) + '"' + (busy ? " disabled" : "") + '>撤销入账</button>') + '</section>';
    }
    return '<p class="ai-bubble ' + (item.user ? "ai-user" : "") + '">' + E(item.text) + '</p>';
  }

  function sessionButton() {
    return button("new-session", "新会话", "ai-entry", busy && !request);
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
    return '<div class="assistant-thread" role="region" aria-label="聊天记录" tabindex="0"><div class="ai-chat" aria-live="polite">' +
      (!configured() ? '<div class="card"><strong>先连接一个模型</strong><p class="micro">在设置中填写 API 地址和模型 ID，即可用 AI 记账或查账。</p></div>' :
        !chat.length ? '<div class="card"><strong>说一句，记账或查账</strong><p class="micro">例如「今天中午吃叉烧饭花了28元」。</p>' +
        '<div class="ai-actions">' + button("example-record", "试一句记账") + button("example-stats", "试查本月收支") + '</div></div>' : "") +
      (historyError ? '<p role="status" class="error">' + E(historyError) + '</p>' : "") +
      chat.map(chatItem).join("") + draftCard() + '</div></div>' +
      '<div class="assistant-dock"><button type="button" class="chat-latest" data-action="assistant-latest" hidden>回到最新 ↓</button>' +
      '<form id="assistant-chat" class="ai-composer"><input aria-label="聊天消息" name="message" class="field" maxlength="600" autocomplete="off"' +
      ' placeholder="说一句，记账或查账" value="' + E(input) + '"' + (busy || !configured() ? " disabled" : "") + '><button class="primary"' +
      (busy || !configured() ? " disabled" : "") + '>' + (busy && !draft ? "处理中…" : "发送") + '</button></form></div>';
  }

  async function callModel(settings, action, text = "", signal, facts) {
    const today = action === "test" ? "2026-01-02" : localToday();
    let response;
    try {
      response = await fetch("./ai/api", { method: "POST", signal, headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action, base: settings.base, model: settings.model, key: settings.key,
          ...(action === "interpret" ? { text, today, lastQuery: context?.lastQuery, pending: context?.pending, previousInput } :
            action === "analyze" ? { text, facts } : {}) }) });
    } catch {
      throw new Error("无法连接本机调试服务，请运行 npm start。");
    }
    const data = await response.json().catch(() => null);
    if (!response.ok || !data) throw new Error(data?.error || "无法连接本机调试服务，请运行 npm start。");
    const result = validateIntent(data.intent, today);
    if (action === "analyze" && !["answer", "message"].includes(result.kind)) throw new Error("模型未返回分析");
    return result;
  }

  async function submitConfig(form, action) {
    if (testing) return;
    const fields = new FormData(form);
    const base = fields.get("base").trim(), key = fields.get("key").trim();
    const next = { base, model: fields.get("model").trim(),
      key: key || (base === config.base ? config.key : "") };
    if (!next.base || !next.model) {
      form.querySelector('[role="status"]').textContent = "请填写 API 地址和模型 ID。";
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
    const currentRequest = new AbortController();
    request = currentRequest;
    busy = true;
    persistSession();
    refresh();
    try {
      const intent = await callModel(settings, "interpret", text, currentRequest.signal);
      if (request !== currentRequest) return;
      previousInput = text.slice(0, 300);
      if (intent.kind === "record") {
        const category = available().find(item => item.name === intent.category && item.type === intent.type);
        draft = { id: crypto.randomUUID(), type: intent.type, amount: (intent.amountMinor / 100).toFixed(2),
          date: intent.date, categoryId: category?.id || "", note: intent.note };
        context = { lastQuery: context?.lastQuery };
        say(intent.text || (category ? "识别到一笔记录，请核对后入账。" : "识别到一笔记录，请先选分类。"));
      } else if (intent.kind === "stats" || intent.kind === "insight") {
        const snapshot = analysisSnapshot(intent);
        const hidden = getData().preferences.hideAmountsByDefault;
        let blocks = fallbackAnswer(intent, snapshot, hidden);
        if (config === settings && !hidden) {
          try {
            const answer = await callModel(settings, "analyze", text, currentRequest.signal, snapshot.facts);
            blocks = answer.kind === "answer" ? answer.blocks :
              [{ type: "paragraph", text: answer.text }, ...blocks.filter(block => block.type !== "paragraph")];
          } catch {
            // The local answer still works when the analysis request fails.
          }
          if (request !== currentRequest) return;
        }
        chat.push({ kind: "answer", blocks, snapshot });
        context = { lastQuery: { start: intent.start, end: intent.end, metric: intent.metric, category: intent.category, view: intent.view } };
      } else if (intent.kind === "clarify") {
        const pending = { original: context?.pending?.original || text, latest: text, question: intent.question };
        context = { lastQuery: context?.lastQuery, pending };
        say(intent.question);
      } else {
        context = { lastQuery: context?.lastQuery };
        if (intent.kind === "answer") chat.push({ kind: "answer", blocks: intent.blocks });
        else say(intent.text);
      }
    } catch (error) {
      if (request === currentRequest) {
        input = text;
        say(error.message);
      }
    } finally {
      if (request === currentRequest) {
        request = null;
        busy = false;
        persistSession();
        refresh();
      }
    }
  }

  async function saveDraft(form) {
    if (busy || !draft) return;
    const fields = new FormData(form);
    for (const name of ["amount", "note", "date", "categoryId"]) draft[name] = fields.get(name);
    busy = true;
    try {
      const record = validateIntent({ ...draft, category: "", kind: "record" }, localToday());
      if (!available().some(item => item.id === draft.categoryId && item.type === draft.type)) {
        throw new Error("请选择当前收支类型下的有效分类。");
      }
      const now = new Date().toISOString();
      const entry = { id: draft.id, type: record.type, amountMinor: record.amountMinor,
        businessDate: record.date, categoryId: draft.categoryId, note: record.note, createdAt: now, updatedAt: now };
      await putEntry(entry);
      draft = null;
      chat.push({ kind: "receipt", entry, undone: false });
      await reload();
    } catch (error) {
      say(error.message || "账单保存失败，请重试。");
    } finally {
      busy = false;
      persistSession();
      refresh();
    }
  }

  async function act(control) {
    const action = control.dataset.assistant;
    if (action === "new-session" && (!busy || request)) {
      await switchSession(crypto.randomUUID());
      refresh();
    } else if (action === "restore-session" && (!busy || request) && sessions.has(control.dataset.id)) {
      await switchSession(control.dataset.id);
      openChat();
    } else if (action === "clear-config" && !testing) {
      config = { base: "", model: "", key: "" };
      status = "已清除配置。";
      refresh();
    } else if (action === "example-record") {
      await send("今天中午吃叉烧饭花了28元");
    } else if (action === "example-stats") {
      await send("统计本月收支，哪个支出占比最多？");
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
        const current = getData().entries.find(item => item.id === saved.id);
        if (current && current.updatedAt !== saved.updatedAt) throw new Error("该账单已修改，请在明细中处理。");
        await removeEntry(saved.id);
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

  return { page, configPage, historyPage, initHistory, clearLocalData, sessionButton, act, submit, onInput };
}
