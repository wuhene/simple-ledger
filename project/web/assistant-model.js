import { EXPENSE, INCOME, isValidDate, parseMoney } from "./model.js";

const dataBlocks = ["totals", "categories", "entries", "comparison"];

function answerBlocks(values, allowed) {
  if (!Array.isArray(values)) return [];
  return values.slice(0, 8).flatMap(block => {
    if (allowed && dataBlocks.includes(block?.type)) return [{ type: block.type }];
    if (["heading", "paragraph"].includes(block?.type) && typeof block.text === "string" && block.text.trim()) {
      return [{ type: block.type, text: block.text.trim().slice(0, block.type === "heading" ? 45 : 600) }];
    }
    if (block?.type === "bullets" && Array.isArray(block.items)) {
      const items = block.items.filter(item => typeof item === "string" && item.trim())
        .slice(0, 5).map(item => item.trim().slice(0, 180));
      return items.length ? [{ type: "bullets", items }] : [];
    }
    return [];
  });
}

export function validateIntent(intent, today) {
  const text = typeof intent?.text === "string" && [...intent.text].length <= 600 ? intent.text.trim() : "";
  if (intent?.kind === "record") {
    if (![INCOME, EXPENSE].includes(intent.type) || typeof intent.amount !== "string" ||
      typeof intent.note !== "string" || [...intent.note].length > 200 ||
      typeof intent.category !== "string" || [...intent.category].length > 12 || !isValidDate(intent.date, today)) {
      throw new Error("记账信息不完整，请明确金额、收支类型和日期。");
    }
    return { kind: "record", type: intent.type, amountMinor: parseMoney(intent.amount), date: intent.date,
      category: intent.category, note: intent.note.trim(), text };
  }
  if (intent?.kind === "stats" || intent?.kind === "insight") {
    const { start, end, metric, category } = intent;
    if (!isValidDate(start, today) || typeof end !== "string" || !isValidDate(end, "9999-12-31") ||
      start > end || (intent.kind === "stats" && !["overview", "income", "expense"].includes(metric)) ||
      (category !== undefined && (typeof category !== "string" || [...category].length > 12))) {
      throw new Error("统计日期无效，请确认起止日期。");
    }
    const { compareStart, compareEnd, view } = intent;
    const comparison = isValidDate(compareStart, today) && isValidDate(compareEnd, today) && compareStart <= compareEnd
      ? { start: compareStart, end: compareEnd } : null;
    return { kind: intent.kind, start, end: end > today ? today : end,
      metric: intent.kind === "insight" ? "expense" : metric, category, comparison, text,
      view: dataBlocks.includes(view) ? view : "" };
  }
  if (intent?.kind === "clarify" && typeof intent.question === "string" && intent.question.trim() && [...intent.question].length <= 100) {
    return { kind: "clarify", question: intent.question.trim() };
  }
  if (intent?.kind === "message" && text) {
    return { kind: "message", text };
  }
  if (intent?.kind === "answer") {
    const blocks = answerBlocks(intent.blocks, true);
    if (blocks.length) return { kind: "answer", blocks };
  }
  throw new Error("模型没有返回可识别的记账或统计指令。");
}
