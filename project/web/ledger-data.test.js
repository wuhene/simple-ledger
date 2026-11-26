import { test } from "node:test";
import assert from "node:assert/strict";
import { unzipSync, zipSync } from "./vendor/fflate.js";
import { packLedger, unpackLedger } from "./ledger-backup.js";
import { applyEntry, rollupAchievements, summarizeRollups } from "./ledger-rollups.js";
import { cleanClues, entryTokens, matchScore, queryTokens } from "./ledger-search.js";
import { EXPENSE, SCHEMA_VERSION, initialCategories } from "./model.js";
import { cleanConversationTurns, cleanResultReferences, isResultDateFollowUp, requestedRank, validateIntent } from "./assistant-model.js";

const first = {
  id: "one", categoryId: "expense-0", type: EXPENSE, businessDate: "2019-02-01", amountMinor: 8800,
  note: "聚餐", sourceText: "在北京王府井跟小李吃四季民福烤鸭",
  clues: [{ type: "城市", value: "北京市" }, { type: "同行人", value: "小李" }, { type: "场合", value: "生日" }],
  createdAt: "2019-02-01T00:00:00.000Z", updatedAt: "2019-02-01T00:00:00.000Z",
};
const second = { ...first, id: "two", businessDate: "2023-03-01", note: "北京烤鸭", clues: [], sourceText: "" };

test("金额排行支持未指定日期，区分前十笔、前十个月和对结果追问年份", () => {
  assert.equal(requestedRank("餐饮的前10项"), 10);
  assert.equal(requestedRank("列出前十笔餐饮"), 10);
  assert.equal(requestedRank("前10个月的餐饮"), 0);
  assert.equal(isResultDateFollowUp("你没有给年份啊，我哪知道哪一年的"), true);
  assert.equal(isResultDateFollowUp("刚才没写年份，改看2025年"), false);
  const intent = validateIntent({ kind: "stats", metric: "expense", category: "餐饮", order: "amount-desc", limit: 10 }, "2026-11-25");
  assert.deepEqual([intent.start, intent.end, intent.view, intent.limit], ["1900-01-01", "2026-11-25", "entries", 10]);
});

test("上下文保留文字、卡片引用和账务状态，去掉逐笔流水字段", () => {
  const turns = cleanConversationTurns([
    { role: "user", text: "看上个月餐饮" }, { role: "assistant", resultId: "r2", entries: [first] },
    { role: "assistant", recordState: "saved", date: first.businessDate, type: EXPENSE, note: first.note },
  ]);
  assert.deepEqual(turns, [
    { role: "user", text: "看上个月餐饮" }, { role: "assistant", resultId: "r2" },
    { role: "assistant", recordState: "saved", date: first.businessDate, type: EXPENSE },
  ]);
  const refs = cleanResultReferences([{ id: "r2", start: first.businessDate, end: first.businessDate,
    blocks: ["totals", "entries"], dates: [first.businessDate], expenseMinor: 8800, entries: [first], location: { latitude: 39.9 } }]);
  assert.equal(refs[0].expenseMinor, 8800);
  assert.deepEqual(refs[0].dates, [first.businessDate]);
  assert.equal("entries" in refs[0] || "location" in refs[0], false);
  assert.equal(validateIntent({ kind: "result-follow-up", resultId: "r2", view: "entries" }).resultId, "r2");
});

test("短词、原话及任意自定义线索可检索，菜名不等同于消费城市", () => {
  const filters = cleanClues([{ type: "城市", value: "北京" }, { type: "场合", value: "生日" }]);
  const terms = ["四季民福", "烤鸭"];
  assert.ok(matchScore(first, terms, filters) > 0);
  assert.ok(queryTokens(terms, filters).every(token => entryTokens(first).includes(token)));
  assert.ok(matchScore(second, ["烤鸭"], []) > 0);
  assert.equal(matchScore(second, ["烤鸭"], filters.slice(0, 1)), -1);
  assert.equal(matchScore({ note: "烤乳鸭" }, ["烤鸭"], []), -1);
});

test("跨月补记和撤销后月汇总、记账天数与金额一致", () => {
  const months = new Map(), moved = { ...first, businessDate: "2023-02-28" };
  applyEntry(months, first, 1);
  applyEntry(months, second, 1);
  applyEntry(months, first, -1);
  applyEntry(months, moved, 1);
  assert.deepEqual(summarizeRollups(months.values()), { income: 0, expense: 17600, balance: -17600 });
  assert.deepEqual(rollupAchievements(months.values(), "2023-03-01"), { days: 2, count: 2, streak: 2 });
  applyEntry(months, second, -1);
  assert.deepEqual(rollupAchievements(months.values(), "2023-03-01"), { days: 1, count: 1, streak: 1 });
});

test("分月 ZIP 往返保存线索与定位，拒绝损坏分片并兼容旧 JSON", async () => {
  const location = { latitude: 39.9, longitude: 116.4, accuracy: 35, capturedAt: "2023-03-01T00:00:00.000Z" };
  const ledger = { schemaVersion: SCHEMA_VERSION, categories: initialCategories(), budgets: [], asset: null,
    preferences: { hideAmountsByDefault: false }, entries: [first, { ...second, location }] };
  const zip = await packLedger(ledger), files = unzipSync(zip);
  assert.deepEqual(Object.keys(files).sort(), ["entries/2019-02.jsonl", "entries/2023-03.jsonl", "ledger.json", "manifest.json"]);
  assert.deepEqual(await unpackLedger(zip), ledger);
  files["entries/2019-02.jsonl"][0] ^= 1;
  await assert.rejects(unpackLedger(zipSync(files)), /分片校验失败/);
  const oldLedger = { ...ledger, entries: [{
    id: "old", type: EXPENSE, categoryId: "expense-0", amountMinor: 900, note: "旧账",
    businessDate: "2019-02-01", createdAt: first.createdAt, updatedAt: first.updatedAt,
  }] };
  assert.deepEqual(await unpackLedger(new TextEncoder().encode(JSON.stringify(oldLedger))), oldLedger);
});
