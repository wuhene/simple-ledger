import { EXPENSE, INCOME, isValidDate, parseMoney } from "./model.js";
import { cleanClues, cleanTerms } from "./ledger-search.js";

const dataBlocks = ["totals", "categories", "entries", "comparison"];
const numerals = "一二三四五六七八九";

export function rankLimit(value) {
  const number = Number(value);
  return Number.isInteger(number) && number > 0 ? Math.min(number, 50) : 0;
}

export function requestedRank(text) {
  const value = text.normalize("NFKC").match(/(?:前|top\s*)(\d{1,3}|[一二三四五六七八九]?十[一二三四五六七八九]?|[一二三四五六七八九])(?=\s*(?:[笔条项名]|个(?!\s*(?:月|星|礼拜|年|小?时))|[。！？?！]|$))/i)?.[1];
  if (!value) return 0;
  if (/^\d/.test(value)) return rankLimit(value);
  if (!value.includes("十")) return numerals.indexOf(value) + 1;
  const [tens, units] = value.split("十");
  return rankLimit((tens ? numerals.indexOf(tens) + 1 : 1) * 10 + (units ? numerals.indexOf(units) + 1 : 0));
}

export function isResultDateFollowUp(text) {
  const missingYear = /(?:没(?:有)?(?:给|标|写|显示|说明|注明|带|说)|缺少|漏了).{0,14}(?:年份|哪一年|哪一年的|年分|日期|年月)/;
  const asksRows = /(?:刚才|上面|这些|那几笔|排行|明细|结果).{0,28}(?:哪一年|分别.{0,4}哪年|每笔.{0,8}(?:时间|日期|年份))/;
  return !/(?:只看|限定|改看|只查|重新查)/.test(text) && (missingYear.test(text) || asksRows.test(text));
}

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
      category: intent.category, note: intent.note.trim(), clues: cleanClues(intent.clues), usesPending: intent.usesPending === true, text };
  }
  if (["search", "stats", "insight"].includes(intent?.kind)) {
    const terms = cleanTerms(intent.terms), filters = cleanClues(intent.filters);
    const limit = intent.order === "amount-desc" ? rankLimit(intent.limit) || 10 : 0;
    const hasQuery = Boolean(terms.length || filters.length || limit);
    if (intent.kind === "search" && !hasQuery) throw new Error("请提供想查找的店名、菜品、地点或备注关键词。");
    const start = intent.start ?? (hasQuery ? "1900-01-01" : undefined);
    const end = intent.end ?? (hasQuery ? today : undefined);
    const { metric, category } = intent;
    if (!isValidDate(start, today) || typeof end !== "string" || !isValidDate(end, "9999-12-31") ||
      start > end || (intent.kind === "stats" && !["overview", "income", "expense"].includes(metric)) ||
      (category !== undefined && (typeof category !== "string" || [...category].length > 12))) {
      throw new Error("统计日期无效，请确认起止日期。");
    }
    const { compareStart, compareEnd, view } = intent;
    const comparison = isValidDate(compareStart, today) && isValidDate(compareEnd, today) && compareStart <= compareEnd
      ? { start: compareStart, end: compareEnd } : null;
    return { kind: intent.kind, start, end: end > today ? today : end,
      metric: intent.kind === "insight" ? "expense" : intent.kind === "search" ? "overview" : metric,
      category, comparison, text, terms, filters, limit,
      order: limit ? "amount-desc" : intent.order === "latest" ? "latest" : "",
      view: intent.kind === "search" || limit ? "entries" : dataBlocks.includes(view) ? view : "" };
  }
  if (intent?.kind === "clarify" && typeof intent.question === "string" && intent.question.trim() && [...intent.question].length <= 100) {
    return { kind: "clarify", question: intent.question.trim() };
  }
  if (intent?.kind === "result-dates") return { kind: "result-dates" };
  if (intent?.kind === "message" && text) {
    return { kind: "message", text };
  }
  if (intent?.kind === "answer") {
    const blocks = answerBlocks(intent.blocks, true);
    if (blocks.length) return { kind: "answer", blocks };
  }
  throw new Error("模型没有返回可识别的记账或统计指令。");
}
