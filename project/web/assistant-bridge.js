import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { cleanClues, cleanTerms } from "./ledger-search.js";
import { cleanConversationTurns, cleanResultReferences, rankLimit } from "./assistant-model.js";

const port = Number(process.env.LEDGER_WEB_PORT || 4173);
const assets = {
  "/": ["index.html", "text/html"],
  "/index.html": ["index.html", "text/html"],
  "/styles.css": ["styles.css", "text/css"],
  "/app.js": ["app.js", "text/javascript"],
  "/model.js": ["model.js", "text/javascript"],
  "/storage.js": ["storage.js", "text/javascript"],
  "/assistant.js": ["assistant.js", "text/javascript"],
  "/assistant-model.js": ["assistant-model.js", "text/javascript"],
  "/category-icons.js": ["category-icons.js", "text/javascript"],
  "/ledger-search.js": ["ledger-search.js", "text/javascript"],
  "/ledger-skills.js": ["ledger-skills.js", "text/javascript"],
  "/ledger-rollups.js": ["ledger-rollups.js", "text/javascript"],
  "/ledger-backup.js": ["ledger-backup.js", "text/javascript"],
  "/vendor/fflate.js": ["vendor/fflate.js", "text/javascript"],
};
const systemPrompt = (await Promise.all([
  readFile(new URL("./assistant-prompt.txt", import.meta.url), "utf8"),
  readFile(new URL("../shared/assets/ledger-skill-catalog.txt", import.meta.url), "utf8"),
])).map(part => part.trim()).join("\n\n");

function reply(res, status, body, type = "application/json") {
  res.writeHead(status, { "Content-Type": type + "; charset=utf-8", "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff", "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; frame-ancestors 'none'" });
  res.end(type === "application/json" ? JSON.stringify(body) : body);
}

async function readJson(stream, maxLength) {
  let body = "";
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      body += decoder.decode(value, { stream: true });
      if (body.length > maxLength) throw new Error("响应过大");
    }
    return JSON.parse(body + decoder.decode());
  } finally {
    await reader.cancel().catch(() => {});
  }
}

function endpoint(base) {
  const url = new URL(base);
  const loopback = ["127.0.0.1", "localhost", "[::1]"].includes(url.hostname);
  if ((url.protocol !== "https:" && !(loopback && url.protocol === "http:")) ||
    url.username || url.password || url.search || url.hash) throw new Error("API 地址无效");
  url.pathname = url.pathname.replace(/\/+$/, "") + "/chat/completions";
  return url;
}

async function proxy(req, res) {
  let input;
  try {
    const chunks = [];
    let length = 0;
    for await (const chunk of req) {
      length += chunk.length;
      if (length > 40000) break;
      chunks.push(chunk);
    }
    if (length > 40000) return reply(res, 413, { error: "请求太长" });
    input = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!input || !["test", "interpret", "analyze"].includes(input.action) ||
      typeof input.base !== "string" || typeof input.model !== "string" || !input.model.trim() ||
      typeof input.key !== "string" || /[\r\n]/.test(input.key)) throw new Error();
  } catch {
    return reply(res, 400, { error: "请求格式不正确" });
  }
  let url;
  try {
    url = endpoint(input.base);
  } catch {
    return reply(res, 400, { error: "请输入 HTTPS API 地址，本机模型可用 localhost HTTP。" });
  }
  const test = input.action === "test";
  if (!test && (typeof input.text !== "string" || !input.text.trim() || input.text.length > 600)) {
    return reply(res, 400, { error: "消息内容无效" });
  }
  const shortText = (value, limit) => typeof value === "string" ? value.slice(0, limit) : undefined;
  const date = value => typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : undefined;
  const previous = input.lastQuery;
  const result = input.lastResult;
  const turns = cleanConversationTurns(input.recentTurns);
  const refs = cleanResultReferences(input.resultRefs);
  const pending = input.pending;
  const analysis = input.action === "analyze";
  let task = test ? { today: "2026-01-02", text: "今天午餐花了28元" } : {
    today: date(input.today),
    text: input.text,
    previousInput: shortText(input.previousInput, 300) || undefined,
    recentTurns: turns.length ? turns : undefined,
    resultRefs: refs.length ? refs : undefined,
    lastResult: result && date(result.start) && date(result.end) ? {
      question: shortText(result.question, 300), start: date(result.start), end: date(result.end),
      category: shortText(result.category, 24), view: "entries",
      ...(result.order === "amount-desc" ? { order: result.order, limit: rankLimit(result.limit) || 10 } : {}),
    } : undefined,
    lastQuery: previous && { start: date(previous.start), end: date(previous.end),
      metric: shortText(previous.metric, 8), category: shortText(previous.category, 24), view: shortText(previous.view, 12) || undefined,
      groupBy: shortText(previous.groupBy, 24) || undefined,
      groupOrder: ["count", "amount"].includes(previous.groupOrder) ? previous.groupOrder : undefined,
      matchText: previous.matchText === true,
      ...(previous.order === "amount-desc" ? { order: previous.order, limit: rankLimit(previous.limit) || 10 } : {}),
      ...(previous.terms?.length ? { terms: cleanTerms(previous.terms) } : {}),
      ...(previous.filters?.length ? { filters: cleanClues(previous.filters) } : {}) },
    pending: pending && { original: shortText(pending.original, 600), latest: shortText(pending.latest, 600), question: shortText(pending.question, 200) },
  };
  if (analysis) {
    const facts = input.facts || {};
    const dateKeys = ["start", "end"];
    const numberKeys = ["days", "count", "incomeMinor", "expenseMinor", "largestMinor"];
    if (dateKeys.some(key => !date(facts[key])) ||
      numberKeys.some(key => !Number.isSafeInteger(facts[key]) || facts[key] < 0) ||
      !facts.days || !["overview", "income", "expense"].includes(facts.metric)) {
      return reply(res, 400, { error: "支出汇总格式无效" });
    }
    const baseline = facts.baseline;
    const baselineKeys = ["days", "count", "recordedDays", "incomeMinor", "expenseMinor"];
    const comparison = baseline && date(baseline.start) && date(baseline.end) &&
      baselineKeys.every(key => Number.isSafeInteger(baseline[key]) && baseline[key] >= 0) && baseline.days > 0
      ? Object.fromEntries(["start", "end", ...baselineKeys].map(key => [key, baseline[key]])) : undefined;
    const group = facts.groupRank;
    const groupRank = group && typeof group.dimension === "string" && ["covered", "missing"].every(key =>
      Number.isSafeInteger(group[key]) && group[key] >= 0) ? {
        dimension: shortText(group.dimension, 24), covered: group.covered, missing: group.missing,
        groups: Array.isArray(group.groups) ? group.groups.slice(0, 5).filter(item =>
          ["count", "amountMinor"].every(key => Number.isSafeInteger(item?.[key]) && item[key] >= 0))
          .map(({ count, amountMinor }) => ({ count, amountMinor })) : [],
      } : undefined;
    task = { task: "analyze", text: input.text, recentTurns: turns.length ? turns : undefined,
      resultRefs: refs.length ? refs : undefined, facts: {
      ...Object.fromEntries([...dateKeys, ...numberKeys].map(key => [key, facts[key]])),
      metric: facts.metric, category: shortText(facts.category, 24), view: shortText(facts.view, 12), baseline: comparison,
      groupRank,
      top: Array.isArray(facts.top) ? facts.top.slice(0, 5).filter(item =>
        Number.isSafeInteger(item?.amountMinor) && item.amountMinor >= 0 &&
        Number.isFinite(item.percent) && item.percent >= 0 && item.percent <= 100)
        .map(({ amountMinor, percent }) => ({ amountMinor, percent })) : [],
    } };
  }
  const connection = new AbortController();
  res.on("close", () => connection.abort());
  try {
    const response = await fetch(url, {
      method: "POST", redirect: "error", signal: AbortSignal.any([connection.signal, AbortSignal.timeout(25000)]),
      headers: { "Content-Type": "application/json", ...(input.key ? { Authorization: "Bearer " + input.key } : {}) },
      body: JSON.stringify({ model: input.model, stream: false,
        messages: [{ role: "system", content: systemPrompt }, { role: "user", content: JSON.stringify(task) }] }),
    });
    if (!response.ok) {
      await response.body?.cancel();
      const error = { 401: "API Key 验证失败", 403: "没有访问模型的权限", 404: "找不到 API 地址或所选模型", 429: "请求受限或额度不足" }[response.status];
      return reply(res, 502, { error: error || "模型服务请求失败（HTTP " + response.status + "）" });
    }
    let intent;
    try {
      const data = await readJson(response.body, 64000);
      const content = data?.choices?.[0]?.message?.content;
      const clean = typeof content === "string" ? content.trim().replace(/^\x60\x60\x60(?:json)?\s*|\s*\x60\x60\x60$/gi, "") : "";
      try {
        intent = JSON.parse(clean);
      } catch (error) {
        if (test || !clean || clean.startsWith("{") || clean.startsWith("[")) throw error;
        intent = { kind: "message", text: [...clean].slice(0, 600).join("") };
      }
    } catch {
      return reply(res, 502, { error: "模型未返回有效 JSON，请检查模型兼容性。" });
    }
    return reply(res, 200, { intent });
  } catch (error) {
    if (connection.signal.aborted) return;
    return reply(res, 502, { error: error.name === "TimeoutError" ? "模型请求超时，请重试。" : "无法连接模型，请检查 API 地址和网络。" });
  }
}

createServer(async (req, res) => {
  const host = req.headers.host;
  if (host !== "127.0.0.1:" + port && host !== "localhost:" + port) return reply(res, 403, { error: "仅接受本机访问" });
  const path = req.url?.split("?")[0];
  if (path === "/ai/api" && req.method === "POST") {
    if (req.headers.origin !== "http://" + host || req.headers["content-type"] !== "application/json") {
      return reply(res, 403, { error: "请从爱记账本机 Web 页面调用" });
    }
    return proxy(req, res);
  }
  if (req.method !== "GET" || !Object.hasOwn(assets, path)) return reply(res, 404, { error: "页面不存在" });
  try {
    const [file, type] = assets[path];
    reply(res, 200, await readFile(new URL("./" + file, import.meta.url)), type);
  } catch {
    reply(res, 500, { error: "无法读取页面" });
  }
}).listen(port, "127.0.0.1", () => console.log("爱记账 Web http://127.0.0.1:" + port + "/"));
