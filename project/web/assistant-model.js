import { EXPENSE, INCOME, isValidDate, monthDays, parseMoney, shiftDate, shiftMonth } from "./model.js";

const monthPattern = /(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月(?:\s*(\d{1,2})\s*[日号])?/gu;
const pad = number => String(number).padStart(2, "0");
const ask = question => ({ kind: "clarify", question });

function datesIn(text, today) {
  const year = Number(today.slice(0, 4));
  const matches = [...text.matchAll(monthPattern)];
  if (matches.length) {
    if (matches.length > 2) return {};
    const relativeYear = year - Number(/去年/.test(text));
    const dates = matches.map(([, y, m, d], index) => {
      const month = Number(m);
      const selectedYear = Number(y || (index === 1 ? matches[0][1] : "") || relativeYear);
      if (month < 1 || month > 12) return null;
      const prefix = selectedYear + "-" + pad(month);
      return [prefix + "-" + pad(d || 1), prefix + "-" + pad(d || monthDays(prefix))];
    });
    if (dates.some(date => !date)) return {};
    return { start: dates[0][0], end: dates.at(-1)[1] };
  }
  const iso = [...text.matchAll(/\b\d{4}-\d{2}-\d{2}\b/g)].map(match => match[0]);
  if (iso.length && iso.length <= 2) return { start: iso[0], end: iso.at(-1) };
  if (text.includes("今天")) return { start: today, end: today };
  if (text.includes("昨天")) {
    const day = shiftDate(today, -1);
    return { start: day, end: day };
  }
  if (/上个月|上月/.test(text)) {
    const month = shiftMonth(today.slice(0, 7), -1);
    return { start: month + "-01", end: month + "-" + monthDays(month) };
  }
  if (/本月|这个月/.test(text)) return { start: today.slice(0, 7) + "-01", end: today };
  const years = [...text.matchAll(/(\d{4})\s*年|今年|去年/g)].map(match =>
    Number(match[1] || year - Number(match[0] === "去年")));
  if (years.length && years.length <= 2) {
    return { start: years[0] + "-01-01", end: years.at(-1) === year ? today : years.at(-1) + "-12-31" };
  }
  return {};
}

export function interpretLocally(text, today, context, categories) {
  const input = text.normalize("NFKC");
  const amountOnly = input.match(/^(\d+(?:\.\d{1,2})?)\s*(?:元|块钱?|[。！!])?[。！!]?$/u);
  if (context?.kind === "partial" && amountOnly) {
    return { ...context.record, amount: amountOnly[1] };
  }
  const category = categories.find(item => input.includes(item.name));
  const bounds = datesIn(input, today);
  if (/统计|收入多少|支出多少|占比|花了多少|开销最多|收支|明细|收入情况/.test(input) ||
    (bounds.start && /(?:收入|支出)[。！!?？\s]*$/.test(input))) {
    if (!bounds.start && !context?.bounds && !/统计|占比|明细/.test(input)) return null;
    if (!bounds.start && !context?.bounds) return ask("你想查询哪天、哪个月或哪个日期范围？");
    if (!bounds.start && /月|年|今天|昨天|\d{4}-/.test(input)) return ask("这个日期没识别清楚，请写出起止日期。");
    const metric = /占比|最多|明细|排行/.test(input) && !/支出|开销/.test(input) && /收入/.test(input) ? "income" :
      /占比|最多|明细|排行/.test(input) ? "expense" : "overview";
    return { kind: "stats", ...(bounds.start ? bounds : context.bounds), metric,
      ...(input.includes("明细") && category ? { metric: category.type === INCOME ? "income" : "expense", category: category.name } : {}) };
  }
  if (context?.kind === "period" && bounds.start) return interpretLocally(context.text + " " + input, today, null, categories);
  if ((input.match(/\d+(?:\.\d+)?\s*(?:元|块)/g) || []).length > 1) return ask("请一次输入一笔记录。");
  const food = input.match(/(?:吃了?|点了?)\s*([^，。！!？?]+?)(?=\s*(?:花了?|付了?|用了?|\d|[，。！!？?]|$))/u);
  const amount = input.match(/(?<![\d.,+-])(\d+(?:\.\d{1,2})?)\s*(?:元|块)/u);
  const income = /工资|收到|到账|收入|赚了/.test(input);
  const type = income ? INCOME : EXPENSE;
  if (!food && !category && !(income && amount)) return null;
  if (!income && !food && !/花|付|买|记|消费|支出/.test(input)) return null;
  if (/[?？]|吗|多少/.test(input)) return null;
  const explicitDate = /[年月日号]|昨天|\d{4}-/.test(input.replace(/(?:今日|今日份)/g, ""));
  if (explicitDate && (!bounds.start || bounds.start !== bounds.end)) return null;
  const record = { kind: "record", type, amount: amount?.[1] || "", date: bounds.start || today,
    category: (category?.type === type ? category.name : food ? "餐饮" : ""), note: food?.[1].trim() || category?.name || "收入" };
  return amount ? record : { kind: "partial", record };
}

export function validateIntent(intent, today) {
  if (intent?.kind === "record") {
    if (![INCOME, EXPENSE].includes(intent.type) || typeof intent.amount !== "string" ||
      typeof intent.note !== "string" || [...intent.note].length > 200 ||
      typeof intent.category !== "string" || [...intent.category].length > 12 || !isValidDate(intent.date, today)) {
      throw new Error("记账信息不完整，请明确金额、收支类型和日期。");
    }
    return { kind: "record", type: intent.type, amountMinor: parseMoney(intent.amount), date: intent.date,
      category: intent.category, note: intent.note.trim() };
  }
  if (intent?.kind === "stats") {
    const { start, end, metric, category } = intent;
    if (!isValidDate(start, today) || typeof end !== "string" || !isValidDate(end, "9999-12-31") ||
      start > end || !["overview", "income", "expense"].includes(metric) ||
      (category !== undefined && (typeof category !== "string" || [...category].length > 12))) {
      throw new Error("统计日期无效，请确认起止日期。");
    }
    return { kind: "stats", start, end: end > today ? today : end, metric, category };
  }
  if (intent?.kind === "clarify" && typeof intent.question === "string" && intent.question.trim() && [...intent.question].length <= 100) {
    return ask(intent.question.trim());
  }
  throw new Error("模型没有返回可识别的记账或统计指令。");
}
