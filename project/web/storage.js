import { initialCategories, SCHEMA_VERSION, validateLedger } from "./model.js";

const DB_NAME = "simple-ledger";
const DB_VERSION = 1;
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
      req.onupgradeneeded = () => {
        const db = req.result;
        db.createObjectStore("entries", { keyPath: "id" });
        db.createObjectStore("categories", { keyPath: "id" });
        db.createObjectStore("budgets", { keyPath: "yearMonth" });
        db.createObjectStore("meta");
        const tx = req.transaction;
        for (const c of initialCategories())
          tx.objectStore("categories").put(c);
        tx.objectStore("meta").put(
          { hideAmountsByDefault: false },
          "preferences",
        );
        tx.objectStore("meta").put(null, "asset");
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
      req.onblocked = () => reject(new Error("请关闭其他简账页面后重试"));
    });
  return dbPromise;
}
export async function loadLedger() {
  const db = await openDatabase();
  const tx = db.transaction(
    ["entries", "categories", "budgets", "meta"],
    "readonly",
  );
  const result = await Promise.all([
    request(tx.objectStore("entries").getAll()),
    request(tx.objectStore("categories").getAll()),
    request(tx.objectStore("budgets").getAll()),
    request(tx.objectStore("meta").get("asset")),
    request(tx.objectStore("meta").get("preferences")),
  ]);
  await done(tx);
  return {
    schemaVersion: SCHEMA_VERSION,
    entries: result[0],
    categories: result[1],
    budgets: result[2],
    asset: result[3] ?? null,
    preferences: result[4] ?? { hideAmountsByDefault: false },
  };
}
export async function putEntry(entry) {
  const db = await openDatabase(),
    tx = db.transaction("entries", "readwrite");
  tx.objectStore("entries").put(entry);
  await done(tx);
}
export async function removeEntry(id) {
  const db = await openDatabase(),
    tx = db.transaction("entries", "readwrite");
  tx.objectStore("entries").delete(id);
  await done(tx);
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
    tx = db.transaction("categories", "readwrite");
  tx.objectStore("categories").delete(id);
  await done(tx);
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
export async function replaceLedger(input) {
  const data = validateLedger(input),
    db = await openDatabase();
  const tx = db.transaction(
    ["entries", "categories", "budgets", "meta"],
    "readwrite",
  );
  const entries = tx.objectStore("entries"),
    categories = tx.objectStore("categories"),
    budgets = tx.objectStore("budgets"),
    meta = tx.objectStore("meta");
  entries.clear();
  categories.clear();
  budgets.clear();
  for (const e of data.entries) entries.put(e);
  for (const c of data.categories) categories.put(c);
  for (const b of data.budgets) budgets.put(b);
  meta.put(data.asset, "asset");
  meta.put(data.preferences, "preferences");
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
  await replaceLedger(empty);
}
