import { EXPENSE, INCOME, isValidDate, parseMoney } from "./model.js";

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
    return { kind: "clarify", question: intent.question.trim() };
  }
  if (intent?.kind === "message" && typeof intent.text === "string" && intent.text.trim() && [...intent.text].length <= 300) {
    return { kind: "message", text: intent.text.trim() };
  }
  throw new Error("模型没有返回可识别的记账或统计指令。");
}
