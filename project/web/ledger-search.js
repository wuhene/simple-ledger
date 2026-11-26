const maxClues = 12;
const fold = value => value.normalize("NFKC").toLowerCase().replace(/\s+/g, " ").trim();
const clueValue = ({ type, value }) => {
  const text = fold(value);
  if (fold(type) === "省份" && text.length > 2) return text.replace(/省$/u, "");
  return ["城市", "入账城市"].includes(fold(type)) && text.length > 2 ? text.replace(/市$/u, "") : text;
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

export function searchableClues(entry) {
  const clues = cleanClues(entry.clues).filter(clue => !["入账城市", "入账区县"].includes(fold(clue.type)));
  for (const [type, field] of [["入账城市", "city"], ["入账区县", "district"]]) {
    const value = entry.location?.[field];
    if (typeof value === "string" && value.trim() && value.length <= 120) clues.push({ type, value });
  }
  return clues;
}

const searchableValues = entry => [entry.note, entry.sourceText, ...searchableClues(entry).flatMap(({ type, value }) => [type, value])]
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
  const clues = searchableClues(entry);
  if (!filters.every(filter => clues.some(clue => fold(clue.type) === fold(filter.type) && clueValue(clue) === clueValue(filter)))) return -1;
  const values = searchableValues(entry);
  if (!terms.every(term => values.some(value => value.includes(term)))) return -1;
  return terms.reduce((score, term) => score + (values.includes(term) ? 2 : 1), filters.length * 3);
}

export function currentLedgerLocation() {
  if (!globalThis.navigator?.geolocation) return Promise.resolve(null);
  return new Promise(resolve => navigator.geolocation.getCurrentPosition(({ coords, timestamp }) => resolve({
    latitude: coords.latitude, longitude: coords.longitude, accuracy: coords.accuracy,
    capturedAt: new Date(timestamp).toISOString(),
  }), () => resolve(null), { maximumAge: 0, timeout: 4000 })).catch(() => null);
}

export function latitudeWindow(latitude, km) {
  const span = km * 180 / (6371 * Math.PI);
  return [Math.max(-90, latitude - span), Math.min(90, latitude + span)];
}

export function withinDistance(origin, point, km) {
  if (!validLocation(point)) return false;
  const rad = Math.PI / 180;
  const halfLat = (point.latitude - origin.latitude) * rad / 2;
  const halfLng = (point.longitude - origin.longitude) * rad / 2;
  const h = Math.sin(halfLat) ** 2 + Math.cos(origin.latitude * rad) * Math.cos(point.latitude * rad) * Math.sin(halfLng) ** 2;
  return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h))) <= km;
}

export function noteWithPlace(note, place) {
  if (!place || note.includes(place)) return note;
  if (!note) return place;
  const size = 200 - [...place].length - 3;
  return [...note].slice(0, size).join("") + " · " + place;
}

export function cluesWithPlace(clues, place) {
  const existing = cleanClues(clues);
  if (!place || existing.some(clue => ["城市", "区县", "地点"].includes(clue.type))) return existing;
  return cleanClues([{ type: "地点", value: place }, ...existing]);
}

export function appendManualPlace(note, place) {
  if (!place || !note) return note + place;
  if (note.split(/\r?\n/).some(line => line.trim() === place)) return note;
  const combined = note + (note.endsWith("\n") ? "" : "\n") + place;
  return [...combined].length <= 200 ? combined : null;
}
