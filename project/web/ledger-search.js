const maxClues = 12;
const fold = value => value.normalize("NFKC").toLowerCase().replace(/\s+/g, " ").trim();
const clueValue = ({ type, value }) => {
  const text = fold(value);
  return fold(type) === "城市" && text.length > 2 ? text.replace(/市$/u, "") : text;
};

export function cleanClues(input) {
  if (!Array.isArray(input)) return [];
  const seen = new Set();
  const result = [];
  for (const clue of input.slice(0, maxClues * 2)) {
    if (typeof clue?.type !== "string" || typeof clue.value !== "string") continue;
    const type = clue.type.replace(/\s+/g, " ").trim(), value = clue.value.replace(/\s+/g, " ").trim();
    if (!type || !value || type.length > 24 || value.length > 120) continue;
    const key = JSON.stringify([fold(type), clueValue({ type, value })]);
    if (seen.has(key)) continue;
    seen.add(key);
    result.push({ type, value });
    if (result.length === maxClues) break;
  }
  return result;
}

export function cleanTerms(input) {
  if (!Array.isArray(input)) return [];
  return [...new Set(input.slice(0, maxClues).filter(value => typeof value === "string" && value.trim().length <= 80)
    .map(fold).filter(value => /[\p{L}\p{N}]/u.test(value)))];
}

export function validLocation(value) {
  return Boolean(value && Number.isFinite(value.latitude) && Math.abs(value.latitude) <= 90 &&
    Number.isFinite(value.longitude) && Math.abs(value.longitude) <= 180 &&
    Number.isFinite(value.accuracy) && value.accuracy >= 0 &&
    typeof value.capturedAt === "string" && Number.isFinite(Date.parse(value.capturedAt)));
}

const searchableValues = entry => [entry.note, entry.sourceText, ...cleanClues(entry.clues).flatMap(({ type, value }) => [type, value])]
  .filter(value => typeof value === "string" && value).map(fold);

function wordParts(value) {
  return value.match(/[\p{L}\p{N}]+/gu) || [];
}

export function entryTokens(entry) {
  const keys = new Set();
  for (const text of searchableValues(entry)) {
    for (const word of wordParts(text)) {
      const letters = [...word];
      for (let i = 0; i < letters.length; i++) {
        keys.add(letters[i]);
        if (i + 1 < letters.length) keys.add(letters[i] + letters[i + 1]);
      }
    }
  }
  return [...keys];
}

export function queryTokens(terms, filters) {
  return [...new Set([...terms, ...filters.map(clueValue)].flatMap(text => {
    const words = wordParts(text);
    return words.flatMap(word => {
      const letters = [...word];
      return letters.length < 2 ? letters : [letters.slice(0, 2).join(""), letters.slice(-2).join("")];
    });
  }))];
}

export function matchScore(entry, terms, filters) {
  const clues = cleanClues(entry.clues);
  if (!filters.every(filter => clues.some(clue => fold(clue.type) === fold(filter.type) && clueValue(clue) === clueValue(filter)))) return -1;
  const values = searchableValues(entry);
  if (!terms.every(term => values.some(value => value.includes(term)))) return -1;
  return terms.reduce((score, term) => score + (values.includes(term) ? 2 : 1), filters.length * 3);
}
