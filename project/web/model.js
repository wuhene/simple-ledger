export const SCHEMA_VERSION = 1;
export const MAX_MINOR = 9999999999;
export const EXPENSE = "EXPENSE";
export const INCOME = "INCOME";
export const expenseNames =
  "餐饮 购物 日用 交通 蔬菜 水果 零食 运动 娱乐 通讯 服饰 美容 住房 居家 孩子 长辈 社交 旅行 烟酒 数码 汽车 医疗 书籍 学习 宠物 礼金 礼物 办公 其它".split(
    " ",
  );
export const incomeNames = "工资 兼职 理财 礼金 其它 支付宝 微信".split(" ");
export const iconKeys = [
  "food",
  "bag",
  "home",
  "bus",
  "gift",
  "heart",
  "book",
  "briefcase",
  "medical",
  "pet",
  "car",
  "other",
];

export function initialCategories() {
  return [
    ...expenseNames.map((name, sortIndex) => ({
      id: `expense-${sortIndex}`,
      type: EXPENSE,
      name,
      iconKey: iconFor(name),
      sortIndex,
      hidden: false,
      isBuiltin: true,
    })),
    ...incomeNames.map((name, sortIndex) => ({
      id: `income-${sortIndex}`,
      type: INCOME,
      name,
      iconKey: iconFor(name),
      sortIndex,
      hidden: name === "支付宝" || name === "微信",
      isBuiltin: true,
    })),
  ];
}

export function iconFor(name) {
  if (/餐饮|蔬菜|水果|零食/.test(name)) return "food";
  if (/购物|服饰|美容|日用/.test(name)) return "bag";
  if (/住房|居家/.test(name)) return "home";
  if (/交通|旅行/.test(name)) return "bus";
  if (/礼金|礼物/.test(name)) return "gift";
  if (/运动|娱乐|社交/.test(name)) return "heart";
  if (/书籍|学习/.test(name)) return "book";
  if (/工资|兼职|理财|办公/.test(name)) return "briefcase";
  if (/医疗/.test(name)) return "medical";
  if (/宠物/.test(name)) return "pet";
  if (/汽车/.test(name)) return "car";
  return "other";
}

export function localToday(now = new Date()) {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
}
export function currentMonth() {
  return localToday().slice(0, 7);
}
export function isValidDate(value, today = localToday()) {
  if (
    typeof value !== "string" ||
    !/^\d{4}-\d{2}-\d{2}$/.test(value) ||
    value < "1900-01-01" ||
    value > today
  )
    return false;
  const [y, m, d] = value.split("-").map(Number);
  const check = new Date(y, m - 1, d);
  return (
    check.getFullYear() === y &&
    check.getMonth() === m - 1 &&
    check.getDate() === d
  );
}
export function shiftMonth(month, delta) {
  const [y, m] = month.split("-").map(Number);
  const date = new Date(y, m - 1 + delta, 1);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
}
export function shiftDate(date, delta) {
  const [y, m, d] = date.split("-").map(Number);
  const value = new Date(y, m - 1, d + delta);
  return localToday(value);
}
export function monthDays(month) {
  const [y, m] = month.split("-").map(Number);
  return new Date(y, m, 0).getDate();
}
export function parseMoney(value, allowZero = false) {
  const s = String(value).trim();
  if (!/^\d+(?:\.\d{1,2})?$/.test(s)) throw new Error("金额最多保留两位小数");
  const [integer, fraction = ""] = s.split(".");
  const minor = Number(integer) * 100 + Number(fraction.padEnd(2, "0"));
  if (
    !Number.isSafeInteger(minor) ||
    minor > MAX_MINOR ||
    (!allowZero && minor <= 0)
  )
    throw new Error("请输入有效金额（0.01—99,999,999.99）");
  return minor;
}
export function expressionMinor(expr) {
  const s = String(expr).replaceAll("-", "−").replaceAll(" ", "");
  if (!s || /[+−]$/.test(s)) throw new Error("请完成金额计算");
  if (!/^\d+(?:\.\d{1,2})?(?:[+−]\d+(?:\.\d{1,2})?)*$/.test(s))
    throw new Error("请输入有效金额，最多两位小数");
  const parts = s.match(/[+−]?\d+(?:\.\d{1,2})?/g) || [];
  const result = parts.reduce(
    (sum, part) =>
      sum +
      (part.startsWith("−") ? -1 : 1) *
        parseMoney(part.replace(/^[+−]/, ""), true),
    0,
  );
  if (!Number.isSafeInteger(result) || result <= 0 || result > MAX_MINOR)
    throw new Error("计算结果需在 0.01—99,999,999.99 元之间");
  return result;
}
export function money(minor) {
  const n = Math.abs(minor);
  return `${minor < 0 ? "−" : ""}${(n / 100).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
export function sum(entries, type) {
  return entries.reduce(
    (total, e) => total + (type && e.type !== type ? 0 : e.amountMinor),
    0,
  );
}
export function totals(entries) {
  const income = sum(entries, INCOME),
    expense = sum(entries, EXPENSE);
  return { income, expense, balance: income - expense };
}
export function entriesForMonth(entries, month) {
  return entries.filter((e) => e.businessDate.startsWith(month));
}
export function categoryTotals(entries, categories, type) {
  const filtered = entries.filter((e) => e.type === type);
  const total = sum(filtered);
  const byId = new Map();
  for (const e of filtered)
    byId.set(e.categoryId, (byId.get(e.categoryId) || 0) + e.amountMinor);
  return [...byId]
    .map(([id, amountMinor]) => ({
      category: categories.find((c) => c.id === id),
      amountMinor,
      percent: total ? (amountMinor / total) * 100 : 0,
    }))
    .filter((x) => x.category)
    .sort(
      (a, b) =>
        b.amountMinor - a.amountMinor ||
        a.category.sortIndex - b.category.sortIndex,
    );
}
export function sortEntries(entries) {
  return [...entries].sort(
    (a, b) =>
      b.businessDate.localeCompare(a.businessDate) ||
      b.createdAt.localeCompare(a.createdAt) ||
      b.id.localeCompare(a.id),
  );
}
export function groupEntries(entries) {
  const groups = new Map();
  for (const e of sortEntries(entries)) {
    if (!groups.has(e.businessDate)) groups.set(e.businessDate, []);
    groups.get(e.businessDate).push(e);
  }
  return [...groups].map(([date, rows]) => ({ date, rows, ...totals(rows) }));
}
export function periodBounds(kind, anchor, today = localToday()) {
  let start, end, label;
  if (kind === "week") {
    const [y, m, d] = anchor.split("-").map(Number);
    const date = new Date(y, m - 1, d);
    const weekday = (date.getDay() + 6) % 7;
    start = shiftDate(anchor, -weekday);
    end = shiftDate(start, 6);
    label = `${start} — ${end}`;
  } else if (kind === "year") {
    start = `${anchor.slice(0, 4)}-01-01`;
    end = `${anchor.slice(0, 4)}-12-31`;
    label = `${anchor.slice(0, 4)}年`;
  } else {
    start = `${anchor.slice(0, 7)}-01`;
    end = `${anchor.slice(0, 7)}-${monthDays(anchor.slice(0, 7))}`;
    label = `${anchor.slice(0, 7)}月`;
  }
  return { start, end: end < today ? end : today, fullEnd: end, label };
}
export function entriesInBounds(entries, bounds) {
  return entries.filter(
    (e) => e.businessDate >= bounds.start && e.businessDate <= bounds.end,
  );
}
export function dayCount(bounds) {
  if (bounds.end < bounds.start) return 0;
  const a = Date.parse(`${bounds.start}T00:00:00Z`),
    b = Date.parse(`${bounds.end}T00:00:00Z`);
  return Math.round((b - a) / 86400000) + 1;
}
export function trend(entries, bounds, type, kind) {
  if (bounds.end < bounds.start) return [];
  const by = new Map();
  for (const e of entriesInBounds(entries, bounds).filter(
    (x) => x.type === type,
  )) {
    const key = kind === "year" ? e.businessDate.slice(0, 7) : e.businessDate;
    by.set(key, (by.get(key) || 0) + e.amountMinor);
  }
  const result = [];
  if (kind === "year") {
    for (let m = 1; m <= 12; m++) {
      const key = `${bounds.start.slice(0, 4)}-${String(m).padStart(2, "0")}`;
      if (`${key}-01` <= bounds.end)
        result.push({ key, value: by.get(key) || 0 });
    }
  } else {
    for (let key = bounds.start; key <= bounds.end; key = shiftDate(key, 1))
      result.push({ key, value: by.get(key) || 0 });
  }
  return result;
}
export function dailyPeak(entries, type) {
  const by = new Map();
  for (const e of entries.filter((x) => x.type === type))
    by.set(e.businessDate, (by.get(e.businessDate) || 0) + e.amountMinor);
  const max = Math.max(0, ...by.values());
  return {
    amountMinor: max,
    dates: [...by]
      .filter(([, v]) => v === max && max > 0)
      .map(([d]) => d)
      .sort(),
  };
}
export function achievements(entries, today = localToday()) {
  const days = new Set(entries.map((e) => e.businessDate));
  let cursor = days.has(today) ? today : shiftDate(today, -1),
    streak = 0;
  while (days.has(cursor)) {
    streak++;
    cursor = shiftDate(cursor, -1);
  }
  return { days: days.size, count: entries.length, streak };
}
export function validateLedger(data, today = localToday()) {
  if (
    !data ||
    data.schemaVersion !== SCHEMA_VERSION ||
    !Array.isArray(data.entries) ||
    !Array.isArray(data.categories) ||
    !Array.isArray(data.budgets) ||
    !data.preferences ||
    !("asset" in data)
  )
    throw new Error("备份版本或结构无效");
  if (
    data.entries.length > 100000 ||
    data.categories.length > 1000 ||
    data.budgets.length > 2000
  )
    throw new Error("备份记录数量超出限制");
  const ids = new Set(),
    names = new Set(),
    categoryIds = new Set();
  for (const c of data.categories) {
    if (
      !c ||
      typeof c.id !== "string" ||
      !c.id ||
      categoryIds.has(c.id) ||
      ![EXPENSE, INCOME].includes(c.type) ||
      typeof c.name !== "string" ||
      !c.name.trim() ||
      [...c.name].length > 8 ||
      !iconKeys.includes(c.iconKey) ||
      !Number.isInteger(c.sortIndex) ||
      typeof c.hidden !== "boolean" ||
      typeof c.isBuiltin !== "boolean"
    )
      throw new Error("备份分类无效");
    const key = `${c.type}:${c.name}`;
    if (names.has(key)) throw new Error("备份中存在重复分类名称");
    names.add(key);
    categoryIds.add(c.id);
  }
  for (const type of [EXPENSE, INCOME])
    if (!data.categories.some((c) => c.type === type && !c.hidden))
      throw new Error("每种收支至少需要一个可用分类");
  for (const e of data.entries) {
    const c = data.categories.find((x) => x.id === e?.categoryId);
    if (
      !e ||
      typeof e.id !== "string" ||
      !e.id ||
      ids.has(e.id) ||
      ![EXPENSE, INCOME].includes(e.type) ||
      !Number.isSafeInteger(e.amountMinor) ||
      e.amountMinor <= 0 ||
      e.amountMinor > MAX_MINOR ||
      !isValidDate(e.businessDate, today) ||
      !c ||
      c.type !== e.type ||
      typeof e.note !== "string" ||
      [...e.note].length > 200 ||
      !Number.isFinite(Date.parse(e.createdAt)) ||
      !Number.isFinite(Date.parse(e.updatedAt))
    )
      throw new Error("备份流水无效或分类引用错误");
    ids.add(e.id);
  }
  const months = new Set();
  for (const b of data.budgets) {
    if (
      !b ||
      !/^\d{4}-\d{2}$/.test(b.yearMonth) ||
      b.yearMonth < "1900-01" ||
      Number(b.yearMonth.slice(5, 7)) < 1 ||
      Number(b.yearMonth.slice(5, 7)) > 12 ||
      months.has(b.yearMonth) ||
      !Number.isSafeInteger(b.amountMinor) ||
      b.amountMinor <= 0 ||
      b.amountMinor > MAX_MINOR ||
      !Number.isFinite(Date.parse(b.updatedAt))
    )
      throw new Error("备份预算无效");
    months.add(b.yearMonth);
  }
  if (
    data.asset !== null &&
    (!data.asset ||
      !Number.isSafeInteger(data.asset.assetMinor) ||
      !Number.isSafeInteger(data.asset.liabilityMinor) ||
      data.asset.assetMinor < 0 ||
      data.asset.liabilityMinor < 0 ||
      data.asset.assetMinor > MAX_MINOR ||
      data.asset.liabilityMinor > MAX_MINOR ||
      !Number.isFinite(Date.parse(data.asset.updatedAt)))
  )
    throw new Error("备份资产无效");
  if (typeof data.preferences.hideAmountsByDefault !== "boolean")
    throw new Error("备份设置无效");
  return data;
}
