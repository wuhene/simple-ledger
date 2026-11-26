import { diningCategoryRepair, initialCategories, SCHEMA_VERSION, validateLedger } from "./model.js";
import { cleanClues, cleanTerms, entryTokens, latitudeWindow, matchScore, queryTokens, withinDistance } from "./ledger-search.js";
import { applyEntry } from "./ledger-rollups.js";

const DB_NAME = "simple-ledger";
const DB_VERSION = 5;
let dbPromise;

function request(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}
function done(tx) {
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error || new Error("数据库事务失败"));
  });
}
export function openDatabase() {
  if (!dbPromise)
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = (event) => {
        const db = req.result;
        const tx = req.transaction;
        if (event.oldVersion < 2) db.createObjectStore("assistantSessions", { keyPath: "id" });
        if (event.oldVersion === 0) {
          db.createObjectStore("entries", { keyPath: "id" });
          db.createObjectStore("categories", { keyPath: "id" });
          db.createObjectStore("budgets", { keyPath: "yearMonth" });
          db.createObjectStore("meta");
          for (const c of initialCategories()) tx.objectStore("categories").put(c);
          tx.objectStore("meta").put({ hideAmountsByDefault: false }, "preferences");
          tx.objectStore("meta").put(null, "asset");
        }
        const entries = tx.objectStore("entries");
        entries.createIndex("locationLatitude", "location.latitude");
        if (event.oldVersion >= 4) return;
        if (event.oldVersion < 3) {
          entries.createIndex("date", "businessDate");
          entries.createIndex("termDate", "searchKeys", { multiEntry: true });
        }
        entries.createIndex("category", "categoryId");
        const summaries = db.createObjectStore("monthlyStats", { keyPath: "month" });
        if (event.oldVersion > 0) {
          const months = new Map();
          const scan = entries.openCursor();
          scan.onsuccess = () => {
            const cursor = scan.result;
            if (!cursor) {
              for (const summary of months.values()) summaries.put(summary);
              return;
            }
            applyEntry(months, cursor.value, 1);
            if (event.oldVersion < 3) cursor.update(indexedEntry(cursor.value));
            cursor.continue();
          };
        }
      };
      req.onsuccess = () => {
        req.result.onversionchange = () => req.result.close();
        resolve(req.result);
      };
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error("请关闭其他爱记账页面后重试"));
    });
  return dbPromise;
}
function indexedEntry(entry) {
  return { ...entry, searchKeys: entryTokens(entry).map(term => [term, entry.businessDate]) };
}

function publicEntry({ searchKeys, ...entry }) {
  return entry;
}

export async function loadLedger(bounds) {
  const db = await openDatabase();
  const tx = db.transaction(
    ["entries", "categories", "budgets", "meta", "monthlyStats"],
    "readonly",
  );
  const entries = tx.objectStore("entries");
  const range = bounds && bounds.start <= bounds.end && IDBKeyRange.bound(bounds.start, bounds.end);
  const result = await Promise.all([
    range ? request(entries.index("date").getAll(range)) : bounds ? [] : request(entries.getAll()),
    request(tx.objectStore("categories").getAll()),
    request(tx.objectStore("budgets").getAll()),
    request(tx.objectStore("meta").get("asset")),
    request(tx.objectStore("meta").get("preferences")),
    request(entries.count()),
    request(tx.objectStore("monthlyStats").getAll()),
  ]);
  await done(tx);
  const categories = result[1];
  const dining = diningCategoryRepair(categories);
  if (dining) {
    await putCategory(dining);
    const index = categories.findIndex(category => category.id === dining.id);
    if (index < 0) categories.push(dining);
    else categories[index] = dining;
  }
  return {
    schemaVersion: SCHEMA_VERSION,
    entries: result[0].map(publicEntry),
    entryCount: result[5],
    monthlyStats: result[6],
    categories,
    budgets: result[2],
    asset: result[3] ?? null,
    preferences: result[4] ?? { hideAmountsByDefault: false },
  };
}

export async function readEntry(id) {
  const db = await openDatabase();
  const entry = await request(db.transaction("entries").objectStore("entries").get(id));
  return entry ? publicEntry(entry) : null;
}

export async function categoryInUse(id) {
  const db = await openDatabase();
  const index = db.transaction("entries").objectStore("entries").index("category");
  return (await request(index.count(id))) > 0;
}

async function updateMonthlyStats(tx, previous, next) {
  const store = tx.objectStore("monthlyStats");
  const keys = [...new Set([previous, next].filter(Boolean).map(entry => entry.businessDate.slice(0, 7)))];
  const summaries = await Promise.all(keys.map(key => request(store.get(key))));
  const months = new Map(summaries.filter(Boolean).map(summary => [summary.month, summary]));
  if (previous) applyEntry(months, previous, -1);
  if (next) applyEntry(months, next, 1);
  for (const summary of months.values()) {
    if (summary.count) store.put(summary);
    else store.delete(summary.month);
  }
}

export async function queryEntries({ start = "1900-01-01", end = "9999-12-31", terms, filters, nearby } = {}) {
  if (start > end) return [];
  terms = cleanTerms(terms);
  filters = cleanClues(filters);
  const db = await openDatabase();
  const store = db.transaction("entries").objectStore("entries");
  if (nearby) {
    const bounds = latitudeWindow(nearby.origin.latitude, nearby.km);
    const candidates = await request(store.index("locationLatitude").getAll(IDBKeyRange.bound(...bounds)));
    return candidates.map(publicEntry).filter(entry => entry.businessDate >= start && entry.businessDate <= end &&
      withinDistance(nearby.origin, entry.location, nearby.km) && matchScore(entry, terms, filters) >= 0);
  }
  const dateRange = IDBKeyRange.bound(start, end);
  const keys = queryTokens(terms, filters);
  if (!keys.length) return (await request(store.index("date").getAll(dateRange))).map(publicEntry)
    .filter(entry => matchScore(entry, terms, filters) >= 0);
  const index = store.index("termDate");
  const ranges = keys.map(key => IDBKeyRange.bound([key, start], [key, end]));
  const counts = await Promise.all(ranges.map(range => request(index.count(range))));
  let smallest = 0;
  for (let i = 1; i < counts.length; i++) if (counts[i] < counts[smallest]) smallest = i;
  if (!counts[smallest]) return [];
  const rows = await request(index.getAll(ranges[smallest]));
  return rows.map(publicEntry).map(entry => ({ entry, score: matchScore(entry, terms, filters) }))
    .filter(row => row.score >= 0).sort((a, b) => b.score - a.score ||
      b.entry.businessDate.localeCompare(a.entry.businessDate) ||
      b.entry.createdAt.localeCompare(a.entry.createdAt) || a.entry.id.localeCompare(b.entry.id)).map(row => row.entry);
}
export async function putEntry(entry, assistantSession) {
  await writeEntry(entry.id, indexedEntry(entry), undefined, assistantSession);
}
export async function removeEntry(id, expectedUpdatedAt) {
  await writeEntry(id, null, expectedUpdatedAt);
}
async function writeEntry(id, row, expectedUpdatedAt, assistantSession) {
  const db = await openDatabase(),
    tx = db.transaction(assistantSession ? ["entries", "monthlyStats", "assistantSessions"] :
      ["entries", "monthlyStats"], "readwrite");
  const completion = done(tx), entries = tx.objectStore("entries");
  const update = request(entries.get(id)).then(async entry => {
    if (entry && expectedUpdatedAt !== undefined && entry.updatedAt !== expectedUpdatedAt) {
      throw new Error("该账单已修改，请在明细中处理。");
    }
    if (!entry && !row) return;
    try {
      await updateMonthlyStats(tx, entry, row);
      if (row) entries.put(row);
      else entries.delete(id);
      if (assistantSession) tx.objectStore("assistantSessions").put(assistantSession);
    } catch (error) {
      try { tx.abort(); } catch { /* The failed request already ended the transaction. */ }
      throw error;
    }
  });
  await Promise.all([completion, update]);
}
export async function putCategory(category) {
  const db = await openDatabase(),
    tx = db.transaction("categories", "readwrite");
  tx.objectStore("categories").put(category);
  await done(tx);
}
export async function putCategories(categories) {
  const db = await openDatabase(),
    tx = db.transaction("categories", "readwrite");
  for (const category of categories) tx.objectStore("categories").put(category);
  await done(tx);
}
export async function removeCategory(id) {
  const db = await openDatabase(),
    tx = db.transaction(["categories", "entries"], "readwrite");
  const completion = done(tx);
  const update = request(tx.objectStore("entries").index("category").count(id)).then(count => {
    if (count) throw new Error("该分类仍有历史流水，不能删除。");
    tx.objectStore("categories").delete(id);
  });
  await Promise.all([completion, update]);
}
export async function putBudget(budget) {
  const db = await openDatabase(),
    tx = db.transaction("budgets", "readwrite");
  tx.objectStore("budgets").put(budget);
  await done(tx);
}
export async function removeBudget(month) {
  const db = await openDatabase(),
    tx = db.transaction("budgets", "readwrite");
  tx.objectStore("budgets").delete(month);
  await done(tx);
}
export async function putMeta(key, value) {
  const db = await openDatabase(),
    tx = db.transaction("meta", "readwrite");
  tx.objectStore("meta").put(value, key);
  await done(tx);
}
export async function replaceLedger(input, clearSessions = false) {
  const data = validateLedger(input),
    db = await openDatabase();
  const stores = ["entries", "categories", "budgets", "meta", "monthlyStats"];
  if (clearSessions) stores.push("assistantSessions");
  const tx = db.transaction(
    stores,
    "readwrite",
  );
  const entries = tx.objectStore("entries"),
    categories = tx.objectStore("categories"),
    budgets = tx.objectStore("budgets"),
    meta = tx.objectStore("meta"),
    summaries = tx.objectStore("monthlyStats");
  entries.clear();
  categories.clear();
  budgets.clear();
  summaries.clear();
  const months = new Map();
  for (const e of data.entries) {
    entries.put(indexedEntry(e));
    applyEntry(months, e, 1);
  }
  for (const summary of months.values()) summaries.put(summary);
  for (const c of data.categories) categories.put(c);
  const dining = diningCategoryRepair(data.categories);
  if (dining) categories.put(dining);
  for (const b of data.budgets) budgets.put(b);
  meta.put(data.asset, "asset");
  meta.put(data.preferences, "preferences");
  if (clearSessions) {
    tx.objectStore("assistantSessions").clear();
    meta.delete("activeAssistantSession");
  }
  await done(tx);
}
export async function clearLedger() {
  const empty = {
    schemaVersion: SCHEMA_VERSION,
    entries: [],
    categories: initialCategories(),
    budgets: [],
    asset: null,
    preferences: { hideAmountsByDefault: false },
  };
  await replaceLedger(empty, true);
}

export async function loadAssistantHistory() {
  const db = await openDatabase();
  const tx = db.transaction(["assistantSessions", "meta"], "readonly");
  const result = await Promise.all([
    request(tx.objectStore("assistantSessions").getAll()),
    request(tx.objectStore("meta").get("activeAssistantSession")),
    done(tx),
  ]);
  return { sessions: result[0], activeId: result[1] };
}

export async function saveAssistantSession(session) {
  const db = await openDatabase();
  const tx = db.transaction("assistantSessions", "readwrite");
  tx.objectStore("assistantSessions").put(session);
  await done(tx);
}
