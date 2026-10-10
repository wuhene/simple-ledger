package com.wuhene.simpleledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LedgerDb extends SQLiteOpenHelper {
    public static final String EXPENSE = "EXPENSE", INCOME = "INCOME";
    private static final String[] EXPENSE_NAMES = "餐饮 购物 日用 交通 蔬菜 水果 零食 运动 娱乐 通讯 服饰 美容 住房 居家 孩子 长辈 社交 旅行 烟酒 数码 汽车 医疗 书籍 学习 宠物 礼金 礼物 办公 其它".split(" ");
    private static final String[] INCOME_NAMES = "工资 兼职 理财 礼金 其它 支付宝 微信".split(" ");

    public static final class Category {
        public String id, type, name, iconKey;
        public int sortIndex;
        public boolean hidden, builtin;
    }
    public static final class Entry {
        public String id, type, categoryId, businessDate, note, createdAt, updatedAt;
        public long amountMinor;
    }
    public static final class Totals {
        public long income, expense;
        public long balance() { return income - expense; }
    }

    public LedgerDb(Context context) { super(context, "simple-ledger.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY,type TEXT NOT NULL,name TEXT NOT NULL,icon_key TEXT NOT NULL,sort_index INTEGER NOT NULL,hidden INTEGER NOT NULL,builtin INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX category_name ON categories(type,name)");
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY,type TEXT NOT NULL,amount_minor INTEGER NOT NULL,category_id TEXT NOT NULL,business_date TEXT NOT NULL,note TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX entry_date ON entries(business_date DESC,created_at DESC)");
        db.execSQL("CREATE INDEX entry_category ON entries(category_id,business_date)");
        db.execSQL("CREATE TABLE budgets (year_month TEXT PRIMARY KEY,amount_minor INTEGER NOT NULL,updated_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        seed(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    private void seed(SQLiteDatabase db) {
        for (int i = 0; i < EXPENSE_NAMES.length; i++) insertCategory(db, category("expense-" + i, EXPENSE, EXPENSE_NAMES[i], i, false, true));
        for (int i = 0; i < INCOME_NAMES.length; i++) insertCategory(db, category("income-" + i, INCOME, INCOME_NAMES[i], i, i >= 5, true));
        putMeta(db, "hideAmountsByDefault", "false");
    }
    private static Category category(String id, String type, String name, int sort, boolean hidden, boolean builtin) {
        Category c = new Category(); c.id = id; c.type = type; c.name = name; c.iconKey = iconFor(name); c.sortIndex = sort; c.hidden = hidden; c.builtin = builtin; return c;
    }
    private static String iconFor(String name) {
        if (name.matches(".*(餐饮|蔬菜|水果|零食).*") ) return "food";
        if (name.matches(".*(购物|服饰|美容|日用).*") ) return "bag";
        if (name.matches(".*(住房|居家).*") ) return "home";
        if (name.matches(".*(交通|旅行).*") ) return "bus";
        if (name.matches(".*(礼金|礼物).*") ) return "gift";
        if (name.matches(".*(运动|娱乐|社交).*") ) return "heart";
        if (name.matches(".*(书籍|学习).*") ) return "book";
        if (name.matches(".*(工资|兼职|理财|办公).*") ) return "briefcase";
        if (name.contains("医疗")) return "medical";
        if (name.contains("宠物")) return "pet";
        if (name.contains("汽车")) return "car";
        return "other";
    }
    private static ContentValues categoryValues(Category c) {
        ContentValues v = new ContentValues(); v.put("id", c.id); v.put("type", c.type); v.put("name", c.name); v.put("icon_key", c.iconKey); v.put("sort_index", c.sortIndex); v.put("hidden", c.hidden ? 1 : 0); v.put("builtin", c.builtin ? 1 : 0); return v;
    }
    private static void insertCategory(SQLiteDatabase db, Category c) { db.insertOrThrow("categories", null, categoryValues(c)); }
    private static ContentValues entryValues(Entry e) {
        ContentValues v = new ContentValues(); v.put("id", e.id); v.put("type", e.type); v.put("amount_minor", e.amountMinor); v.put("category_id", e.categoryId); v.put("business_date", e.businessDate); v.put("note", e.note); v.put("created_at", e.createdAt); v.put("updated_at", e.updatedAt); return v;
    }
    private static Category readCategory(Cursor c) {
        Category x = new Category(); x.id = c.getString(0); x.type = c.getString(1); x.name = c.getString(2); x.iconKey = c.getString(3); x.sortIndex = c.getInt(4); x.hidden = c.getInt(5) != 0; x.builtin = c.getInt(6) != 0; return x;
    }
    private static Entry readEntry(Cursor c) {
        Entry x = new Entry(); x.id = c.getString(0); x.type = c.getString(1); x.amountMinor = c.getLong(2); x.categoryId = c.getString(3); x.businessDate = c.getString(4); x.note = c.getString(5); x.createdAt = c.getString(6); x.updatedAt = c.getString(7); return x;
    }

    public List<Category> categories(String type, boolean includeHidden) {
        List<Category> result = new ArrayList<>();
        String where = type == null ? null : "type=?";
        if (!includeHidden) where = where == null ? "hidden=0" : where + " AND hidden=0";
        try (Cursor c = getReadableDatabase().query("categories", null, where, type == null ? null : new String[]{type}, null, null, "sort_index,id")) {
            while (c.moveToNext()) result.add(readCategory(c));
        }
        return result;
    }
    public Category findCategory(String id) {
        try (Cursor c = getReadableDatabase().query("categories", null, "id=?", new String[]{id}, null, null, null)) { return c.moveToFirst() ? readCategory(c) : null; }
    }
    public void addCategory(String type, String name) {
        String clean = name.trim();
        if (!validType(type) || clean.isEmpty() || clean.codePointCount(0, clean.length()) > 8) throw new IllegalArgumentException("分类名称需为 1—8 个字");
        int sort = 0;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(sort_index),-1)+1 FROM categories WHERE type=?", new String[]{type})) { if (c.moveToFirst()) sort = c.getInt(0); }
        insertCategory(getWritableDatabase(), category(UUID.randomUUID().toString(), type, clean, sort, false, false));
    }
    public void renameCategory(String id, String name) {
        Category c = findCategory(id); if (c == null) throw new IllegalArgumentException("分类不存在");
        String clean = name.trim(); if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > 8) throw new IllegalArgumentException("分类名称需为 1—8 个字");
        ContentValues v = new ContentValues(); v.put("name", clean); getWritableDatabase().update("categories", v, "id=?", new String[]{id});
    }
    public void setCategoryHidden(String id, boolean hidden) {
        Category c = findCategory(id); if (c == null) return;
        if (hidden && categories(c.type, false).size() <= 1 && !c.hidden) throw new IllegalArgumentException("每种收支至少保留一个可用分类");
        ContentValues v = new ContentValues(); v.put("hidden", hidden ? 1 : 0); getWritableDatabase().update("categories", v, "id=?", new String[]{id});
    }
    public void setCategoryIcon(String id, String iconKey) {
        if (!iconKey.matches("food|bag|home|bus|gift|heart|book|briefcase|medical|pet|car|other")) throw new IllegalArgumentException("图标无效");
        ContentValues v = new ContentValues(); v.put("icon_key", iconKey); getWritableDatabase().update("categories", v, "id=?", new String[]{id});
    }
    public void moveCategory(String id, int delta) {
        Category target = findCategory(id); if (target == null) return;
        List<Category> list = categories(target.type, true); int index = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) { index = i; break; }
        int other = index + delta; if (index < 0 || other < 0 || other >= list.size()) return;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { ContentValues a = new ContentValues(), b = new ContentValues(); a.put("sort_index", list.get(other).sortIndex); b.put("sort_index", list.get(index).sortIndex); db.update("categories", a, "id=?", new String[]{id}); db.update("categories", b, "id=?", new String[]{list.get(other).id}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    public void deleteCategory(String id) {
        Category c = findCategory(id); if (c == null || c.builtin) throw new IllegalArgumentException("内置分类不可删除");
        if (entryCountForCategory(id) > 0) throw new IllegalArgumentException("已有记账引用，请先隐藏分类");
        if (!c.hidden && categories(c.type, false).size() <= 1) throw new IllegalArgumentException("每种收支至少保留一个可用分类");
        getWritableDatabase().delete("categories", "id=?", new String[]{id});
    }
    private long entryCountForCategory(String id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE category_id=?", new String[]{id})) { c.moveToFirst(); return c.getLong(0); }
    }

    public List<Entry> entries(String start, String end, String type, String categoryId) {
        StringBuilder where = new StringBuilder("1=1"); List<String> args = new ArrayList<>();
        if (start != null) { where.append(" AND business_date>=?"); args.add(start); }
        if (end != null) { where.append(" AND business_date<=?"); args.add(end); }
        if (type != null) { where.append(" AND type=?"); args.add(type); }
        if (categoryId != null) { where.append(" AND category_id=?"); args.add(categoryId); }
        List<Entry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("entries", null, where.toString(), args.toArray(new String[0]), null, null, "business_date DESC,created_at DESC,id DESC")) {
            while (c.moveToNext()) result.add(readEntry(c));
        }
        return result;
    }
    public Entry findEntry(String id) {
        try (Cursor c = getReadableDatabase().query("entries", null, "id=?", new String[]{id}, null, null, null)) { return c.moveToFirst() ? readEntry(c) : null; }
    }
    public void saveEntry(Entry e) {
        if (!validType(e.type) || e.amountMinor <= 0 || e.amountMinor > LedgerMath.MAX_MINOR || !LedgerMath.validDate(e.businessDate) || e.note == null || e.note.codePointCount(0, e.note.length()) > 200) throw new IllegalArgumentException("记账内容无效");
        Category c = findCategory(e.categoryId); if (c == null || !c.type.equals(e.type)) throw new IllegalArgumentException("请选择有效分类");
        String now = Instant.now().toString();
        if (e.id == null) { e.id = UUID.randomUUID().toString(); e.createdAt = now; }
        e.updatedAt = now;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.insertWithOnConflict("entries", null, entryValues(e), SQLiteDatabase.CONFLICT_REPLACE); putMeta(db, "lastCategory-" + e.type, e.categoryId); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    public void restoreEntry(Entry e) { getWritableDatabase().insertOrThrow("entries", null, entryValues(e)); }
    public String defaultCategory(String type) {
        String id = meta("lastCategory-" + type, ""); Category c = findCategory(id);
        return c != null && !c.hidden && c.type.equals(type) ? id : categories(type, false).get(0).id;
    }
    public void deleteEntry(String id) { getWritableDatabase().delete("entries", "id=?", new String[]{id}); }
    public Totals totals(String start, String end) {
        Totals t = new Totals();
        String sql = "SELECT type,COALESCE(SUM(amount_minor),0) FROM entries WHERE business_date>=? AND business_date<=? GROUP BY type";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{start, end})) {
            while (c.moveToNext()) { if (INCOME.equals(c.getString(0))) t.income = c.getLong(1); else t.expense = c.getLong(1); }
        }
        return t;
    }
    public long budget(String month) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT amount_minor FROM budgets WHERE year_month=?", new String[]{month})) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }
    public List<String> years() {
        List<String> years = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT substr(business_date,1,4) AS year FROM entries UNION SELECT DISTINCT substr(year_month,1,4) FROM budgets ORDER BY year DESC", null)) {
            while (c.moveToNext()) years.add(c.getString(0));
        }
        return years;
    }
    public void setBudget(String month, long minor) {
        if (!month.matches("\\d{4}-(0[1-9]|1[0-2])") || month.compareTo("1900-01") < 0 || minor < 0 || minor > LedgerMath.MAX_MINOR) throw new IllegalArgumentException("预算无效");
        if (minor == 0) { getWritableDatabase().delete("budgets", "year_month=?", new String[]{month}); return; }
        ContentValues v = new ContentValues(); v.put("year_month", month); v.put("amount_minor", minor); v.put("updated_at", Instant.now().toString()); getWritableDatabase().insertWithOnConflict("budgets", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public long asset(boolean liability) { return Long.parseLong(meta(liability ? "liabilityMinor" : "assetMinor", "0")); }
    public boolean hasAsset() { return !meta("assetUpdatedAt", "").isEmpty(); }
    public String assetUpdatedAt() { return meta("assetUpdatedAt", ""); }
    public void setAssets(long assets, long liabilities) {
        if (assets < 0 || liabilities < 0 || assets > LedgerMath.MAX_MINOR || liabilities > LedgerMath.MAX_MINOR) throw new IllegalArgumentException("资产金额无效");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { putMeta(db, "assetMinor", String.valueOf(assets)); putMeta(db, "liabilityMinor", String.valueOf(liabilities)); putMeta(db, "assetUpdatedAt", Instant.now().toString()); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    public boolean hideAmounts() { return Boolean.parseBoolean(meta("hideAmountsByDefault", "false")); }
    public void setHideAmounts(boolean hidden) { putMeta(getWritableDatabase(), "hideAmountsByDefault", String.valueOf(hidden)); }
    private String meta(String key, String fallback) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM meta WHERE key=?", new String[]{key})) { return c.moveToFirst() ? c.getString(0) : fallback; }
    }
    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues v = new ContentValues(); v.put("key", key); v.put("value", value); db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static boolean validType(String type) { return EXPENSE.equals(type) || INCOME.equals(type); }

    public JSONObject exportJson() throws JSONException {
        JSONObject root = new JSONObject(); root.put("schemaVersion", 1);
        JSONArray cats = new JSONArray();
        for (Category c : categories(null, true)) cats.put(new JSONObject().put("id", c.id).put("type", c.type).put("name", c.name).put("iconKey", c.iconKey).put("sortIndex", c.sortIndex).put("hidden", c.hidden).put("isBuiltin", c.builtin));
        root.put("categories", cats);
        JSONArray rows = new JSONArray();
        for (Entry e : entries(null, null, null, null)) rows.put(new JSONObject().put("id", e.id).put("type", e.type).put("amountMinor", e.amountMinor).put("categoryId", e.categoryId).put("businessDate", e.businessDate).put("note", e.note).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt));
        root.put("entries", rows);
        JSONArray budgets = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT year_month,amount_minor,updated_at FROM budgets ORDER BY year_month", null)) {
            while (c.moveToNext()) budgets.put(new JSONObject().put("yearMonth", c.getString(0)).put("amountMinor", c.getLong(1)).put("updatedAt", c.getString(2)));
        }
        root.put("budgets", budgets);
        String updated = meta("assetUpdatedAt", "");
        root.put("asset", updated.isEmpty() ? JSONObject.NULL : new JSONObject().put("assetMinor", asset(false)).put("liabilityMinor", asset(true)).put("updatedAt", updated));
        root.put("preferences", new JSONObject().put("hideAmountsByDefault", hideAmounts()));
        return root;
    }

    public void importJson(JSONObject root) throws JSONException {
        if (!root.has("schemaVersion") || !"1".equals(root.get("schemaVersion").toString())) throw new IllegalArgumentException("备份版本无效");
        JSONArray cats = root.getJSONArray("categories"), rows = root.getJSONArray("entries"), budgets = root.getJSONArray("budgets");
        JSONObject prefs = root.getJSONObject("preferences");
        if (!root.has("asset") || cats.length() > 1000 || rows.length() > 100000 || budgets.length() > 2000 || !(prefs.get("hideAmountsByDefault") instanceof Boolean)) throw new IllegalArgumentException("备份结构或记录数量无效");
        List<Category> validCats = new ArrayList<>(); List<Entry> validRows = new ArrayList<>(); Map<String, Category> catById = new HashMap<>(); Set<String> catIds = new HashSet<>(), catNames = new HashSet<>(), rowIds = new HashSet<>(), monthIds = new HashSet<>();
        boolean hasExpense = false, hasIncome = false;
        for (int i = 0; i < cats.length(); i++) {
            JSONObject x = cats.getJSONObject(i); Category c = new Category(); c.id = x.getString("id"); c.type = x.getString("type"); c.name = x.getString("name"); c.iconKey = x.getString("iconKey"); long sort = integer(x, "sortIndex"); if (sort < Integer.MIN_VALUE || sort > Integer.MAX_VALUE) throw new IllegalArgumentException("备份分类排序无效"); c.sortIndex = (int) sort; c.hidden = x.getBoolean("hidden"); c.builtin = x.getBoolean("isBuiltin");
            if (c.id.isEmpty() || !validType(c.type) || c.name.trim().isEmpty() || c.name.codePointCount(0, c.name.length()) > 8 || !c.iconKey.matches("food|bag|home|bus|gift|heart|book|briefcase|medical|pet|car|other") || !catIds.add(c.id) || !catNames.add(c.type + ":" + c.name)) throw new IllegalArgumentException("备份分类无效");
            if (!c.hidden) { if (EXPENSE.equals(c.type)) hasExpense = true; else hasIncome = true; }
            validCats.add(c); catById.put(c.id, c);
        }
        if (!hasExpense || !hasIncome) throw new IllegalArgumentException("每种收支至少需要一个可用分类");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject x = rows.getJSONObject(i); Entry e = new Entry(); e.id = x.getString("id"); e.type = x.getString("type"); e.amountMinor = integer(x, "amountMinor"); e.categoryId = x.getString("categoryId"); e.businessDate = x.getString("businessDate"); e.note = x.getString("note"); e.createdAt = x.getString("createdAt"); e.updatedAt = x.getString("updatedAt");
            Category cat = catById.get(e.categoryId);
            if (e.id.isEmpty() || !rowIds.add(e.id) || !validType(e.type) || e.amountMinor <= 0 || e.amountMinor > LedgerMath.MAX_MINOR || !LedgerMath.validDate(e.businessDate) || cat == null || !cat.type.equals(e.type) || e.note.codePointCount(0, e.note.length()) > 200 || !validInstant(e.createdAt) || !validInstant(e.updatedAt)) throw new IllegalArgumentException("备份流水无效或分类引用错误");
            validRows.add(e);
        }
        List<ContentValues> validBudgets = new ArrayList<>();
        for (int i = 0; i < budgets.length(); i++) {
            JSONObject x = budgets.getJSONObject(i); String month = x.getString("yearMonth"); long amount = integer(x, "amountMinor"); String updated = x.getString("updatedAt");
            if (!month.matches("\\d{4}-(0[1-9]|1[0-2])") || month.compareTo("1900-01") < 0 || !monthIds.add(month) || amount <= 0 || amount > LedgerMath.MAX_MINOR || !validInstant(updated)) throw new IllegalArgumentException("备份预算无效");
            ContentValues v = new ContentValues(); v.put("year_month", month); v.put("amount_minor", amount); v.put("updated_at", updated); validBudgets.add(v);
        }
        JSONObject asset = root.isNull("asset") ? null : root.getJSONObject("asset");
        if (asset != null && (integer(asset, "assetMinor") < 0 || integer(asset, "assetMinor") > LedgerMath.MAX_MINOR || integer(asset, "liabilityMinor") < 0 || integer(asset, "liabilityMinor") > LedgerMath.MAX_MINOR || !validInstant(asset.getString("updatedAt")))) throw new IllegalArgumentException("备份资产无效");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("entries", null, null); db.delete("categories", null, null); db.delete("budgets", null, null); db.delete("meta", null, null);
            for (Category c : validCats) insertCategory(db, c);
            for (Entry e : validRows) db.insertOrThrow("entries", null, entryValues(e));
            for (ContentValues v : validBudgets) db.insertOrThrow("budgets", null, v);
            putMeta(db, "hideAmountsByDefault", String.valueOf(prefs.getBoolean("hideAmountsByDefault")));
            if (asset != null) { putMeta(db, "assetMinor", String.valueOf(integer(asset, "assetMinor"))); putMeta(db, "liabilityMinor", String.valueOf(integer(asset, "liabilityMinor"))); putMeta(db, "assetUpdatedAt", asset.getString("updatedAt")); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private static boolean validInstant(String text) { try { Instant.parse(text); return true; } catch (Exception e) { return false; } }
    private static long integer(JSONObject object, String key) throws JSONException {
        Object value = object.get(key); if (!(value instanceof Number) || !value.toString().matches("-?\\d+")) throw new IllegalArgumentException("备份金额或序号必须为整数");
        try { return Long.parseLong(value.toString()); } catch (NumberFormatException e) { throw new IllegalArgumentException("备份整数超出范围"); }
    }
    public void clear() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.delete("entries", null, null); db.delete("categories", null, null); db.delete("budgets", null, null); db.delete("meta", null, null); seed(db); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
}
