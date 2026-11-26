import { cleanTerms, searchableClues } from "./ledger-search.js";

export function programTerms(text) {
  const source = String(text || "").normalize("NFKC");
  return cleanTerms(source.split(/帮我|找一下|找找|查一下|搜一下|我在|我吃过|有没有|哪里|哪家|是什么|花了多少|的记录|吃的|吃过的|买过的|[\s\p{P}]+/u)
    .map(value => value.trim()).filter(value => value.length >= 2 && value.length <= 24 &&
      !/^(我|吃饭|吃了|记账|多少|有没有|最多|餐饮)$/.test(value))).slice(0, 6);
}

export async function recallLedger(queryEntries, bounds, aiWords, localWords, useText) {
  const modelTerms = cleanTerms(aiWords), generated = cleanTerms(localWords).slice(0, 6);
  const hard = new Set(cleanTerms((bounds.filters || []).map(filter => filter.value)));
  const words = useText ? [...new Set([...modelTerms, ...generated])].filter(word => !hard.has(word)) : [];
  if (!words.length) return queryEntries(bounds);
  const found = new Map();
  for (const word of words) {
    for (const entry of await queryEntries({ ...bounds, terms: [word] })) found.set(entry.id, entry);
  }
  return [...found.values()];
}

export function groupLedger(rows, dimension, order, limit) {
  const groups = new Map();
  let covered = 0;
  for (const entry of rows) {
    const values = new Map();
    if (dimension === "入账小时") {
      const hour = new Date(entry.createdAt).getHours();
      if (Number.isFinite(hour)) values.set(String(hour), String(hour).padStart(2, "0") + ":00");
    } else {
      for (const clue of searchableClues(entry)) {
        if (clue.type.normalize("NFKC").trim() !== dimension) continue;
        let label = clue.value.normalize("NFKC").trim().replace(/\s+/g, " ");
        if (dimension === "用餐时间" && /^(?:[01]?\d|2[0-3]):[0-5]\d$/.test(label)) {
          const hour = label.split(":")[0].padStart(2, "0");
          label = hour + ":00–" + hour + ":59";
        }
        if (label) values.set(label.toLowerCase(), label);
      }
    }
    if (values.size) covered++;
    for (const [key, name] of values) {
      const group = groups.get(key) || { name, count: 0, amountMinor: 0 };
      group.count++;
      group.amountMinor += entry.amountMinor;
      groups.set(key, group);
    }
  }
  const metric = order === "amount" ? "amountMinor" : "count";
  return { dimension, covered, missing: rows.length - covered,
    groups: [...groups.values()].sort((a, b) => b[metric] - a[metric] || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)).slice(0, limit) };
}
