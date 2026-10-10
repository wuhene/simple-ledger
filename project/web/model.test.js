import test from "node:test";
import assert from "node:assert/strict";
import {
  EXPENSE,
  INCOME,
  achievements,
  categoryTotals,
  dailyPeak,
  dayCount,
  expressionMinor,
  initialCategories,
  periodBounds,
  trend,
  validateLedger,
} from "./model.js";

const categories = initialCategories();
const entry = (
  id,
  date,
  amountMinor,
  type = EXPENSE,
  categoryId = type === EXPENSE ? "expense-0" : "income-0",
) => ({
  id,
  type,
  amountMinor,
  categoryId,
  businessDate: date,
  note: "",
  createdAt: "2026-09-30T00:00:00.000Z",
  updatedAt: "2026-09-30T00:00:00.000Z",
});

test("金额表达式按整数分加减，拒绝无效结果", () => {
  assert.equal(expressionMinor("18.50+3−2"), 1950);
  for (const value of ["0", "1.234", "3+", "2−3", "99999999.99+0.01"])
    assert.throws(() => expressionMinor(value));
});
test("单日最高聚合多笔，趋势补零并按周期统计", () => {
  const rows = [
    entry("a", "2026-09-01", 10000),
    entry("b", "2026-09-01", 20000),
    entry("c", "2026-09-03", 25000),
  ];
  assert.deepEqual(dailyPeak(rows, EXPENSE), {
    amountMinor: 30000,
    dates: ["2026-09-01"],
  });
  const bounds = periodBounds("month", "2026-09-01", "2026-09-30");
  assert.equal(dayCount(bounds), 30);
  assert.deepEqual(
    trend(rows, bounds, EXPENSE, "month")
      .slice(0, 3)
      .map((x) => x.value),
    [30000, 0, 25000],
  );
});
test("闰年与当前周期日均分母", () => {
  assert.equal(dayCount(periodBounds("month", "2024-02-01", "2026-09-30")), 29);
  assert.equal(dayCount(periodBounds("month", "2026-09-01", "2026-09-15")), 15);
});
test("分类占比、补记与删除后的成就重算", () => {
  const rows = [
    entry("a", "2026-09-29", 10000),
    entry("b", "2026-09-29", 20000),
    entry("c", "2026-09-30", 25000),
  ];
  assert.equal(categoryTotals(rows, categories, EXPENSE)[0].percent, 100);
  assert.deepEqual(achievements(rows, "2026-09-30"), {
    days: 2,
    count: 3,
    streak: 2,
  });
  assert.deepEqual(achievements(rows.slice(0, 2), "2026-09-30"), {
    days: 1,
    count: 2,
    streak: 1,
  });
});
test("无效备份分类引用必须被拒绝", () => {
  const backup = {
    schemaVersion: 1,
    entries: [entry("a", "2026-09-01", 1000, INCOME, "missing")],
    categories,
    budgets: [],
    asset: null,
    preferences: { hideAmountsByDefault: false },
  };
  assert.throws(() => validateLedger(backup, "2026-09-30"), /分类引用/);
});
