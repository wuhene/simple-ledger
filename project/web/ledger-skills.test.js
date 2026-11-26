import assert from "node:assert/strict";
import test from "node:test";
import { groupLedger, programTerms, recallLedger } from "./ledger-skills.js";
import { validateIntent } from "./assistant-model.js";
import { matchScore } from "./ledger-search.js";

test("双路查同一个索引，取并集去重并保留城市硬筛选", async () => {
  const city = [{ type: "城市", value: "北京市" }];
  const tagged = [{ type: "城市", value: "北京" }];
  const entries = [
    { id: "1", note: "王府井烤鸭", clues: tagged },
    { id: "2", note: "王府井家常菜", clues: tagged },
    { id: "3", note: "北京烤鸭但地点未知" },
  ];
  const lookup = async ({ terms = [], filters }) => entries.filter(entry => matchScore(entry, terms, filters) >= 0);
  const local = programTerms("帮我找找王府井的记录");
  assert.deepEqual(local, ["王府井"]);
  assert.deepEqual((await recallLedger(lookup, { filters: city }, ["烤鸭", "北京"], local, true)).map(item => item.id), ["1", "2"]);
  assert.deepEqual((await recallLedger(lookup, { filters: city }, ["没命中"], [], false)).map(item => item.id), ["1", "2"]);
});

test("同笔同人仅记一次，分别统计用餐时段和线索覆盖率", () => {
  const entries = [
    { amountMinor: 12800, clues: [{ type: "同行人", value: "小李" }, { type: "同行人", value: " 小李 " }, { type: "用餐时间", value: "12:30" }] },
    { amountMinor: 5600, clues: [{ type: "同行人", value: "小王" }, { type: "用餐时间", value: "12:15" }] },
    { amountMinor: 1800, clues: [{ type: "同行人", value: "小李" }] },
  ];
  const people = groupLedger(entries, "同行人", "count", 10);
  assert.deepEqual(people.groups[0], { name: "小李", count: 2, amountMinor: 14600 });
  const meals = groupLedger(entries, "用餐时间", "count", 10);
  assert.deepEqual(meals.groups[0], { name: "12:00–12:59", count: 2, amountMinor: 18400 });
  assert.equal(meals.missing, 1);
  const plan = validateIntent({ kind: "stats", category: "餐饮", metric: "expense", groupBy: "同行人" }, "2026-11-26");
  assert.equal(plan.start, "1900-01-01");
  assert.equal(plan.view, "groupRank");
  assert.equal(plan.matchText, false);
});
