import { EXPENSE, INCOME, categoryTotals, entriesInBounds, localToday, money, sortEntries, totals } from "./model.js";
import { putEntry, removeEntry } from "./storage.js";
import { interpretLocally, validateIntent } from "./assistant-model.js";

const paragraph = (escape, value) => '<p class="micro">' + escape(value) + '</p>';
const typeName = type => type === INCOME ? "收入" : "支出";

export function createAssistant({ getData, escape: E, render, reload, isOpen }) {
  let config = { mode: "local", base: "", model: "", key: "", consent: false };
  let status = "", input = "", busy = false, testing = false, context = null, draft = null, saved = null;
  let report = null, reportCategory = "", chat = [];
  const say = (text, user = false) => {
    chat = [...chat.slice(-19), { text, user }];
  };
  const refresh = () => { if (isOpen()) render(); };
  const button = (action, label, style = "outline", disabled = false) => '<button type="button" class="' +
    style + '" data-assistant="' + action + '"' + (disabled ? " disabled" : "") + ">" + E(label) + "</button>";
  const displayMoney = minor => getData().preferences.hideAmountsByDefault ? "••••" : "¥" + money(minor);
  const available = () => getData().categories.filter(category => !category.hidden);

  function configPage() {
    return '<form id="assistant-config" class="card ai-config"><label>识别模式<select name="mode" class="field">' +
      '<option value="local"' + (config.mode === "local" ? " selected" : "") + '>本地体验</option>' +
      '<option value="cloud"' + (config.mode === "cloud" ? " selected" : "") + '>云端补充识别</option></select></label>' +
      '<label>API 地址<input class="field" name="base" type="url" placeholder="https://服务地址/v1" value="' + E(config.base) + '"></label>' +
      '<label>模型 ID<input class="field" name="model" maxlength="150" value="' + E(config.model) + '" placeholder="填服务商提供的模型 ID"></label>' +
      '<label>API Key<input class="field" name="key" type="password" autocomplete="off" maxlength="512" placeholder="' +
      (config.key ? "已设置 · 留空沿用" : "模型需要认证时填写") + '"></label>' +
      '<label class="ai-consent"><input name="consent" type="checkbox"' + (config.consent ? " checked" : "") +
      '>允许把输入及必要上下文发给此模型服务</label>' +
      paragraph(E, "本地模式无需配置。云端测试仅发送示例句，账本和统计结果留在本机。配置仅在当前页面会话中有效。") +
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

  function reportCard() {
    if (!report) return "";
    const data = getData(), rows = entriesInBounds(data.entries, report);
    const amount = totals(rows);
    const type = report.metric === "income" ? INCOME : EXPENSE;
    const ranking = categoryTotals(rows, data.categories, type);
    const leader = ranking[0];
    const tied = ranking.filter(item => item.amountMinor === leader?.amountMinor);
    const matched = ranking.find(item => item.category.id === reportCategory || (!reportCategory && item.category.name === report.category));
    const detail = matched
      ? '<div class="ai-detail"><strong>' + E(matched.category.name) + '明细</strong>' +
        sortEntries(rows.filter(item => item.categoryId === matched.category.id)).slice(0, 20).map(item =>
          '<div class="row"><span>' + E(item.businessDate + " · " + (item.note || matched.category.name)) +
          '</span><span>' + displayMoney(item.amountMinor) + '</span></div>').join("") +
        (rows.filter(item => item.categoryId === matched.category.id).length > 20 ? paragraph(E, "这里展示最近 20 笔。") : "") + '</div>'
      : report.category ? paragraph(E, "这个区间没有该分类明细。") : "";
    return '<section class="card ai-report"><strong>收支统计</strong>' + paragraph(E, report.start + " 至 " + report.end) +
      '<div class="ai-fields"><div>收入 <strong>' + displayMoney(amount.income) + '</strong></div><div>支出 <strong>' +
      displayMoney(amount.expense) + '</strong></div></div>' +
      (!rows.length ? paragraph(E, "这个区间没有记录。") : !leader ? paragraph(E, "这个区间没有" + typeName(type) + "记录。") :
        paragraph(E, typeName(type) + "占比最高：" + tied.map(item => item.category.name).join("、") +
          (tied.length > 1 ? "（并列）" : "") + " · " + leader.percent.toFixed(1) + "%")) +
      ranking.map(item => '<button type="button" class="ai-rank" data-assistant="detail" data-category="' + E(item.category.id) +
        '"><span class="row"><span>' + E(item.category.name) + '</span><span>' + displayMoney(item.amountMinor) + ' · ' +
        item.percent.toFixed(1) + '% ›</span></span><span class="ai-track" aria-hidden="true"><span style="width:' +
        item.percent.toFixed(1) + '%"></span></span></button>').join("") + detail + '</section>';
  }

  function page() {
    return '<div class="ai-chat" aria-live="polite">' +
      (!chat.length ? '<div class="card"><strong>说一句，记账或查账</strong><p class="micro">例如「今天中午吃叉烧饭花了28元」。</p>' +
        '<div class="ai-actions">' + button("example-record", "试一句记账") + button("example-stats", "试查本月收支") + '</div></div>' : "") +
      chat.map(item => '<p class="ai-bubble ' + (item.user ? "ai-user" : "") + '">' + E(item.text) + '</p>').join("") +
      draftCard() + reportCard() +
      (saved ? '<div class="card"><strong>已入账</strong>' + paragraph(E, saved.note + " · " + displayMoney(saved.amountMinor)) +
        button("undo", "撤销入账", "outline", busy) + '</div>' : "") + '</div>' +
      '<form id="assistant-chat" class="ai-composer"><input aria-label="聊天消息" name="message" class="field" maxlength="600" autocomplete="off"' +
      ' placeholder="说一句，记账或查账" value="' + E(input) + '"' + (busy ? " disabled" : "") + '><button class="primary"' +
      (busy ? " disabled" : "") + '>' + (busy && !draft ? "处理中…" : "发送") + '</button></form>';
  }

  async function callModel(settings, action, text = "") {
    let response;
    try {
      response = await fetch("./ai/api", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action, base: settings.base, model: settings.model, key: settings.key,
          ...(action === "interpret" ? { text, today: localToday(), dateContext: context?.bounds,
            pendingText: context?.pendingText || context?.text } : {}) }) });
    } catch {
      throw new Error("无法连接本机调试服务，请运行 npm start。");
    }
    const data = await response.json().catch(() => null);
    if (!response.ok || !data) throw new Error(data?.error || "无法连接本机调试服务，请运行 npm start。");
    return validateIntent(data.intent, action === "test" ? "2026-01-02" : localToday());
  }

  async function submitConfig(form, action) {
    if (testing) return;
    const fields = new FormData(form);
    const base = fields.get("base").trim(), key = fields.get("key").trim();
    const next = { mode: fields.get("mode"), base, model: fields.get("model").trim(),
      key: key || (base === config.base ? config.key : ""), consent: fields.has("consent") };
    if ((action === "test" || next.mode === "cloud") && (!next.base || !next.model || !next.consent)) {
      form.querySelector('[role="status"]').textContent = "请填写 API 地址和模型 ID，并同意发送请求。";
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
    if (draft) {
      say("请先确认或取消上一笔草稿。");
      refresh();
      return;
    }
    report = null;
    reportCategory = "";
    say(text, true);
    input = "";
    busy = true;
    refresh();
    try {
      const today = localToday();
      let intent = interpretLocally(text, today, context, available());
      if (intent?.kind === "partial") {
        context = { kind: "partial", record: intent.record, pendingText: text };
        say("这笔花了多少钱？请回复金额，例如 28 元。");
        return;
      }
      if (intent) intent = validateIntent(intent, today);
      else if (config.mode === "cloud" && config.consent) intent = await callModel(config, "interpret", text);
      else {
        context = null;
        say("本地模式没有识别这句。可以试「今天中午吃叉烧饭花了28元」或「本月收入多少」，也可以在设置开启云端识别。");
        return;
      }
      if (intent.kind === "record") {
        const category = available().find(item => item.name === intent.category && item.type === intent.type);
        draft = { id: crypto.randomUUID(), type: intent.type, amount: (intent.amountMinor / 100).toFixed(2),
          date: intent.date, categoryId: category?.id || "", note: intent.note };
        context = null;
        say(category ? "识别到一笔记录，请核对后入账。" : "识别到一笔记录，请先选分类。");
      } else if (intent.kind === "stats") {
        report = intent;
        context = { bounds: { start: intent.start, end: intent.end } };
      } else {
        context = { kind: "period", text: context?.text || text, pendingText: context?.pendingText || text };
        say(intent.question);
      }
    } catch (error) {
      input = text;
      say(error.message);
    } finally {
      busy = false;
      refresh();
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
      saved = entry;
      draft = null;
      say("已记入本地账本。");
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
    if (action === "clear-config" && !testing) {
      config = { mode: "local", base: "", model: "", key: "", consent: false };
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
      reportCategory = reportCategory === control.dataset.category ? "" : control.dataset.category;
      refresh();
    } else if (action === "undo" && saved && !busy) {
      busy = true;
      try {
        const current = getData().entries.find(item => item.id === saved.id);
        if (current && current.updatedAt !== saved.updatedAt) throw new Error("该账单已修改，请在明细中处理。");
        await removeEntry(saved.id);
        saved = null;
        say("已撤销入账。");
        await reload();
      } catch (error) {
        say(error.message || "撤销失败，请重试。");
      } finally {
        busy = false;
        refresh();
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

  return { page, configPage, act, submit, onInput };
}
