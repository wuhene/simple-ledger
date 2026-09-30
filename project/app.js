import {
  EXPENSE,
  INCOME,
  achievements,
  categoryTotals,
  currentMonth,
  dailyPeak,
  dayCount,
  entriesForMonth,
  entriesInBounds,
  expressionMinor,
  groupEntries,
  iconKeys,
  isValidDate,
  localToday,
  money,
  parseMoney,
  periodBounds,
  shiftDate,
  shiftMonth,
  sortEntries,
  totals,
  trend,
  validateLedger,
} from "./model.js";
import {
  loadLedger,
  putEntry,
  removeEntry,
  putCategory,
  putCategories,
  removeCategory,
  putBudget,
  removeBudget,
  putMeta,
  replaceLedger,
  clearLedger,
} from "./storage.js";

const $ = (s) => document.querySelector(s),
  phone = $("#phone");
const E = (s) =>
  String(s ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const uid = () => crypto.randomUUID();
const cName = (id) =>
  state.data.categories.find((c) => c.id === id)?.name || "未知分类";
const cById = (id) => state.data.categories.find((c) => c.id === id);
const typeName = (t) => (t === INCOME ? "收入" : "支出");
const format = (n, prefix = "") =>
  state.data.preferences.hideAmountsByDefault ? "••••" : `${prefix}${money(n)}`;
const formatYen = (n) =>
  state.data.preferences.hideAmountsByDefault
    ? "••••"
    : `${n < 0 ? "−" : ""}¥${money(Math.abs(n))}`;
const yearMonth = (m) => `${Number(m.slice(0, 4))}年${Number(m.slice(5, 7))}月`;
const yearOf = (m) => Number(m.slice(0, 4));
const monthOf = (m) => Number(m.slice(5, 7));
const dateText = (d) => `${Number(d.slice(5, 7))}月${Number(d.slice(8, 10))}日`;
const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
const fullDate = (d) => {
  const [y, m, day] = d.split("-").map(Number);
  return `${y} 年 ${m} 月 ${day} 日 · ${weekdays[new Date(y, m - 1, day).getDay()]}`;
};
const validMonth = (m) => /^\d{4}-(0[1-9]|1[0-2])$/.test(m) && m >= "1900-01" && m <= "9999-12";
const canShiftMonth = (m, delta, max = "9999-12") => {
  const next = shiftMonth(m, delta);
  return validMonth(next) && next <= max;
};
function dateInputError(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return "请按 YYYY-MM-DD 输入日期";
  if (value < "1900-01-01") return "最早可记 1900-01-01";
  if (value > localToday()) return "不能晚于今天";
  if (!isValidDate(value)) return "日期不存在，请检查年月日";
  return "";
}
const state = {
  data: null,
  page: "home",
  month: currentMonth(),
  statsType: EXPENSE,
  statsPeriod: "month",
  statsAnchor: localToday(),
  billMode: "month",
  billYear: Number(localToday().slice(0, 4)),
  reportMonth: currentMonth(),
  reportYear: Number(localToday().slice(0, 4)),
  detailId: null,
  detailOrigin: "home",
  filter: null,
  entry: null,
  entryOrigin: "home",
  categoryOrigin: "mine",
  catType: EXPENSE,
  modal: null,
  toast: null,
  shareUrl: null,
  shareOptions: { amounts: true, ranking: false, achievements: false },
  lastCategory: { [EXPENSE]: "expense-0", [INCOME]: "income-0" },
  undo: null,
  showAllCats: false,
  showAllMonths: false,
  showAllRanking: false,
  trendPoint: null,
  saveBusy: false,
  scrollByPage: {},
  motion: "",
};

function activeCategories(type, includeHidden = false) {
  return state.data.categories
    .filter((c) => c.type === type && (includeHidden || !c.hidden))
    .sort((a, b) => a.sortIndex - b.sortIndex);
}
function entryRows(entries) {
  const groups = groupEntries(entries);
  if (!groups.length)
    return `<div class="empty"><strong>这个范围暂无记录</strong><span>试试切换日期，或记下第一笔。</span></div>`;
  return groups
    .map(
      (g) =>
        `<div class="group-head"><span>${E(fullDate(g.date))}</span><span>收入 ${formatYen(g.income)} · 支出 ${formatYen(g.expense)}</span></div><div class="list">${g.rows.map((e) => `<button class="record" data-action="detail" data-id="${E(e.id)}"><span class="ico">${categoryIcon(cById(e.categoryId))}</span><span class="record-text">${E(cName(e.categoryId))}${e.note ? `<small>${E(e.note)}</small>` : ""}</span><strong class="${e.type === INCOME ? "income" : ""}">${e.type === INCOME ? "+" : "−"}${format(e.amountMinor)}</strong></button>`).join("")}</div>`,
    )
    .join("");
}
const iconPaths = {
  food: '<path d="M4 12h16M6 12a6 6 0 0 0 12 0M8 8l-1-3m9 3 1-3"/>',
  bag: '<path d="M4 8h16l-1.5 13h-13L4 8Zm4 0V6a4 4 0 0 1 8 0v2"/>',
  home: '<path d="m3 11 9-8 9 8v10H3V11Zm6 10v-7h6v7"/>',
  bus: '<rect x="4" y="4" width="16" height="15" rx="3"/><path d="M4 11h16M8 19v2m8-2v2M8 15h1m6 0h1"/>',
  gift: '<rect x="3" y="9" width="18" height="12" rx="2"/><path d="M2 9h20M12 9v12M12 9c-7 0-7-6-4-6s4 6 4 6Zm0 0c7 0 7-6 4-6s-4 6-4 6Z"/>',
  heart: '<path d="M20 9c0 5-8 11-8 11S4 14 4 9a4.5 4.5 0 0 1 8-2 4.5 4.5 0 0 1 8 2Z"/>',
  book: '<path d="M4 4h14a2 2 0 0 1 2 2v15H6a2 2 0 0 1-2-2V4Zm0 14a2 2 0 0 1 2-2h14M8 8h8"/>',
  briefcase: '<rect x="3" y="7" width="18" height="14" rx="2"/><path d="M9 7V4h6v3M3 13h18M10 13v2h4v-2"/>',
  medical: '<path d="M9 3h6v6h6v6h-6v6H9v-6H3V9h6V3Z"/>',
  pet: '<path d="M7 14c-3 2-1 7 3 6h4c4 1 6-4 3-6l-3-3h-4l-3 3ZM5 8h.01M9 5h.01M15 5h.01M19 8h.01"/>',
  car: '<path d="M4 11 6 5h12l2 6v8H4v-8Zm0 0h16M7 15h2m6 0h2M6 19v2m12-2v2"/>',
  other: '<circle cx="12" cy="12" r="9"/><path d="M12 8v8m-4-4h8"/>',
  daily: '<rect x="4" y="7" width="16" height="14" rx="2"/><path d="M7 7V4h10v3M8 12h8M8 16h5"/>',
  veg: '<path d="M8 20C2 13 5 5 19 4c1 13-5 18-11 16ZM8 20 18 7"/>',
  fruit: '<path d="M12 7C6 4 3 8 5 15c2 6 6 6 7 5 1 1 5 1 7-5 2-7-1-11-7-8Zm0 0c0-3 2-5 5-5"/>',
  snack: '<path d="M5 6h14l2 6-2 7H5l-2-7 2-6ZM8 10h.01M15 12h.01M10 16h.01"/>',
  sport: '<path d="M4 9v6m3-9v12m3-8v4m4-4v4m3-8v12m3-9v6M4 12h16"/>',
};
const categoryArt = {
  "expense-2": ["bag", "daily"],
  "expense-4": ["food", "veg"],
  "expense-5": ["food", "fruit"],
  "expense-6": ["food", "snack"],
  "expense-7": ["heart", "sport"],
};
const uiPaths = {
  list: '<rect x="4" y="3" width="16" height="18" rx="3"/><path d="M8 8h8M8 12h8M8 16h5"/>',
  chart: '<path d="M3 20V4M3 20h18M7 15l4-4 3 2 6-7"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  person: '<circle cx="12" cy="7" r="4"/><path d="M4 21c0-5 3-8 8-8s8 3 8 8"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="3"/><path d="M3 10h18M8 3v4m8-4v4M8 14h2m4 0h2m-8 4h2"/>',
  note: '<path d="M4 20h16M7 16 17 6l2 2-10 10-3 1 1-3Z"/>',
  eye: '<path d="M2 12s4-6 10-6 10 6 10 6-4 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
  eyeOff: '<path d="M3 3 21 21M9 7c1-.6 2-.9 3-.9 6 0 10 5.9 10 5.9a17 17 0 0 1-4 4M6 8a17 17 0 0 0-4 4s4 6 10 6c1.5 0 3-.4 4.2-1"/>',
  chevronLeft: '<path d="m15 5-7 7 7 7"/>',
  chevronRight: '<path d="m9 5 7 7-7 7"/>',
  chevronDown: '<path d="m5 9 7 7 7-7"/>',
  close: '<path d="M5 5 19 19M19 5 5 19"/>',
  check: '<path d="m5 12 5 5 9-10"/>',
};
function svgIcon(path) {
  return `<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${path}</svg>`;
}
function icon(key) { return svgIcon(iconPaths[key] || iconPaths.other); }
function categoryIcon(category) {
  const art = categoryArt[category?.id];
  return icon(art && category.iconKey === art[0] ? art[1] : category?.iconKey);
}
function uiIcon(key) { return svgIcon(uiPaths[key]); }
function seg(items, value, action) {
  return `<div class="seg" role="group">${items.map(([v, label]) => `<button data-action="${action}" data-value="${v}" class="${value === v ? "on" : ""}" aria-pressed="${value === v}">${label}</button>`).join("")}</div>`;
}
function periodPicker(month, action = "set-month") {
  return `<div class="period month-period"><button class="period-arrow" data-action="${action}" data-delta="-1" aria-label="上个月" ${canShiftMonth(month, -1) ? "" : "disabled"}>${uiIcon("chevronLeft")}</button><button class="period-current" data-action="pick-month" aria-label="选择月份，当前${yearMonth(month)}"><strong>${yearMonth(month)}</strong>${uiIcon("chevronDown")}</button><button class="period-arrow" data-action="${action}" data-delta="1" aria-label="下个月" ${canShiftMonth(month, 1) ? "" : "disabled"}>${uiIcon("chevronRight")}</button></div>`;
}
function hero(label, t) {
  return `<div class="card hero"><div class="eyebrow">${label}</div><div class="hero-value">${formatYen(t.balance)}</div><div class="summary-grid"><div><span class="eyebrow">收入</span><strong class="income">${formatYen(t.income)}</strong></div><div><span class="eyebrow">支出</span><strong>${formatYen(t.expense)}</strong></div></div></div>`;
}
function totalCard(label, value) {
  return `<div class="card hero"><div class="eyebrow">${label}</div><div class="hero-value">${formatYen(value)}</div></div>`;
}
function empty(title, body, action, label) {
  return `<div class="empty"><strong>${title}</strong>${body}${action ? `<button class="outline" data-action="${action}">${label}</button>` : ""}</div>`;
}

function home() {
  const rows = entriesForMonth(state.data.entries, state.month),
    t = totals(rows);
  return `${periodPicker(state.month)}${hero("本月结余", t)}<div class="row"><h2 class="heading">本月明细</h2><span class="micro">${rows.length} 笔</span></div>${rows.length ? entryRows(rows) : empty(state.data.entries.length ? "这个月暂无记录" : "还没有记录，记下第一笔吧", "收支会自动汇总在这里。", "new-entry", "记第一笔")}`;
}
function detail() {
  const e = state.data.entries.find((x) => x.id === state.detailId);
  if (!e)
    return empty("记录已不存在", "返回明细重新查看。", "go-home", "返回明细");
  return `<div class="card" style="text-align:center"><div class="eyebrow">${typeName(e.type)}</div><div class="hero-value ${e.type === INCOME ? "income" : ""}">${e.type === INCOME ? "+" : "−"}${formatYen(e.amountMinor)}</div><span class="chip">${E(cName(e.categoryId))}</span></div><div class="card"><div class="metric"><span>分类</span><strong>${E(cName(e.categoryId))}</strong></div><div class="metric"><span>日期</span><strong>${E(e.businessDate)}</strong></div><div class="metric"><span>备注</span><strong style="white-space:pre-wrap;overflow-wrap:anywhere">${E(e.note || "无")}</strong></div><div class="metric"><span>创建</span><strong>${E(new Date(e.createdAt).toLocaleString("zh-CN"))}</strong></div></div><button class="primary" data-action="edit-entry">编辑记录</button><button class="outline danger" data-action="ask-delete-entry">删除记录</button>`;
}
function entryPage() {
  const d = state.entry;
  const cats = activeCategories(d.type, true).filter((c) => !c.hidden || c.id === d.categoryId);
  const shown = state.showAllCats ? cats : cats.slice(0, 8);
  const categories = shown.map((c) => `<button class="cat ${c.id === d.categoryId ? "on" : ""}" data-action="entry-category" data-id="${E(c.id)}" aria-pressed="${c.id === d.categoryId}"><span class="ico">${categoryIcon(c)}</span><span>${E(c.name)}</span>${c.id === d.categoryId ? `<span class="cat-check">${uiIcon("check")}</span>` : ""}</button>`).join("");
  const keys = ["7", "8", "9", "⌫", "4", "5", "6", "+", "1", "2", "3", "−", "清空", "0", ".", "日期"].map((k) => k === "日期" ? `<button class="op key-date" data-action="pick-date" aria-label="选择记账日期">${uiIcon("calendar")}<span>日期</span></button>` : `<button class="${/[+−⌫]/.test(k) ? "op" : ""}" data-action="key" data-value="${k}" aria-label="${k === "⌫" ? "退格" : k}">${k}</button>`).join("");
  const categoryActions = `${cats.length > 8 ? `<button class="plain" data-action="toggle-cats">${state.showAllCats ? "收起" : "全部分类"}</button>` : ""}<button class="plain" data-action="category-manage">管理</button>`;
  return `<div class="entry-layout"><div class="entry-fields">
    ${seg([[EXPENSE, "支出"], [INCOME, "收入"]], d.type, "entry-type")}
    <div class="row category-heading"><h2 class="heading">选择分类</h2><div class="category-actions">${categoryActions}</div></div>
    <div class="gridcats">${categories}</div>
    <div class="entry-amount-card"><div class="eyebrow">${typeName(d.type)}金额 · ${E(cName(d.categoryId))}</div><div id="expression" class="amount-editor ${d.expr.length > 13 ? "long" : ""}">¥ ${E(d.expr || "0.00")}</div><div id="entryError" class="error" role="alert">${E(d.error || "")}</div><button class="entry-date" data-action="pick-date" aria-label="选择记账日期，当前${E(fullDate(d.date))}">${uiIcon("calendar")}<span>${E(d.date)} · ${E(fullDate(d.date).split("· ")[1])}</span>${d.date !== localToday() ? '<span class="backdate">补记</span>' : ""}${uiIcon("chevronRight")}</button></div>
    <textarea id="entryNote" class="entry-note" rows="1" maxlength="200" aria-label="备注（可选）" placeholder="备注　添加一点说明（可选）">${E(d.note)}</textarea>
  </div><div class="entry-dock"><div class="keys">${keys}</div><button class="primary entry-save" data-action="save-entry" ${state.saveBusy ? "disabled" : ""}>${state.saveBusy ? "保存中…" : "保存记录"}</button></div></div>`;
}
function categoryRanks(entries, type, click = true) {
  const ranked = categoryTotals(entries, state.data.categories, type),
    total = totals(entries)[type === INCOME ? "income" : "expense"];
  if (!ranked.length)
    return empty(`暂无${typeName(type)}分类数据`, "这个周期没有可统计的记录。");
  return `<div class="card">${ranked.map((r) => `<button class="rank" ${click ? `data-action="category-detail" data-id="${E(r.category.id)}"` : ""}><div class="row"><span>${categoryIcon(r.category)}　${E(r.category.name)}</span><strong>${formatYen(r.amountMinor)}</strong></div><div class="row micro"><span>${r.percent.toFixed(1)}%</span><span>${total ? `${money(r.amountMinor)} / ${money(total)}` : ""}</span></div><div class="bar"><i style="width:${r.percent}%"></i></div></button>`).join("")}</div>`;
}
function chart(points) {
  if (!points.length || points.every((p) => p.value === 0))
    return empty("暂无趋势数据", "这个周期没有可统计的记录。");
  const max = Math.max(1, ...points.map((p) => p.value));
  const w = 320,
    h = 130,
    coords = points.map(
      (p, i) =>
        `${points.length === 1 ? w / 2 : (i / (points.length - 1)) * w},${h - (p.value / max) * 105}`,
    );
  return `<svg class="trend" viewBox="0 0 320 150" role="img" aria-label="趋势图"><line x1="0" y1="130" x2="320" y2="130"/><line x1="0" y1="68" x2="320" y2="68"/><polyline points="${coords.join(" ")}"/>${points.map((p, i) => `<circle cx="${points.length === 1 ? w / 2 : (i / (points.length - 1)) * w}" cy="${h - (p.value / max) * 105}" r="${i === state.trendPoint ? 5 : 3}" data-action="trend-point" data-index="${i}"><title>${E(p.key)} ${money(p.value)} 元</title></circle>`).join("")}</svg><div class="chart-labels"><span>${E(points[0].key)}</span><span>${E(points.at(-1).key)}</span></div>${state.trendPoint !== null && points[state.trendPoint] ? `<p class="micro">${E(points[state.trendPoint].key)} · ${formatYen(points[state.trendPoint].value)}</p>` : ""}<details><summary class="plain">查看图表数据</summary>${points.map((p) => `<div class="metric micro"><span>${E(p.key)}</span><strong>${formatYen(p.value)}</strong></div>`).join("")}</details>`;
}
function stats() {
  const b = periodBounds(state.statsPeriod, state.statsAnchor),
    rows = entriesInBounds(state.data.entries, b),
    type = state.statsType,
    value = totals(rows)[type === INCOME ? "income" : "expense"],
    points = trend(rows, b, type, state.statsPeriod),
    days = dayCount(b);
  return `${seg(
    [
      [EXPENSE, "支出"],
      [INCOME, "收入"],
    ],
    type,
    "stats-type",
  )}${seg(
    [
      ["week", "周"],
      ["month", "月"],
      ["year", "年"],
    ],
    state.statsPeriod,
    "stats-period",
  )}<div class="period"><button data-action="stats-shift" data-delta="-1">‹</button><strong>${E(b.label)}</strong><button data-action="stats-shift" data-delta="1">›</button></div><div class="card hero"><div class="eyebrow">${typeName(type)}总额</div><div class="hero-value">${formatYen(value)}</div><span class="caption">日均 ${formatYen(days ? Math.round(value / days) : 0)} · ${days} 天</span></div><h2 class="heading">${typeName(type)}趋势</h2><div class="card">${value ? chart(points) : empty(`这个周期暂无${typeName(type)}`, "新增记录后显示趋势。")}</div><h2 class="heading">分类排行</h2>${categoryRanks(rows, type)}`;
}
function categoryDetail() {
  if (!state.filter) return "";
  const { categoryId, bounds, type } = state.filter,
    rows = entriesInBounds(state.data.entries, bounds).filter(
      (e) => e.categoryId === categoryId,
    ),
    all = entriesInBounds(state.data.entries, bounds).filter(
      (e) => e.type === type,
    ),
    sum = totals(rows)[type === INCOME ? "income" : "expense"],
    base = totals(all)[type === INCOME ? "income" : "expense"];
  return `${totalCard(`${E(bounds.label)} · ${typeName(type)} · ${rows.length}笔`, sum)}<p class="caption">占该周期${typeName(type)} ${base ? `${((sum / base) * 100).toFixed(1)}%` : "—"}</p>${entryRows(rows)}`;
}
function budgetInfo(month) {
  const b = state.data.budgets.find((x) => x.yearMonth === month),
    spent = totals(entriesForMonth(state.data.entries, month)).expense;
  return {
    budget: b,
    spent,
    remaining: b ? b.amountMinor - spent : null,
    ratio: b ? (spent / b.amountMinor) * 100 : null,
  };
}
function budgetCard(month) {
  const b = budgetInfo(month);
  return `<div class="card"><div class="row"><h2 class="heading" style="margin:0">月总预算</h2><span class="micro">${yearMonth(month)}</span></div>${b.budget ? `<div class="hero-value ${b.remaining < 0 ? "danger" : ""}" style="margin:14px 0 8px">${b.remaining < 0 ? "已超支 " : "剩余 "}${formatYen(Math.abs(b.remaining))}</div><div class="row caption"><span>已用 ${formatYen(b.spent)}</span><span>${b.ratio.toFixed(2)}%</span></div><div class="bar"><i style="width:${Math.min(100, b.ratio)}%;${b.remaining < 0 ? "background:#b53c3c" : ""}"></i></div>` : `<p class="muted">尚未设置预算</p>`}</div>`;
}
function toolsPage() {
  const t = totals(entriesForMonth(state.data.entries, state.month)),
    a = state.data.asset;
  return `<button class="menu" data-action="bills"><span class="ico">▤</span><span><strong>账单</strong><small class="micro" style="display:block">${yearMonth(state.month)} · 结余 ${formatYen(t.balance)}</small></span><span class="chev">›</span></button><button class="menu" data-action="budget"><span class="ico">◌</span><span><strong>月总预算</strong><small class="micro" style="display:block">${budgetInfo(state.month).budget ? "查看已用与剩余" : "设置本月预算"}</small></span><span class="chev">›</span></button><button class="menu" data-action="assets"><span class="ico">◇</span><span><strong>资产概览</strong><small class="micro" style="display:block">${a ? `净资产 ${formatYen(a.assetMinor - a.liabilityMinor)}` : "填写资产与负债"}</small></span><span class="chev">›</span></button>`;
}
function bills() {
  const entries = state.data.entries,
    years = [...new Set(entries.map((e) => yearOf(e.businessDate)))].sort(
      (a, b) => b - a,
    ),
    isMonth = state.billMode === "month",
    selected = state.billYear,
    yearRows = entries.filter((e) => yearOf(e.businessDate) === selected),
    t = totals(isMonth ? yearRows : entries),
    months = Array.from(
      { length: 12 },
      (_, i) => `${selected}-${String(12 - i).padStart(2, "0")}`,
    ).filter(
      (m) =>
        state.showAllMonths ||
        (m <= currentMonth() &&
          (entriesForMonth(entries, m).length ||
            state.data.budgets.some((b) => b.yearMonth === m))),
    );
  return `${seg(
    [
      ["month", "月账单"],
      ["year", "年账单"],
    ],
    state.billMode,
    "bill-mode",
  )}${isMonth ? `<div class="period"><button data-action="bill-year-shift" data-delta="-1">‹</button><strong>${selected}年</strong><button data-action="bill-year-shift" data-delta="1">›</button></div>` : ""}${hero(isMonth ? `${selected}年结余` : "累计结余", t)}${isMonth ? `<button class="plain" data-action="toggle-all-months">${state.showAllMonths ? "只看有记录月份" : "展开全部月份"}</button>` : ""}<div class="card table-wrap"><table class="bill-table"><thead><tr><th>${isMonth ? "月份" : "年份"}</th><th>收入</th><th>支出</th><th>结余</th></tr></thead><tbody>${(isMonth
    ? months
    : years
  )
    .map((key) => {
      const data = isMonth
          ? entriesForMonth(entries, key)
          : entries.filter((e) => yearOf(e.businessDate) === key),
        v = totals(data);
      return `<tr data-action="open-report" data-value="${key}"><td>${isMonth ? `${monthOf(key)}月${key === currentMonth() ? " · 至今" : ""}` : `${key}年`}</td><td>${format(v.income)}</td><td>${format(v.expense)}</td><td>${format(v.balance)}</td></tr>`;
    })
    .join(
      "",
    )}</tbody></table>${!(isMonth ? months : years).length ? `<div class="empty">暂无账单</div>` : ""}</div>`;
}
function budgetPage() {
  const month = state.month,
    b = budgetInfo(month);
  return `${periodPicker(month)}${budgetCard(month)}<label for="budgetAmount">月总预算（元）</label><input id="budgetAmount" class="field" inputmode="decimal" value="${b.budget ? money(b.budget.amountMinor).replaceAll(",", "") : ""}" placeholder="例如 6000.00"><p id="budgetError" class="error"></p><p class="micro">每个月独立设置；收入不会增加预算。</p><button class="primary" data-action="save-budget">保存预算</button>${b.budget ? '<button class="outline danger" data-action="ask-remove-budget">删除本月预算</button>' : ""}`;
}
function assetsPage() {
  const a = state.data.asset;
  return `<div class="card hero"><div class="eyebrow">净资产</div><div class="hero-value">${a ? formatYen(a.assetMinor - a.liabilityMinor) : "尚未设置"}</div><div class="summary-grid"><div><span class="eyebrow">资产</span><strong>${a ? formatYen(a.assetMinor) : "—"}</strong></div><div><span class="eyebrow">负债</span><strong>${a ? formatYen(a.liabilityMinor) : "—"}</strong></div></div></div><div class="tip">资产为手动维护，收支记录不会自动改变资产。</div><div class="asset-inputs"><div><label for="assetAmount">资产总额</label><input id="assetAmount" class="field" inputmode="decimal" value="${a ? money(a.assetMinor).replaceAll(",", "") : ""}" placeholder="0.00"></div><div><label for="liabilityAmount">负债总额</label><input id="liabilityAmount" class="field" inputmode="decimal" value="${a ? money(a.liabilityMinor).replaceAll(",", "") : ""}" placeholder="0.00"></div></div><p id="assetError" class="error"></p><button class="primary" data-action="save-assets">保存资产概览</button>${a ? `<p class="micro">更新于 ${E(new Date(a.updatedAt).toLocaleString("zh-CN"))}</p>` : ""}`;
}
function mine() {
  const a = achievements(state.data.entries);
  return `<div class="card"><div class="row"><span class="ico" style="height:52px;width:52px;font-size:23px">账</span><div style="flex:1"><strong>认真记录每一天</strong><div class="micro">简账 · 本地账本</div></div></div></div><div class="card achievement"><div><strong>${a.streak}</strong><span>连续记账</span></div><div><strong>${a.days}</strong><span>记账天数</span></div><div><strong>${a.count}</strong><span>总笔数</span></div></div><div class="list"><button class="menu" data-action="categories"><span class="ico">◇</span><span>分类管理</span><span class="chev">›</span></button><button class="menu" data-action="settings"><span class="ico">⚙</span><span>设置与数据</span><span class="chev">›</span></button><button class="menu" data-action="help"><span class="ico">?</span><span>使用说明</span><span class="chev">›</span></button><button class="menu" data-action="about"><span class="ico">♡</span><span>关于简账</span><span class="chev">›</span></button></div>`;
}
function categoryManage() {
  const list = activeCategories(state.catType, true),
    active = list.filter((c) => !c.hidden),
    hidden = list.filter((c) => c.hidden);
  const item = (c) =>
    `<div class="menu" draggable="true" data-catdrag="${E(c.id)}"><span class="ico">${categoryIcon(c)}</span><span><strong>${E(c.name)}</strong><small class="micro" style="display:block">${c.isBuiltin ? "内置" : "自建"} · 拖动排序</small></span><button class="small-button" data-action="edit-category" data-id="${E(c.id)}">编辑</button><button class="small-button" data-action="toggle-category" data-id="${E(c.id)}">${c.hidden ? "恢复" : "隐藏"}</button></div>`;
  return `${seg(
    [
      [EXPENSE, "支出"],
      [INCOME, "收入"],
    ],
    state.catType,
    "cat-type",
  )}<button class="primary" data-action="new-category">＋ 新增分类</button><h2 class="heading">使用中的分类</h2><div class="list">${active.map(item).join("")}</div>${hidden.length ? `<h2 class="heading">已隐藏</h2><div class="list">${hidden.map(item).join("")}</div>` : ""}`;
}
function settings() {
  return `<div class="list"><button class="menu" data-action="toggle-hide"><span>默认隐藏金额</span><strong>${state.data.preferences.hideAmountsByDefault ? "已开启" : "已关闭"}</strong></button><button class="menu" data-action="export"><span>导出 JSON 备份</span><span class="chev">›</span></button><button class="menu" data-action="restore"><span>从备份恢复</span><span class="chev">›</span></button></div><div class="tip">浏览器数据保存在当前地址和浏览器中。请定期导出备份；更换地址或浏览器不会自动同步。</div><button class="outline danger" data-action="clear">清空全部数据</button>`;
}

const palette = [
  "#246B54",
  "#5C8AA1",
  "#A9875A",
  "#8775A5",
  "#658760",
  "#899690",
];
function donut(ranks) {
  if (!ranks.length) return empty("暂无组成数据", "有记录后会显示占比。");
  const total = ranks.reduce((s, r) => s + r.amountMinor, 0),
    top = ranks.slice(0, 5),
    other = ranks.slice(5).reduce((s, r) => s + r.amountMinor, 0),
    display = other
      ? [...top, { category: { name: "其它分类" }, amountMinor: other }]
      : top;
  let cursor = 0;
  const stops = display.map((r, i) => {
    const start = cursor;
    cursor += (r.amountMinor / total) * 100;
    return `${palette[i % palette.length]} ${start}% ${cursor}%`;
  });
  return `<div class="row" style="align-items:flex-start"><div class="donut" style="background:conic-gradient(${stops.join(",")})" role="img" aria-label="分类支出组成"></div><div style="flex:1">${display.map((r, i) => `<div class="legend"><span class="dot" style="background:${palette[i % palette.length]}"></span><span>${E(r.category.name)} ${((r.amountMinor / total) * 100).toFixed(1)}%</span></div>`).join("")}</div></div>`;
}
function comparison(month, type) {
  return Array.from({ length: 6 }, (_, i) => {
    const m = shiftMonth(month, i - 5);
    return {
      key: m,
      value: totals(entriesForMonth(state.data.entries, m))[
        type === INCOME ? "income" : "expense"
      ],
    };
  });
}
function bars(points) {
  const max = Math.max(1, ...points.map((p) => p.value));
  return points
    .map(
      (p, i) =>
        `<div class="metric" style="align-items:center"><span class="micro" style="width:54px">${yearMonth(p.key)}</span><div class="bar" style="flex:1"><i style="width:${(p.value / max) * 100}%;opacity:${i === points.length - 1 ? 1 : 0.55}"></i></div><strong class="micro" style="width:82px">${format(p.value)}</strong></div>`,
    )
    .join("");
}
function report() {
  const annual = state.page === "annual",
    period = annual ? "year" : "month",
    anchor = annual ? `${state.reportYear}-01-01` : `${state.reportMonth}-01`,
    bounds = periodBounds(period, anchor),
    rows = entriesInBounds(state.data.entries, bounds),
    t = totals(rows),
    expenseRows = rows.filter((e) => e.type === EXPENSE),
    expenseRanks = categoryTotals(rows, state.data.categories, EXPENSE),
    incomeRanks = categoryTotals(rows, state.data.categories, INCOME),
    single = [...expenseRows].sort(
      (a, b) =>
        b.amountMinor - a.amountMinor ||
        b.businessDate.localeCompare(a.businessDate),
    ),
    peak = dailyPeak(rows, EXPENSE),
    days = dayCount(bounds),
    points = trend(rows, bounds, EXPENSE, period),
    label = annual
      ? `${state.reportYear}年`
      : `${yearMonth(state.reportMonth)}`,
    prevMonth = annual ? null : shiftMonth(state.reportMonth, -1),
    prev = annual
      ? null
      : totals(entriesForMonth(state.data.entries, prevMonth)),
    compare = annual
      ? []
      : expenseRanks
          .map((r) => ({
            r,
            old:
              categoryTotals(
                entriesForMonth(state.data.entries, prevMonth),
                state.data.categories,
                EXPENSE,
              ).find((x) => x.category.id === r.category.id)?.amountMinor || 0,
          }))
          .concat(
            categoryTotals(
              entriesForMonth(state.data.entries, prevMonth),
              state.data.categories,
              EXPENSE,
            )
              .filter(
                (r) =>
                  !expenseRanks.some((x) => x.category.id === r.category.id),
              )
              .map((r) => ({
                r: { ...r, amountMinor: 0 },
                old: r.amountMinor,
              })),
          )
          .map((x) => ({ ...x, diff: x.r.amountMinor - x.old }))
          .filter((x) => x.diff !== 0)
          .sort(
            (a, b) =>
              Math.abs(b.diff) - Math.abs(a.diff) ||
              a.r.category.sortIndex - b.r.category.sortIndex,
          )
          .slice(0, 3);
  if (bounds.end < bounds.start) return empty("暂无报告", "未来周期尚未开始。");
  return `${hero(`${label}结余`, t)}${annual ? "" : `<p class="micro">上月结余 ${formatYen(prev.balance)} · ${state.reportMonth === currentMonth() ? "本月至今对比上月全月" : "本月全月对比上月全月"}</p>`}<h2 class="heading">支出组成</h2><div class="card">${donut(expenseRanks)}</div>${categoryRanks(rows, EXPENSE)}${
    annual
      ? ""
      : `<h2 class="heading">单笔支出排行</h2><div class="card">${
          single.length
            ? single
                .slice(0, state.showAllRanking ? single.length : 5)
                .map(
                  (e, i) =>
                    `<button class="rank" data-action="detail" data-id="${E(e.id)}"><div class="row"><span>${i + 1}. ${E(cName(e.categoryId))} · ${dateText(e.businessDate)}</span><strong>${formatYen(e.amountMinor)}</strong></div></button>`,
                )
                .join("")
            : empty("暂无支出", "")
        }</div>${single.length > 5 ? `<button class="plain" data-action="toggle-ranking">${state.showAllRanking ? "收起" : "查看全部"}</button>` : ""}`
  }
  <h2 class="heading">${annual ? "月度" : "每日"}支出趋势</h2><div class="card"><div class="summary-grid"><div><span class="eyebrow">${annual ? "年度支出" : "单日最高"}</span><strong>${formatYen(annual ? t.expense : peak.amountMinor)}</strong></div><div><span class="eyebrow">日均支出</span><strong>${formatYen(days ? Math.round(t.expense / days) : 0)}</strong></div></div>${!annual && peak.dates.length ? `<p class="micro">最高日期 ${peak.dates.map(dateText).join("、")}</p>` : ""}${chart(points)}</div>
  <h2 class="heading">${annual ? "12个月支出" : "最近6个月支出对比"}</h2><div class="card">${bars(
    annual
      ? Array.from({ length: 12 }, (_, i) => {
          const m = `${state.reportYear}-${String(i + 1).padStart(2, "0")}`;
          return {
            key: m,
            value: totals(entriesForMonth(state.data.entries, m)).expense,
          };
        })
      : comparison(state.reportMonth, EXPENSE),
  )}</div>
  ${annual ? "" : `<h2 class="heading">较上月变化最大的类别</h2><div class="card">${compare.length ? compare.map((x) => `<div class="metric"><span>${E(x.r.category.name)}</span><strong class="${x.diff > 0 ? "danger" : ""}">${x.old === 0 && x.diff > 0 ? "新增支出" : x.diff > 0 ? "增加" : "减少"} ${formatYen(Math.abs(x.diff))}</strong></div>`).join("") : empty("暂无类别变化", "")}</div>`}
  <h2 class="heading">收入组成</h2>${categoryRanks(rows, INCOME)}${
    annual
      ? ""
      : `<h2 class="heading">最近6个月收入对比</h2><div class="card">${bars(comparison(state.reportMonth, INCOME))}</div><h2 class="heading">当前记账成就</h2><div class="card achievement">${(() => {
          const a = achievements(state.data.entries);
          return `<div><strong>${a.streak}</strong><span>连续天数</span></div><div><strong>${a.days}</strong><span>记账天数</span></div><div><strong>${a.count}</strong><span>总笔数</span></div>`;
        })()}</div>`
  }<button class="primary" data-action="share">预览并下载报告图片</button>`;
}
function header() {
  const main = ["home", "stats", "tools", "mine"].includes(state.page),
    titles = {
      home: "简账",
      stats: "统计",
      tools: "工具",
      mine: "我的",
      entry: state.entry?.id ? "编辑记录" : "记一笔",
      detail: "记录详情",
      categoryDetail: cName(state.filter?.categoryId),
      bills: "账单",
      report: `${yearMonth(state.reportMonth)}账单`,
      annual: `${state.reportYear}年账单`,
      budget: "月总预算",
      assets: "资产概览",
      categories: "分类管理",
      settings: "设置与数据",
    };
  return `<header class="top ${state.page === "entry" ? "entry-top" : ""}">${main ? "" : `<button class="back" data-action="back" aria-label="${state.page === "entry" ? "取消记账" : "返回"}">${state.page === "entry" ? "取消" : uiIcon("chevronLeft")}</button>`}<h1>${E(titles[state.page] || "简账")}</h1>${main ? `<button class="top-icon" data-action="toggle-hide" aria-label="${state.data.preferences.hideAmountsByDefault ? "显示金额" : "隐藏金额"}">${uiIcon(state.data.preferences.hideAmountsByDefault ? "eyeOff" : "eye")}</button>` : ""}</header>`;
}
function navbar() {
  if (!["home", "stats", "tools", "mine"].includes(state.page)) return "";
  const items = [
    ["home", "明细", "list"],
    ["stats", "统计", "chart"],
    ["tools", "工具", "grid"],
    ["mine", "我的", "person"],
  ];
  return `<button class="fab" data-action="new-entry"><span aria-hidden="true">＋</span>记一笔</button><nav class="bottom" aria-label="主导航">${items.map(([p, label, glyph]) => `<button data-action="nav" data-value="${p}" class="${state.page === p ? "active" : ""}" aria-current="${state.page === p ? "page" : "false"}"><span class="nav-icon">${uiIcon(glyph)}</span><span>${label}</span></button>`).join("")}</nav>`;
}
function monthModal(m) {
  const year = Number(m.pending.slice(0, 4)), selectedMonth = Number(m.pending.slice(5, 7));
  return `<div class="modal-head"><h2>选择月份</h2><button class="icon-button" data-action="close-modal" aria-label="关闭">${uiIcon("close")}</button></div><div class="calendar-period"><button class="icon-button" data-action="month-year-shift" data-delta="-1" aria-label="上一年" ${year <= 1900 ? "disabled" : ""}>${uiIcon("chevronLeft")}</button><strong>${year} 年</strong><button class="icon-button" data-action="month-year-shift" data-delta="1" aria-label="下一年" ${year >= 9999 ? "disabled" : ""}>${uiIcon("chevronRight")}</button></div><div class="month-grid">${Array.from({ length: 12 }, (_, i) => { const value = `${year}-${String(i + 1).padStart(2, "0")}`; return `<button data-action="select-month" data-value="${value}" class="${selectedMonth === i + 1 ? "selected" : ""} ${value === currentMonth() ? "today-month" : ""}" aria-pressed="${selectedMonth === i + 1}">${i + 1} 月</button>`; }).join("")}</div><div class="modal-actions calendar-actions"><button class="outline" data-action="close-modal">取消</button><button class="primary" data-action="confirm-month">查看此月</button></div>`;
}
function dateJump(m) {
  const year = m.jumpYear ?? Number(m.month.slice(0, 4)), maxMonth = localToday().slice(0, 7);
  return `<div class="calendar-period"><button class="icon-button" data-action="date-year-shift" data-delta="-1" aria-label="上一年" ${year <= 1900 ? "disabled" : ""}>${uiIcon("chevronLeft")}</button><strong>${year} 年</strong><button class="icon-button" data-action="date-year-shift" data-delta="1" aria-label="下一年" ${year >= Number(maxMonth.slice(0, 4)) ? "disabled" : ""}>${uiIcon("chevronRight")}</button></div><div class="month-grid">${Array.from({length: 12}, (_, i) => {const value = `${year}-${String(i + 1).padStart(2, "0")}`; return `<button data-action="date-jump-month" data-value="${value}" class="${value === m.month ? "selected" : ""}" ${value > maxMonth ? "disabled" : ""}>${i + 1} 月</button>`;}).join("")}</div><button class="plain" data-action="calendar-mode">返回日历</button>`;
}
function dateModal(m) {
  const today = localToday(), maxMonth = today.slice(0, 7), first = new Date(Number(m.month.slice(0, 4)), Number(m.month.slice(5, 7)) - 1, 1), offset = (first.getDay() + 6) % 7, days = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
  const cells = Array.from({ length: offset }, () => '<span class="calendar-blank"></span>');
  for (let day = 1; day <= days; day++) {
    const date = `${m.month}-${String(day).padStart(2, "0")}`;
    cells.push(`<button class="day ${date === m.pending ? "selected" : ""} ${date === today ? "today" : ""}" data-action="select-date" data-value="${date}" aria-label="${E(fullDate(date))}${date === today ? "，今天" : ""}${date === m.pending ? "，已选中" : ""}" aria-pressed="${date === m.pending}" ${date > today ? "disabled" : ""}>${day}</button>`);
  }
  return `<div class="modal-head"><h2>选择日期</h2><button class="icon-button" data-action="close-modal" aria-label="关闭">${uiIcon("close")}</button></div><div class="picked-date">已选 ${E(fullDate(m.pending))}</div><div class="quick-dates"><button class="soft-button" data-action="quick-date" data-value="${today}">今天</button><button class="soft-button" data-action="quick-date" data-value="${shiftDate(today, -1)}">昨天</button></div>${m.mode === "input" ? `<label for="dateManual">输入日期 · YYYY-MM-DD</label><input id="dateManual" class="field" type="text" inputmode="numeric" maxlength="10" autocomplete="off" value="${E(m.input ?? m.pending)}" placeholder="例如 2026-09-28"><p id="dateError" class="error" role="alert">${E(m.error || "")}</p><button class="plain" data-action="calendar-mode">返回日历</button>` : m.mode === "jump" ? dateJump(m) : `<div class="calendar-period"><button class="icon-button" data-action="date-month-shift" data-delta="-1" aria-label="上个月" ${canShiftMonth(m.month, -1, maxMonth) ? "" : "disabled"}>${uiIcon("chevronLeft")}</button><button class="calendar-month-title" data-action="date-jump">${yearMonth(m.month)} ${uiIcon("chevronDown")}</button><button class="icon-button" data-action="date-month-shift" data-delta="1" aria-label="下个月" ${canShiftMonth(m.month, 1, maxMonth) ? "" : "disabled"}>${uiIcon("chevronRight")}</button></div><div class="weekday-row">${["一", "二", "三", "四", "五", "六", "日"].map((d) => `<span>${d}</span>`).join("")}</div><div class="day-grid">${cells.join("")}</div><button class="date-input-link" data-action="date-input-mode">输入日期 ${uiIcon("chevronRight")}</button>`}<div class="modal-actions calendar-actions"><button class="outline" data-action="close-modal">取消</button><button class="primary" data-action="confirm-date" ${m.error ? "disabled" : ""}>确定</button></div>`;
}
function modalHtml() {
  const m = state.modal;
  if (!m) return "";
  let body = "";
  switch (m.kind) {
    case "confirm":
      body = `<h2>${E(m.title)}</h2><p>${E(m.message)}</p><div class="modal-actions"><button class="outline" data-action="close-modal">取消</button><button class="primary ${m.danger ? "danger" : ""}" data-action="modal-confirm">${E(m.confirm || "确认")}</button></div>`;
      break;
    case "pickMonth":
      body = monthModal(m);
      break;
    case "pickDate":
      body = dateModal(m);
      break;
    case "category":
      body = `<h2>${m.id ? "编辑分类" : "新增分类"}</h2><label for="categoryName">名称（最多8字）</label><input class="field" id="categoryName" maxlength="8" value="${E(m.name || "")}" placeholder="分类名称"><label for="categoryIcon">图标</label><select id="categoryIcon" class="field">${iconKeys.map((k) => `<option value="${k}" ${k === m.iconKey ? "selected" : ""}>${k}</option>`).join("")}</select><p id="categoryError" class="error"></p><button class="primary" data-action="save-category">保存分类</button>${m.id && !cById(m.id)?.isBuiltin && !state.data.entries.some((e) => e.categoryId === m.id) ? '<button class="outline danger" data-action="ask-remove-category">删除分类</button>' : ""}<button class="plain" data-action="close-modal">关闭</button>`;
      break;
    case "restore":
      body = `<h2>恢复备份</h2><p>这会替换当前全部数据。请先导出当前账本备份，并确认文件已经保留在电脑上。</p><button class="outline" data-action="export">导出当前备份</button><label><input type="checkbox" id="backupConfirmed"> 我已确认备份文件已保留</label><label for="restoreFile">选择 JSON 备份</label><input id="restoreFile" class="field" type="file" accept=".json,application/json"><p id="restoreError" class="error"></p><button class="primary" data-action="preview-restore">校验并预览</button><button class="plain" data-action="close-modal">取消</button>`;
      break;
    case "restorePreview":
      body = `<h2>恢复预览</h2><p>记录 ${m.data.entries.length} 笔 · 分类 ${m.data.categories.length} 个 · 预算 ${m.data.budgets.length} 个月</p><p>日期范围：${m.data.entries.length ? `${[...m.data.entries].sort((a, b) => a.businessDate.localeCompare(b.businessDate))[0].businessDate} — ${[...m.data.entries].sort((a, b) => b.businessDate.localeCompare(a.businessDate))[0].businessDate}` : "无记录"}</p><p class="danger">确认后将替换当前全部数据。</p><button class="primary" data-action="confirm-restore">确认替换</button><button class="outline" data-action="close-modal">取消</button>`;
      break;
    case "share":
      body = `<h2>报告图片预览</h2><label><input id="shareAmounts" type="checkbox" ${state.shareOptions.amounts ? "checked" : ""}> 图片包含金额</label><label><input id="shareRanking" type="checkbox" ${state.shareOptions.ranking ? "checked" : ""}> 包含单笔排行</label><label><input id="shareAchievements" type="checkbox" ${state.shareOptions.achievements ? "checked" : ""}> 包含记账成就</label><p class="micro">默认只含汇总图表，不含备注、资产或姓名。请确认图片中的金额后再下载。</p><button class="outline" data-action="refresh-share">更新预览</button><img class="share-preview" alt="报告图片预览" src="${state.shareUrl || ""}"><button class="primary" data-action="download-share">下载 PNG 图片</button><button class="plain" data-action="close-modal">关闭</button>`;
      break;
    case "help":
      body = `<h2>使用说明</h2><p>点击“记一笔”选择收支分类，用金额键盘输入数字或加减表达式。选择过去日期可补记。点击明细可编辑或删除，删除后 5 秒内可撤销。</p><p>工具中可查看账单、设置月预算和手动维护资产。数据仅保存在当前浏览器，建议定期导出备份。</p><button class="primary" data-action="close-modal">知道了</button>`;
      break;
    case "about":
      body = `<h2>关于简账</h2><p>简账 Web 功能验收版 · 数据仅保存在本机浏览器。</p><button class="primary" data-action="close-modal">关闭</button>`;
      break;
    case "clear":
      body = `<h2>清空全部数据</h2><p class="danger">流水、分类调整、预算和资产都将删除。请先导出备份。</p><button class="outline" data-action="export">导出当前备份</button><label><input id="clearBacked" type="checkbox"> 我已保留备份，仍要清空</label><label for="clearPhrase">输入“清空账本”再次确认</label><input id="clearPhrase" class="field" placeholder="清空账本"><p id="clearError" class="error"></p><button class="primary" data-action="confirm-clear">确认清空</button><button class="outline" data-action="close-modal">取消</button>`;
      break;
  }
  return `<div class="modalback ${m.entering ? "modal-enter" : ""}"><button class="modal-scrim" data-action="close-modal" aria-label="关闭弹层"></button><div class="modal ${m.kind === "pickDate" || m.kind === "pickMonth" ? "calendar-modal" : ""}" role="dialog" aria-modal="true" aria-label="${m.kind === "pickDate" ? "选择日期" : m.kind === "pickMonth" ? "选择月份" : "操作弹层"}"><span class="modal-handle" aria-hidden="true"></span>${body}</div></div>`;
}
function render() {
  if (!state.data) return;
  const oldContent = $(".content"), samePage = phone.dataset.page === state.page;
  const oldScroll = oldContent?.scrollTop || 0;
  const oldEntryScroll = $(".entry-fields")?.scrollTop || 0;
  const oldFocus = state.modal && document.activeElement?.closest(".modal")
    ? { id: document.activeElement.id, action: document.activeElement.dataset.action, value: document.activeElement.dataset.value }
    : null;
  const pages = {
    home,
    detail,
    entry: entryPage,
    stats,
    categoryDetail,
    tools: toolsPage,
    bills,
    report,
    annual: report,
    budget: budgetPage,
    assets: assetsPage,
    mine,
    categories: categoryManage,
    settings,
  };
  const main = ["home", "stats", "tools", "mine"].includes(state.page);
  phone.innerHTML =
    header() +
    `<main class="content ${main ? "" : "secondary"} ${state.page === "entry" ? "entry-content" : ""} ${state.motion}">${pages[state.page]?.() || ""}</main>` +
    navbar() +
    modalHtml() +
    (state.toast
      ? `<div class="toast" role="status">${E(state.toast)}${state.undo ? '<button class="plain" style="color:#fff" data-action="undo-delete">撤销</button>' : ""}</div>`
      : "");
  phone.dataset.page = state.page;
  $(".content").scrollTop = samePage ? oldScroll : state.scrollByPage[state.page] || 0;
  if (samePage && state.page === "entry") $(".entry-fields").scrollTop = oldEntryScroll;
  if (state.modal) {
    phone.querySelectorAll(".top, .content, .fab, .bottom").forEach((el) => el.inert = true);
  }
  state.motion = "";
  if (state.modal?.entering) {
    state.modal.entering = false;
    $(".modal .icon-button, .modal input, .modal button")?.focus({ preventScroll: true });
  } else if (state.modal && oldFocus) {
    const same = oldFocus.id ? document.getElementById(oldFocus.id) : [...phone.querySelectorAll(".modal [data-action]")].find((el) => el.dataset.action === oldFocus.action && el.dataset.value === oldFocus.value);
    same?.focus({ preventScroll: true });
  }
}
function navigate(page) {
  if ($(".content")) state.scrollByPage[state.page] = $(".content").scrollTop;
  const mainFrom = ["home", "stats", "tools", "mine"].includes(state.page);
  const mainTo = ["home", "stats", "tools", "mine"].includes(page);
  state.motion = mainFrom && mainTo ? "motion-fade" : "motion-forward";
  state.page = page;
  state.modal = null;
  state.trendPoint = null;
  render();
}
function withViewTransition(change) {
  if (document.startViewTransition && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
    document.startViewTransition(change);
  } else {
    change();
  }
}
let toastTimer;
function toast(msg) {
  state.toast = msg;
  render();
  clearTimeout(toastTimer);
  toastTimer = setTimeout(
    () => {
      state.toast = null;
      render();
    },
    state.undo ? 5000 : 3200,
  );
}
function modal(m) {
  state.modal = { ...m, entering: true, returnFocus: document.activeElement?.dataset.action };
  render();
}
function closeModal() {
  const action = state.modal?.returnFocus;
  state.modal = null;
  render();
  if (action) $(`[data-action="${action}"]`)?.focus({ preventScroll: true });
}
async function reload() {
  state.data = await loadLedger();
  render();
}
function beginEntry(e = null) {
  const type = e?.type || EXPENSE,
    preferred = state.lastCategory[type],
    valid = activeCategories(type).some((c) => c.id === preferred)
      ? preferred
      : activeCategories(type)[0]?.id;
  state.entry = {
    id: e?.id || null,
    type,
    categoryId: e?.categoryId || valid,
    initialCategoryId: e?.categoryId || valid,
    expr: e ? money(e.amountMinor).replaceAll(",", "") : "",
    date: e?.businessDate || localToday(),
    note: e?.note || "",
    error: "",
  };
  state.entryOrigin = state.page;
  state.showAllCats = false;
  navigate("entry");
}
function draftChanged() {
  const d = state.entry;
  if (d.id) {
    const e = state.data.entries.find((x) => x.id === d.id);
    return (
      !!e &&
      (d.type !== e.type ||
        d.categoryId !== e.categoryId ||
        d.expr !== money(e.amountMinor).replaceAll(",", "") ||
        d.date !== e.businessDate ||
        d.note !== e.note)
    );
  }
  return !!(
    d.expr ||
    d.note ||
    d.date !== localToday() ||
    d.type !== EXPENSE ||
    d.categoryId !== d.initialCategoryId
  );
}
function back() {
  if (state.modal) {
    closeModal();
    return;
  }
  if (state.page === "entry") {
    if (draftChanged()) {
      modal({
        kind: "confirm",
        title: "放弃这笔记录？",
        message: "尚未保存的输入将丢失。",
        confirm: "放弃",
        target: "discard",
      });
      return;
    }
    navigate(state.entryOrigin);
    return;
  }
  const targets = {
    detail: state.detailOrigin,
    categoryDetail: state.filter?.origin || "stats",
    bills: "tools",
    report: "bills",
    annual: "bills",
    budget: "tools",
    assets: "tools",
    categories: state.categoryOrigin,
    settings: "mine",
  };
  navigate(targets[state.page] || "home");
}

function onKey(key) {
  const d = state.entry;
  let s = d.expr;
  if (key === "清空") s = "";
  else if (key === "⌫") s = s.slice(0, -1);
  else if (key === "+" || key === "−") {
    if (s) s = /[+−]$/.test(s) ? s.slice(0, -1) + key : s + key;
  } else {
    const term = s.split(/[+−]/).at(-1);
    if (key === "." && term.includes(".")) return;
    if (term.includes(".") && term.split(".")[1].length >= 2) return;
    if (key === "." && !term) key = "0.";
    if (s.length >= 80) return;
    s += key;
  }
  d.expr = s;
  d.error = "";
  $("#expression").textContent = `¥ ${s || "0.00"}`;
  $("#expression").classList.toggle("long", s.length > 13);
  $("#entryError").textContent = "";
}
async function saveEntry() {
  if (state.saveBusy) return;
  const d = state.entry;
  d.note = $("#entryNote").value.trim();
  let amountMinor;
  try {
    amountMinor = expressionMinor(d.expr);
    if (!isValidDate(d.date)) throw new Error("日期须在 1900-01-01 至今天之间");
    const c = cById(d.categoryId);
    if (
      !c ||
      c.type !== d.type ||
      (c.hidden &&
        c.id !== state.data.entries.find((e) => e.id === d.id)?.categoryId)
    )
      throw new Error("请选择有效分类");
    if ([...d.note].length > 200) throw new Error("备注最多200字");
  } catch (err) {
    d.error = err.message;
    $("#entryError").textContent = d.error;
    return;
  }
  state.saveBusy = true;
  render();
  try {
    const old = d.id ? state.data.entries.find((e) => e.id === d.id) : null,
      now = new Date().toISOString();
    const entry = {
      id: old?.id || uid(),
      type: d.type,
      amountMinor,
      categoryId: d.categoryId,
      businessDate: d.date,
      note: d.note,
      createdAt: old?.createdAt || now,
      updatedAt: now,
    };
    await putEntry(entry);
    state.lastCategory[d.type] = d.categoryId;
    await reload();
    state.detailId = entry.id;
    navigate(old ? "detail" : state.entryOrigin);
    toast(
      `已保存${entry.businessDate === localToday() ? "" : ` · ${entry.businessDate}`}`,
    );
  } catch (err) {
    d.error = `保存失败：${err.message}`;
    state.saveBusy = false;
    render();
    return;
  }
  state.saveBusy = false;
}
async function deleteEntry() {
  const e = state.data.entries.find((x) => x.id === state.detailId);
  if (!e) return;
  await removeEntry(e.id);
  state.undo = e;
  await reload();
  navigate("home");
  toast("记录已删除");
  setTimeout(() => {
    if (state.undo?.id === e.id) {
      state.undo = null;
      render();
    }
  }, 5000);
}
async function undoDelete() {
  if (!state.undo) return;
  const e = state.undo;
  state.undo = null;
  await putEntry(e);
  await reload();
  toast("已撤销删除");
}
async function saveBudget() {
  try {
    const amountMinor = parseMoney($("#budgetAmount").value);
    const b = {
      yearMonth: state.month,
      amountMinor,
      updatedAt: new Date().toISOString(),
    };
    await putBudget(b);
    await reload();
    toast("预算已保存");
  } catch (err) {
    $("#budgetError").textContent = err.message;
  }
}
async function saveAssets() {
  try {
    const a = $("#assetAmount").value,
      b = $("#liabilityAmount").value;
    if (a === "" || b === "") throw new Error("请填写资产和负债；允许填0");
    const asset = {
      assetMinor: parseMoney(a, true),
      liabilityMinor: parseMoney(b, true),
      updatedAt: new Date().toISOString(),
    };
    await putMeta("asset", asset);
    await reload();
    toast("资产概览已保存");
  } catch (err) {
    $("#assetError").textContent = err.message;
  }
}
async function saveCategory() {
  const m = state.modal;
  const name = $("#categoryName").value.trim(),
    iconKey = $("#categoryIcon").value;
  try {
    if (!name || [...name].length > 8) throw new Error("分类名称需要1—8个字");
    if (
      state.data.categories.some(
        (c) => c.type === state.catType && c.name === name && c.id !== m.id,
      )
    )
      throw new Error("同类型分类名称不能重复");
    const old = m.id ? cById(m.id) : null;
    const category = old
      ? { ...old, name, iconKey }
      : {
          id: uid(),
          type: state.catType,
          name,
          iconKey,
          sortIndex:
            Math.max(
              0,
              ...activeCategories(state.catType, true).map((c) => c.sortIndex),
            ) + 1,
          hidden: false,
          isBuiltin: false,
        };
    await putCategory(category);
    await reload();
    closeModal();
    toast("分类已保存");
  } catch (err) {
    $("#categoryError").textContent = err.message;
  }
}
async function toggleCategory(id) {
  const c = cById(id);
  if (!c) return;
  if (!c.hidden && activeCategories(c.type).length <= 1) {
    toast("至少保留一个可用分类");
    return;
  }
  await putCategory({ ...c, hidden: !c.hidden });
  await reload();
  toast(c.hidden ? "分类已恢复" : "分类已隐藏");
}
async function reorderCategory(sourceId, targetId) {
  const a = cById(sourceId),
    b = cById(targetId);
  if (!a || !b || a.type !== b.type || a.id === b.id) return;
  const rows = activeCategories(a.type, true),
    from = rows.findIndex((x) => x.id === a.id),
    to = rows.findIndex((x) => x.id === b.id);
  const [moving] = rows.splice(from, 1);
  rows.splice(to, 0, moving);
  await putCategories(rows.map((category, sortIndex) => ({ ...category, sortIndex })));
  await reload();
}
function exportBackup() {
  const data = JSON.stringify(state.data, null, 2),
    url = URL.createObjectURL(new Blob([data], { type: "application/json" })),
    link = document.createElement("a");
  link.href = url;
  link.download = `简账备份-${localToday()}.json`;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
  toast("已触发备份下载，请确认文件已保留");
}
async function previewRestore() {
  if (!$("#backupConfirmed").checked) {
    $("#restoreError").textContent = "请先确认已保留当前账本备份";
    return;
  }
  const file = $("#restoreFile").files[0];
  if (!file) {
    $("#restoreError").textContent = "请选择 JSON 文件";
    return;
  }
  try {
    if (file.size > 100 * 1024 * 1024) throw new Error("备份文件过大");
    const data = validateLedger(JSON.parse(await file.text()));
    modal({ kind: "restorePreview", data });
  } catch (err) {
    $("#restoreError").textContent = `校验失败：${err.message}`;
  }
}
function drawShare() {
  const annual = state.page === "annual",
    m = state.reportMonth,
    y = state.reportYear,
    anchor = annual ? `${y}-01-01` : `${m}-01`,
    b = periodBounds(annual ? "year" : "month", anchor),
    rows = entriesInBounds(state.data.entries, b),
    t = totals(rows),
    rank = categoryTotals(rows, state.data.categories, EXPENSE),
    showMoney = (n) =>
      state.shareOptions.amounts
        ? `${n < 0 ? "−" : ""}¥${money(Math.abs(n))}`
        : "••••";
  const canvas = document.createElement("canvas");
  canvas.width = 720;
  canvas.height = 1040;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#f6f8f7";
  ctx.fillRect(0, 0, 720, 1040);
  ctx.fillStyle = "#fff";
  ctx.fillRect(38, 38, 644, 964);
  ctx.fillStyle = "#246b54";
  ctx.font = "bold 30px system-ui";
  ctx.fillText("简账", 72, 104);
  ctx.fillStyle = "#62736c";
  ctx.font = "24px system-ui";
  ctx.fillText(annual ? `${y}年账单` : `${yearMonth(m)}账单`, 72, 154);
  ctx.fillText("结余", 72, 232);
  ctx.fillStyle = "#172d26";
  ctx.font = "bold 54px system-ui";
  ctx.fillText(showMoney(t.balance), 72, 304);
  ctx.fillStyle = "#53665e";
  ctx.font = "26px system-ui";
  ctx.fillText(`收入  ${showMoney(t.income)}`, 72, 365);
  ctx.fillText(`支出  ${showMoney(t.expense)}`, 72, 408);
  ctx.fillStyle = "#172d26";
  ctx.font = "bold 27px system-ui";
  ctx.fillText("支出分类", 72, 486);
  let cy = 526;
  for (const r of rank.slice(0, 5)) {
    ctx.fillStyle = "#246b54";
    ctx.fillRect(72, cy - 18, 12, 12);
    ctx.fillStyle = "#172d26";
    ctx.font = "23px system-ui";
    ctx.fillText(`${r.category.name}  ${showMoney(r.amountMinor)}`, 100, cy);
    ctx.fillStyle = "#e7f1ec";
    ctx.fillRect(474, cy - 16, 164, 12);
    ctx.fillStyle = "#246b54";
    ctx.fillRect(474, cy - 16, (164 * r.amountMinor) / rank[0].amountMinor, 12);
    cy += 51;
  }
  if (!rank.length) {
    ctx.fillStyle = "#62736c";
    ctx.fillText("暂无支出", 72, cy);
    cy += 50;
  }
  if (state.shareOptions.ranking) {
    ctx.fillStyle = "#172d26";
    ctx.font = "bold 26px system-ui";
    ctx.fillText("单笔支出排行", 72, cy + 28);
    cy += 67;
    for (const e of sortEntries(rows.filter((x) => x.type === EXPENSE))
      .sort((a, b) => b.amountMinor - a.amountMinor)
      .slice(0, 3)) {
      ctx.font = "21px system-ui";
      ctx.fillText(
        `${cName(e.categoryId)}  ${showMoney(e.amountMinor)}`,
        72,
        cy,
      );
      cy += 38;
    }
  }
  if (state.shareOptions.achievements) {
    const a = achievements(state.data.entries);
    ctx.fillStyle = "#172d26";
    ctx.font = "bold 25px system-ui";
    ctx.fillText(
      `记账成就 · ${a.days}天 · ${a.count}笔`,
      72,
      Math.min(cy + 60, 920),
    );
  }
  ctx.fillStyle = "#62736c";
  ctx.font = "19px system-ui";
  ctx.fillText("由简账生成 · 不含备注及资产", 72, 958);
  state.shareUrl = canvas.toDataURL("image/png");
}
function downloadShare() {
  if (!state.shareUrl) drawShare();
  const a = document.createElement("a");
  a.href = state.shareUrl;
  a.download = `简账报告-${state.page === "annual" ? state.reportYear : state.reportMonth}.png`;
  document.body.append(a);
  a.click();
  a.remove();
  toast("报告图片已下载");
}

async function handleAction(el) {
  const a = el.dataset.action,
    v = el.dataset.value,
    id = el.dataset.id,
    delta = Number(el.dataset.delta || 0);
  try {
    switch (a) {
      case "nav":
        if (state.page !== v) withViewTransition(() => navigate(v));
        break;
      case "go-home":
        navigate("home");
        break;
      case "back":
        back();
        break;
      case "new-entry":
        withViewTransition(() => beginEntry());
        break;
      case "detail":
        state.detailId = id;
        state.detailOrigin = state.page;
        navigate("detail");
        break;
      case "edit-entry":
        beginEntry(state.data.entries.find((x) => x.id === state.detailId));
        break;
      case "ask-delete-entry": {
        const e = state.data.entries.find((x) => x.id === state.detailId);
        modal({
          kind: "confirm",
          title: "删除这笔记录？",
          message: `${cName(e.categoryId)} · ${money(e.amountMinor)} · ${e.businessDate}`,
          confirm: "删除",
          danger: true,
          target: "delete-entry",
        });
        break;
      }
      case "undo-delete":
        await undoDelete();
        break;
      case "entry-type": {
        const d = state.entry;
        if (d.type === v) break;
        d.type = v;
        d.categoryId = activeCategories(v).some(
          (c) => c.id === state.lastCategory[v],
        )
          ? state.lastCategory[v]
          : activeCategories(v)[0]?.id || null;
        state.showAllCats = false;
        state.motion = "motion-fade";
        render();
        break;
      }
      case "entry-category":
        state.entry.categoryId = id;
        render();
        break;
      case "toggle-cats":
        state.showAllCats = !state.showAllCats;
        render();
        break;
      case "category-manage":
        state.catType = state.entry.type;
        state.categoryOrigin = "entry";
        navigate("categories");
        break;
      case "key":
        onKey(v);
        break;
      case "save-entry":
        await saveEntry();
        break;
      case "pick-date":
        modal({ kind: "pickDate", pending: state.entry.date, month: state.entry.date.slice(0, 7), mode: "calendar", input: state.entry.date, error: "" });
        break;
      case "date-month-shift": {
        const m = state.modal;
        if (m?.kind === "pickDate" && canShiftMonth(m.month, delta, localToday().slice(0, 7))) {
          m.month = shiftMonth(m.month, delta);
          render();
        }
        break;
      }
      case "date-jump":
        state.modal.mode = "jump";
        state.modal.jumpYear = Number(state.modal.month.slice(0, 4));
        render();
        break;
      case "date-year-shift":
        state.modal.jumpYear = Math.min(Number(localToday().slice(0, 4)), Math.max(1900, state.modal.jumpYear + delta));
        render();
        break;
      case "date-jump-month":
        if (validMonth(v) && v <= localToday().slice(0, 7)) {
          state.modal.month = v;
          state.modal.mode = "calendar";
          render();
        }
        break;
      case "select-date":
      case "quick-date":
        if (isValidDate(v)) {
          state.modal.pending = v;
          state.modal.month = v.slice(0, 7);
          state.modal.input = v;
          state.modal.error = "";
          state.modal.mode = "calendar";
          render();
        }
        break;
      case "date-input-mode":
        state.modal.mode = "input";
        state.modal.input = state.modal.pending;
        state.modal.error = "";
        render();
        $("#dateManual")?.focus();
        break;
      case "calendar-mode":
        state.modal.mode = "calendar";
        state.modal.error = "";
        render();
        break;
      case "confirm-date": {
        const m = state.modal;
        if (m.mode === "input") {
          m.input = $("#dateManual").value.trim();
          m.error = dateInputError(m.input);
          if (m.error) {
            $("#dateError").textContent = m.error;
            $("[data-action='confirm-date']").disabled = true;
            break;
          }
          m.pending = m.input;
        }
        if (!isValidDate(m.pending)) break;
        state.entry.date = m.pending;
        closeModal();
        break;
      }
      case "set-month":
        if (canShiftMonth(state.month, delta)) {
          state.month = shiftMonth(state.month, delta);
          state.motion = delta > 0 ? "motion-next" : "motion-prev";
          render();
        }
        break;
      case "pick-month":
        modal({ kind: "pickMonth", pending: state.month });
        break;
      case "month-year-shift": {
        const m = state.modal;
        const year = Number(m.pending.slice(0, 4)) + delta;
        if (year >= 1900 && year <= 9999) {
          m.pending = `${year}-${m.pending.slice(5, 7)}`;
          render();
        }
        break;
      }
      case "select-month":
        if (validMonth(v)) {
          state.modal.pending = v;
          render();
        }
        break;
      case "confirm-month": {
        const value = state.modal.pending;
        if (!validMonth(value)) {
          toast("请选择有效月份");
          break;
        }
        state.month = value;
        state.motion = "motion-fade";
        closeModal();
        break;
      }
      case "stats-type":
        state.statsType = v;
        state.trendPoint = null;
        render();
        break;
      case "stats-period":
        state.statsPeriod = v;
        state.trendPoint = null;
        render();
        break;
      case "stats-shift": {
        if (state.statsPeriod === "week")
          state.statsAnchor = shiftDate(state.statsAnchor, delta * 7);
        else if (state.statsPeriod === "month")
          state.statsAnchor = `${shiftMonth(state.statsAnchor.slice(0, 7), delta)}-01`;
        else
          state.statsAnchor = `${Number(state.statsAnchor.slice(0, 4)) + delta}-01-01`;
        state.trendPoint = null;
        state.motion = delta > 0 ? "motion-next" : "motion-prev";
        render();
        break;
      }
      case "trend-point":
        state.trendPoint = Number(el.dataset.index);
        render();
        break;
      case "category-detail": {
        let bounds, type;
        if (state.page === "stats") {
          bounds = periodBounds(state.statsPeriod, state.statsAnchor);
          type = state.statsType;
        } else if (state.page === "annual") {
          bounds = periodBounds("year", `${state.reportYear}-01-01`);
          type = state.data.categories.find((c) => c.id === id)?.type;
        } else {
          bounds = periodBounds("month", `${state.reportMonth}-01`);
          type = state.data.categories.find((c) => c.id === id)?.type;
        }
        state.filter = { categoryId: id, bounds, type, origin: state.page };
        navigate("categoryDetail");
        break;
      }
      case "bills":
        state.billYear = yearOf(state.month);
        navigate("bills");
        break;
      case "bill-mode":
        state.billMode = v;
        render();
        break;
      case "bill-year-shift":
        state.billYear += delta;
        render();
        break;
      case "toggle-all-months":
        state.showAllMonths = !state.showAllMonths;
        render();
        break;
      case "open-report":
        if (state.billMode === "month") {
          state.reportMonth = v;
          navigate("report");
        } else {
          state.reportYear = Number(v);
          navigate("annual");
        }
        break;
      case "toggle-ranking":
        state.showAllRanking = !state.showAllRanking;
        render();
        break;
      case "budget":
        navigate("budget");
        break;
      case "save-budget":
        await saveBudget();
        break;
      case "ask-remove-budget":
        modal({
          kind: "confirm",
          title: "删除这个月的预算？",
          message: "流水仍会保留。",
          confirm: "删除预算",
          danger: true,
          target: "remove-budget",
        });
        break;
      case "assets":
        navigate("assets");
        break;
      case "save-assets":
        await saveAssets();
        break;
      case "categories":
        state.categoryOrigin = "mine";
        navigate("categories");
        break;
      case "cat-type":
        state.catType = v;
        render();
        break;
      case "new-category":
        modal({ kind: "category", id: null, name: "", iconKey: "other" });
        break;
      case "edit-category": {
        const c = cById(id);
        modal({ kind: "category", id, name: c.name, iconKey: c.iconKey });
        break;
      }
      case "save-category":
        await saveCategory();
        break;
      case "toggle-category":
        await toggleCategory(id);
        break;
      case "ask-remove-category":
        modal({
          kind: "confirm",
          title: "删除自建分类？",
          message: "该分类没有历史流水，删除后不可恢复。",
          confirm: "删除分类",
          danger: true,
          target: "remove-category",
          id: state.modal.id,
        });
        break;
      case "settings":
        navigate("settings");
        break;
      case "toggle-hide": {
        const preference = {
          ...state.data.preferences,
          hideAmountsByDefault: !state.data.preferences.hideAmountsByDefault,
        };
        await putMeta("preferences", preference);
        await reload();
        break;
      }
      case "export":
        exportBackup();
        break;
      case "restore":
        modal({ kind: "restore" });
        break;
      case "preview-restore":
        await previewRestore();
        break;
      case "confirm-restore": {
        const data = state.modal.data;
        await replaceLedger(data);
        state.modal = null;
        await reload();
        navigate("home");
        toast("备份已恢复");
        break;
      }
      case "clear":
        modal({ kind: "clear" });
        break;
      case "confirm-clear":
        if (
          !$("#clearBacked").checked ||
          $("#clearPhrase").value !== "清空账本"
        ) {
          $("#clearError").textContent = "请确认备份并准确输入“清空账本”";
          break;
        }
        await clearLedger();
        state.modal = null;
        await reload();
        navigate("home");
        toast("账本已清空");
        break;
      case "share":
        state.shareOptions = {
          amounts: !state.data.preferences.hideAmountsByDefault,
          ranking: false,
          achievements: false,
        };
        drawShare();
        modal({ kind: "share" });
        break;
      case "refresh-share":
        state.shareOptions = {
          amounts: $("#shareAmounts").checked,
          ranking: $("#shareRanking").checked,
          achievements: $("#shareAchievements").checked,
        };
        drawShare();
        render();
        break;
      case "download-share":
        downloadShare();
        break;
      case "help":
        modal({ kind: "help" });
        break;
      case "about":
        modal({ kind: "about" });
        break;
      case "close-modal":
        closeModal();
        break;
      case "modal-confirm": {
        const m = state.modal;
        closeModal();
        if (m.target === "discard") navigate(state.entryOrigin);
        if (m.target === "delete-entry") await deleteEntry();
        if (m.target === "remove-budget") {
          await removeBudget(state.month);
          await reload();
          toast("预算已删除");
        }
        if (m.target === "remove-category") {
          await removeCategory(m.id);
          await reload();
          toast("分类已删除");
        }
        break;
      }
    }
  } catch (err) {
    console.error(err);
    toast(`操作失败：${err.message}`);
  }
}

phone.addEventListener("click", (e) => {
  const target = e.target.closest("[data-action]");
  if (target && phone.contains(target)) handleAction(target);
});
phone.addEventListener("input", (e) => {
  if (state.page === "entry" && state.entry) {
    if (e.target.id === "entryNote") state.entry.note = e.target.value;
  }
  if (e.target.id === "dateManual" && state.modal?.kind === "pickDate") {
    state.modal.input = e.target.value.trim();
    state.modal.error = dateInputError(state.modal.input);
    $("#dateError").textContent = state.modal.error;
    $("[data-action='confirm-date']").disabled = !!state.modal.error;
  }
});
phone.addEventListener("dragstart", (e) => {
  const row = e.target.closest("[data-catdrag]");
  if (row) e.dataTransfer.setData("text/plain", row.dataset.catdrag);
});
phone.addEventListener("dragover", (e) => {
  if (e.target.closest("[data-catdrag]")) e.preventDefault();
});
phone.addEventListener("drop", (e) => {
  const row = e.target.closest("[data-catdrag]");
  if (row) {
    e.preventDefault();
    reorderCategory(e.dataTransfer.getData("text/plain"), row.dataset.catdrag);
  }
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if (state.modal) closeModal();
    else if (!["home", "stats", "tools", "mine"].includes(state.page)) back();
  }
});
$("#width360").addEventListener("click", () => {
  phone.classList.add("narrow");
  $("#width360").classList.add("selected");
  $("#width390").classList.remove("selected");
});
$("#width390").addEventListener("click", () => {
  phone.classList.remove("narrow");
  $("#width390").classList.add("selected");
  $("#width360").classList.remove("selected");
});
try {
  state.data = await loadLedger();
  for (const type of [EXPENSE, INCOME]) {
    const last = sortEntries(
      state.data.entries.filter((e) => e.type === type),
    ).sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
    if (last && activeCategories(type).some((c) => c.id === last.categoryId))
      state.lastCategory[type] = last.categoryId;
  }
  render();
} catch (err) {
  phone.innerHTML = `<div class="loading"><h2>无法打开本地账本</h2><p>${E(err.message)}</p><button class="primary" onclick="location.reload()">重试</button></div>`;
}
