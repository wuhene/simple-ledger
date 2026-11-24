import { EXPENSE, INCOME, categoryTotals, entriesInBounds, localToday, money, sortEntries, totals } from "./model.js";
import { putEntry, removeEntry } from "./storage.js";
import { validateIntent } from "./assistant-model.js";

const paragraph = (escape, value) => '<p class="micro">' + escape(value) + '</p>';
const typeName = type => type === INCOME ? "收入" : "支出";

export function createAssistant({ getData, escape: E, render, reload, isOpen }) {
  let config = { base: "", model: "", key: "", consent: false };
  let status = "", input = "", busy = false, testing = false, context = null, draft = null, request = null;
  let chat = [];
  const say = (text, user = false) => {
    chat.push({ kind: "text", text, user });
  };
  const refresh = () => { if (isOpen()) render(); };
  const button = (action, label, style = "outline", disabled = false) => '<button type="button" class="' +
    style + '" data-assistant="' + action + '"' + (disabled ? " disabled" : "") + ">" + E(label) + "</button>";
  const displayMoney = minor => getData().preferences.hideAmountsByDefault ? "••••" : "¥" + money(minor);
  const available = () => getData().categories.filter(category => !category.hidden);
  const configured = () => Boolean(config.base && config.model && config.consent);

  function configPage() {
    return '<form id="assistant-config" class="card ai-config">' +
      '<label>API 地址<input class="field" name="base" type="url" placeholder="https://服务地址/v1" value="' + E(config.base) + '"></label>' +
      '<label>模型 ID<input class="field" name="model" maxlength="150" value="' + E(config.model) + '" placeholder="填服务商提供的模型 ID"></label>' +
      '<label>API Key<input class="field" name="key" type="password" autocomplete="off" maxlength="512" placeholder="' +
      (config.key ? "已设置 · 留空沿用" : "模型需要认证时填写") + '"></label>' +
      '<label class="ai-consent"><input name="consent" type="checkbox"' + (config.consent ? " checked" : "") +
      '>允许把输入及必要上下文发给此模型服务</label>' +
      paragraph(E, "每条消息由所选模型识别。连接测试仅发送示例句，账本和统计结果留在本机。配置在刷新前有效。") +
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

  function createReport(query) {
    const data = getData(), rows = entriesInBounds(data.entries, query);
    const type = query.metric === "income" ? INCOME : EXPENSE;
    const ranking = categoryTotals(rows, data.categories, type);
    const sorted = sortEntries(rows);
    const details = new Map(ranking.map(item => [item.category.id, { count: 0, rows: [] }]));
    for (const row of sorted) {
      const detail = details.get(row.categoryId);
      if (!detail) continue;
      detail.count++;
      if (detail.rows.length < 20) detail.rows.push(row);
    }
    return { kind: "report", id: crypto.randomUUID(), query, amount: totals(rows), type, ranking,
      details, empty: !rows.length, selected: ranking.find(item => item.category.name === query.category)?.category.id || "" };
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

  function chatItem(item) {
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

  function page() {
    return '<div class="ai-chat" aria-live="polite">' +
      (!configured() ? '<div class="card"><strong>先连接一个模型</strong><p class="micro">在设置中填写 API 地址和模型 ID，即可用 AI 记账或查账。</p></div>' :
        !chat.length ? '<div class="card"><strong>说一句，记账或查账</strong><p class="micro">例如「今天中午吃叉烧饭花了28元」。</p>' +
        '<div class="ai-actions">' + button("example-record", "试一句记账") + button("example-stats", "试查本月收支") + '</div></div>' : "") +
      chat.map(chatItem).join("") + draftCard() + '</div>' +
      '<form id="assistant-chat" class="ai-composer"><input aria-label="聊天消息" name="message" class="field" maxlength="600" autocomplete="off"' +
      ' placeholder="说一句，记账或查账" value="' + E(input) + '"' + (busy || !configured() ? " disabled" : "") + '><button class="primary"' +
      (busy || !configured() ? " disabled" : "") + '>' + (busy && !draft ? "处理中…" : "发送") + '</button></form>';
  }

  async function callModel(settings, action, text = "", signal) {
    const today = action === "test" ? "2026-01-02" : localToday();
    let response;
    try {
      response = await fetch("./ai/api", { method: "POST", signal, headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action, base: settings.base, model: settings.model, key: settings.key,
          ...(action === "interpret" ? { text, today, lastQuery: context?.lastQuery, pending: context?.pending } : {}) }) });
    } catch {
      throw new Error("无法连接本机调试服务，请运行 npm start。");
    }
    const data = await response.json().catch(() => null);
    if (!response.ok || !data) throw new Error(data?.error || "无法连接本机调试服务，请运行 npm start。");
    return validateIntent(data.intent, today);
  }

  async function submitConfig(form, action) {
    if (testing) return;
    const fields = new FormData(form);
    const base = fields.get("base").trim(), key = fields.get("key").trim();
    const next = { base, model: fields.get("model").trim(),
      key: key || (base === config.base ? config.key : ""), consent: fields.has("consent") };
    if (action === "test" && (!next.base || !next.model || !next.consent)) {
      form.querySelector('[role="status"]').textContent = "请填写 API 地址和模型 ID，并同意发送请求。";
      return;
    }
    config = next;
    if (action !== "test") {
      status = configured() ? "设置已保存。" : "设置已保存。填好 API 地址和模型 ID 并同意发送后即可聊天。";
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
    const currentRequest = new AbortController();
    request = currentRequest;
    busy = true;
    refresh();
    try {
      const intent = await callModel(config, "interpret", text, currentRequest.signal);
      if (request !== currentRequest) return;
      if (intent.kind === "record") {
        const category = available().find(item => item.name === intent.category && item.type === intent.type);
        draft = { id: crypto.randomUUID(), type: intent.type, amount: (intent.amountMinor / 100).toFixed(2),
          date: intent.date, categoryId: category?.id || "", note: intent.note };
        context = { lastQuery: context?.lastQuery };
        say(category ? "识别到一笔记录，请核对后入账。" : "识别到一笔记录，请先选分类。");
      } else if (intent.kind === "stats") {
        chat.push(createReport(intent));
        context = { lastQuery: { start: intent.start, end: intent.end, metric: intent.metric, category: intent.category } };
      } else if (intent.kind === "clarify") {
        const pending = { original: context?.pending?.original || text, latest: text, question: intent.question };
        context = { lastQuery: context?.lastQuery, pending };
        say(intent.question);
      } else {
        context = { lastQuery: context?.lastQuery };
        say(intent.text);
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
      refresh();
    }
  }

  async function act(control) {
    const action = control.dataset.assistant;
    if (action === "new-session" && (!busy || request)) {
      if (draft && !window.confirm("开启新会话并放弃这笔未入账草稿？")) return;
      request?.abort();
      request = null;
      busy = false;
      input = "";
      context = null;
      draft = null;
      chat = [];
      refresh();
    } else if (action === "clear-config" && !testing) {
      config = { base: "", model: "", key: "", consent: false };
      status = "已清除配置。";
      refresh();
    } else if (action === "example-record") {
      await send("今天中午吃叉烧饭花了28元");
    } else if (action === "example-stats") {
      await send("统计本月收支，哪个支出占比最多？");
    } else if (action === "cancel" && !busy) {
      draft = null;
      say("已取消草稿。");
      refresh();
    } else if (action === "detail") {
      const report = chat.find(item => item.kind === "report" && item.id === control.dataset.id);
      if (report) {
        report.selected = report.selected === control.dataset.category ? "" : control.dataset.category;
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

  return { page, configPage, sessionButton, act, submit, onInput };
}
