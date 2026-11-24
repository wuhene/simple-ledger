import { createServer } from "node:http";
import { readFile } from "node:fs/promises";

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
};
const systemPrompt = [
  "你负责把中文记账请求转换为一个 JSON 对象，不要执行操作、计算账本或返回 Markdown。",
  '单笔记录: {"kind":"record","type":"EXPENSE 或 INCOME","amount":"十进制元字符串","date":"YYYY-MM-DD","category":"分类名或空字符串","note":"备注"}。',
  '查询: {"kind":"stats","start":"YYYY-MM-DD","end":"YYYY-MM-DD","metric":"overview 或 income 或 expense"}。查分类明细可额外给 category。',
  '欠缺信息或不是以上任务: {"kind":"clarify","question":"不超过100个字的问题"}。',
  "使用用户提供的今天和已有结构化日期上下文。记账没给日期可用今天；缺金额、多个金额或日期有歧义时追问，不能猜。统计日期区间含首尾。",
].join("\n");

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
      if (length > 12000) break;
      chunks.push(chunk);
    }
    if (length > 12000) return reply(res, 413, { error: "请求太长" });
    input = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!input || !["test", "interpret"].includes(input.action) ||
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
  const task = test ? { today: "2026-01-02", text: "今天午餐花了28元" } :
    { today: input.today, text: input.text, dateContext: input.dateContext, pendingText: input.pendingText };
  try {
    const response = await fetch(url, {
      method: "POST", redirect: "error", signal: AbortSignal.timeout(25000),
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
      intent = JSON.parse(typeof content === "string" ? content.trim().replace(/^\x60\x60\x60(?:json)?\s*|\s*\x60\x60\x60$/gi, "") : "");
    } catch {
      return reply(res, 502, { error: "模型未返回有效 JSON，请检查模型兼容性。" });
    }
    return reply(res, 200, { intent });
  } catch (error) {
    return reply(res, 502, { error: error.name === "TimeoutError" ? "模型请求超时，请重试。" : "无法连接模型，请检查 API 地址和网络。" });
  }
}

createServer(async (req, res) => {
  const host = req.headers.host;
  if (host !== "127.0.0.1:" + port && host !== "localhost:" + port) return reply(res, 403, { error: "仅接受本机访问" });
  const path = req.url?.split("?")[0];
  if (path === "/ai/api" && req.method === "POST") {
    if (req.headers.origin !== "http://" + host || req.headers["content-type"] !== "application/json") {
      return reply(res, 403, { error: "请从简账本机 Web 页面调用" });
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
}).listen(port, "127.0.0.1", () => console.log("简账 Web http://127.0.0.1:" + port + "/"));
