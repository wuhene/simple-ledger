import assert from "node:assert/strict";
import test from "node:test";
import { entryTokens, latitudeWindow, matchScore, noteWithPlace, withinDistance } from "./ledger-search.js";

const point = (latitude, longitude) => ({ latitude, longitude, accuracy: 30, capturedAt: "2026-11-26T04:00:00Z" });

test("消费地点与手机入账地点冲突时按各自的来源检索", () => {
  const entry = {
    note: "四季民福 · 王府井",
    clues: [{ type: "城市", value: "北京市" }, { type: "地点", value: "王府井" }, { type: "入账城市", value: "虚构市" }],
    location: { ...point(31.23, 121.47), city: "上海市", district: "黄浦区" },
  };
  assert.ok(entryTokens(entry).includes("黄浦"));
  assert.ok(matchScore(entry, [], [{ type: "城市", value: "北京" }]) >= 0);
  assert.equal(matchScore(entry, [], [{ type: "城市", value: "上海" }]), -1);
  assert.ok(matchScore(entry, [], [{ type: "入账城市", value: "上海" }]) >= 0);
  assert.equal(matchScore(entry, [], [{ type: "入账城市", value: "虚构" }]), -1);
});

test("半径筛选保留异经线近点，排除跨城和无坐标记录", () => {
  const center = point(0, 179.995);
  assert.equal(withinDistance(center, point(0, -179.995), 2), true);
  assert.equal(withinDistance(center, point(0, -179.995), 1), false);
  assert.equal(withinDistance(center, null, 2), false);
  assert.equal(withinDistance(point(39.9, 116.4), point(31.23, 121.47), 100), false);
  assert.equal(latitudeWindow(89.99, 2)[1], 90);
});

test("地点追加去重且完整保留选择的地点", () => {
  assert.equal(noteWithPlace("午餐", "王府井"), "午餐 · 王府井");
  assert.equal(noteWithPlace("午餐 · 王府井", "王府井"), "午餐 · 王府井");
  const result = noteWithPlace("餐".repeat(200), "王府井");
  assert.equal([...result].length, 200);
  assert.ok(result.endsWith(" · 王府井"));
});
