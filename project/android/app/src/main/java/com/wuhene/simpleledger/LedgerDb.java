package com.wuhene.simpleledger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.Comparator;
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
        public String sourceText;
        public JSONArray clues;
        public JSONObject location;
    }
    public static final class Totals {
        public long income, expense;
        public long balance() { return income - expense; }
    }

    /**
     * 打开简账本机数据库。
     */
    public LedgerDb(Context context) { super(context, "simple-ledger.db", null, 3); }

    /**
     * 建立账本与索引表。
     */
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories (id TEXT PRIMARY KEY,type TEXT NOT NULL,name TEXT NOT NULL,icon_key TEXT NOT NULL,sort_index INTEGER NOT NULL,hidden INTEGER NOT NULL,builtin INTEGER NOT NULL)");
        db.execSQL("CREATE UNIQUE INDEX category_name ON categories(type,name)");
        db.execSQL("CREATE TABLE entries (id TEXT PRIMARY KEY,type TEXT NOT NULL,amount_minor INTEGER NOT NULL,category_id TEXT NOT NULL,business_date TEXT NOT NULL,note TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX entry_date ON entries(business_date DESC,created_at DESC)");
        db.execSQL("CREATE INDEX entry_category ON entries(category_id,business_date)");
        db.execSQL("CREATE TABLE budgets (year_month TEXT PRIMARY KEY,amount_minor INTEGER NOT NULL,updated_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        createDerivedTables(db);
        seed(db);
    }

    /**
     * 为旧流水补齐扩展字段并重建索引。
     */
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createDerivedTables(db);
            try (Cursor rows = db.query("entries", null, null, null, null, null, null);
                 SQLiteStatement add = termStatement(db)) {
                while (rows.moveToNext()) addTerms(add, readEntry(rows));
            }
            db.execSQL("INSERT INTO monthly_stats SELECT substr(business_date,1,7), COUNT(*), "
                    + "SUM(CASE WHEN type='INCOME' THEN amount_minor ELSE 0 END), "
                    + "SUM(CASE WHEN type='EXPENSE' THEN amount_minor ELSE 0 END) FROM entries GROUP BY 1");
            repairDining(db);
        } else if (oldVersion < 3) {
            try (Cursor rows = db.query("entries", null, "location IS NOT NULL", null, null, null, null)) {
                while (rows.moveToNext()) replaceTerms(db, readEntry(rows));
            }
        }
    }

    private void seed(SQLiteDatabase db) {
        for (int i = 0; i < EXPENSE_NAMES.length; i++) insertCategory(db, category("expense-" + i, EXPENSE, EXPENSE_NAMES[i], i, false, true));
        for (int i = 0; i < INCOME_NAMES.length; i++) insertCategory(db, category("income-" + i, INCOME, INCOME_NAMES[i], i, i >= 5, true));
        putMeta(db, "hideAmountsByDefault", "false");
    }
    private static Category category(String id, String type, String name, int sort, boolean hidden, boolean builtin) {
        Category c = new Category(); c.id = id; c.type = type; c.name = name; c.iconKey = iconFor(name); c.sortIndex = sort; c.hidden = hidden; c.builtin = builtin; return c;
    }
    /**
     * 返回分类名称对应的旧版默认图标。
     */
    static String iconFor(String name) {
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
    /**
     * 序列化流水的账务字段和可选线索。
     */
    private static ContentValues entryValues(Entry e) {
        ContentValues v = new ContentValues(); v.put("id", e.id); v.put("type", e.type); v.put("amount_minor", e.amountMinor); v.put("category_id", e.categoryId); v.put("business_date", e.businessDate); v.put("note", e.note); v.put("created_at", e.createdAt); v.put("updated_at", e.updatedAt);
        v.put("source_text", e.sourceText);
        v.put("clues", e.clues == null ? null : e.clues.toString());
        v.put("location", e.location == null ? null : e.location.toString());
        return v;
    }
    private static Category readCategory(Cursor c) {
        Category x = new Category(); x.id = c.getString(0); x.type = c.getString(1); x.name = c.getString(2); x.iconKey = c.getString(3); x.sortIndex = c.getInt(4); x.hidden = c.getInt(5) != 0; x.builtin = c.getInt(6) != 0; return x;
    }
    /**
     * 从 SQLite 流水行恢复完整账务记录。
     */
    private static Entry readEntry(Cursor c) {
        Entry x = new Entry(); x.id = c.getString(0); x.type = c.getString(1); x.amountMinor = c.getLong(2); x.categoryId = c.getString(3); x.businessDate = c.getString(4); x.note = c.getString(5); x.createdAt = c.getString(6); x.updatedAt = c.getString(7);
        if (c.getColumnCount() == 8) return x;
        x.sourceText = c.getString(8);
        try {
            if (!c.isNull(9)) x.clues = new JSONArray(c.getString(9));
            if (!c.isNull(10)) x.location = new JSONObject(c.getString(10));
        } catch (JSONException error) {
            throw new IllegalStateException("流水扩展字段损坏", error);
        }
        return x;
    }

    public List<Category> categories(String type, boolean includeHidden) {
        List<Category> result = new ArrayList<>();
        String where = type == null ? null : "type=?";
        if (!includeHidden) where = where == null ? "hidden=0" : where + " AND hidden=0";
        try (Cursor c = getReadableDatabase().query("categories", null, where, type == null ? null : new String[]{type}, null, null,
                "CASE WHEN type='EXPENSE' AND name='餐饮' THEN 0 ELSE 1 END,sort_index,id")) {
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
    /**
     * 修改可编辑的分类名称。
     */
    public void renameCategory(String id, String name) {
        Category c = findCategory(id); if (c == null) throw new IllegalArgumentException("分类不存在");
        if (isDining(c)) throw new IllegalArgumentException("餐饮分类固定在首位");
        String clean = name.trim(); if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > 8) throw new IllegalArgumentException("分类名称需为 1—8 个字");
        ContentValues v = new ContentValues(); v.put("name", clean); getWritableDatabase().update("categories", v, "id=?", new String[]{id});
    }
    /**
     * 调整分类是否在记账页展示。
     */
    public void setCategoryHidden(String id, boolean hidden) {
        Category c = findCategory(id); if (c == null) return;
        if (hidden && isDining(c)) throw new IllegalArgumentException("餐饮分类不可隐藏");
        if (hidden && categories(c.type, false).size() <= 1 && !c.hidden) throw new IllegalArgumentException("每种收支至少保留一个可用分类");
        ContentValues v = new ContentValues(); v.put("hidden", hidden ? 1 : 0); getWritableDatabase().update("categories", v, "id=?", new String[]{id});
    }
    public void setCategoryIcon(String id, String iconKey) {
        if (!iconKey.matches("food|bag|home|bus|gift|heart|book|briefcase|medical|pet|car|other")) throw new IllegalArgumentException("图标无效");
        ContentValues v = new ContentValues(); v.put("icon_key", iconKey); getWritableDatabase().update("categories", v, "id=?", new String[]{id});
    }
    /**
     * 在同类可排序项目之间调整分类顺序。
     */
    public void moveCategory(String id, int delta) {
        Category target = findCategory(id); if (target == null) return;
        if (isDining(target)) return;
        List<Category> list = categories(target.type, true); int index = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) { index = i; break; }
        int other = index + delta; if (index < 0 || other < 0 || other >= list.size()) return;
        if (isDining(list.get(other))) return;
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
    /**
     * 保存流水并在同一个事务中更新派生索引。
     */
    public void saveEntry(Entry e) {
        if (!validType(e.type) || e.amountMinor <= 0 || e.amountMinor > LedgerMath.MAX_MINOR || !LedgerMath.validDate(e.businessDate) || e.note == null || e.note.codePointCount(0, e.note.length()) > 200) throw new IllegalArgumentException("记账内容无效");
        Category c = findCategory(e.categoryId); if (c == null || !c.type.equals(e.type)) throw new IllegalArgumentException("请选择有效分类");
        validateDetails(e);
        String now = Instant.now().toString();
        if (e.id == null) { e.id = UUID.randomUUID().toString(); e.createdAt = now; }
        e.updatedAt = now;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (db.update("entries", entryValues(e), "id=?", new String[]{e.id}) == 0) db.insertOrThrow("entries", null, entryValues(e));
            replaceTerms(db, e);
            db.setTransactionSuccessful();
        }
        finally { db.endTransaction(); }
    }
    /**
     * 恢复已删除的原始流水与搜索词元。
     */
    public void restoreEntry(Entry e) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.insertOrThrow("entries", null, entryValues(e));
            replaceTerms(db, e);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public String defaultCategory(String type) {
        String id = meta("lastCategory-" + type, ""); Category c = findCategory(id);
        return c != null && !c.hidden && c.type.equals(type) ? id : categories(type, false).get(0).id;
    }
    public void deleteEntry(String id) { getWritableDatabase().delete("entries", "id=?", new String[]{id}); }
    /**
     * 累加完整月份汇总和两端不完整月份的流水。
     */
    public Totals totals(String start, String end) {
        Totals t = new Totals();
        LocalDate first = LocalDate.parse(start), last = LocalDate.parse(end);
        if (first.isAfter(last)) return t;
        YearMonth from = YearMonth.from(first), to = YearMonth.from(last);
        YearMonth wholeFrom = first.getDayOfMonth() == 1 ? from : from.plusMonths(1);
        YearMonth wholeTo = last.getDayOfMonth() == to.lengthOfMonth() ? to : to.minusMonths(1);
        if (wholeFrom.isAfter(wholeTo)) {
            addPartialTotals(t, start, end);
            return t;
        }
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(income),0),COALESCE(SUM(expense),0) FROM monthly_stats WHERE month BETWEEN ? AND ?",
                new String[]{wholeFrom.toString(), wholeTo.toString()})) {
            c.moveToFirst();
            t.income = c.getLong(0);
            t.expense = c.getLong(1);
        }
        if (from.isBefore(wholeFrom)) addPartialTotals(t, start, from.atEndOfMonth().toString());
        if (to.isAfter(wholeTo)) addPartialTotals(t, to.atDay(1).toString(), end);
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

    /**
     * 生成与 Web 兼容的单文件 JSON 账本。
     */
    public JSONObject exportJson() throws JSONException {
        JSONObject root = exportMetadata();
        JSONArray rows = new JSONArray();
        for (Entry entry : entries(null, null, null, null)) rows.put(entryJson(entry));
        return root.put("entries", rows);
    }

    /**
     * 导出不含派生索引及会话的账本元数据。
     */
    public JSONObject exportMetadata() throws JSONException {
        JSONObject root = new JSONObject(); root.put("schemaVersion", 1);
        JSONArray cats = new JSONArray();
        for (Category c : categories(null, true)) cats.put(new JSONObject().put("id", c.id).put("type", c.type).put("name", c.name).put("iconKey", c.iconKey).put("sortIndex", c.sortIndex).put("hidden", c.hidden).put("isBuiltin", c.builtin));
        root.put("categories", cats);
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

    /**
     * 校验并原子替换账本数据。
     */
    public void importJson(JSONObject root) throws JSONException {
        processBackup(root, this);
    }

    /**
     * 验证备份内容但不写入账本。
     */
    public static void validateBackup(JSONObject root) throws JSONException {
        processBackup(root, null);
    }

    /**
     * 解析、校验并按需写入备份。
     */
    private static void processBackup(JSONObject root, LedgerDb destination) throws JSONException {
        if (!root.has("schemaVersion") || !"1".equals(root.get("schemaVersion").toString())) throw new IllegalArgumentException("备份版本无效");
        JSONArray cats = root.getJSONArray("categories"), rows = root.getJSONArray("entries"), budgets = root.getJSONArray("budgets");
        JSONObject prefs = root.getJSONObject("preferences");
        if (!root.has("asset") || cats.length() > 1000 || rows.length() > 120000 || budgets.length() > 2000 || !(prefs.get("hideAmountsByDefault") instanceof Boolean)) throw new IllegalArgumentException("备份结构或记录数量无效");
        List<Category> validCats = new ArrayList<>(); Map<String, Category> catById = new HashMap<>(); Set<String> catIds = new HashSet<>(), catNames = new HashSet<>(), rowIds = new HashSet<>(), monthIds = new HashSet<>();
        boolean hasExpense = false, hasIncome = false;
        for (int i = 0; i < cats.length(); i++) {
            JSONObject x = cats.getJSONObject(i); Category c = new Category(); c.id = x.getString("id"); c.type = x.getString("type"); c.name = x.getString("name"); c.iconKey = x.getString("iconKey"); long sort = integer(x, "sortIndex"); if (sort < Integer.MIN_VALUE || sort > Integer.MAX_VALUE) throw new IllegalArgumentException("备份分类排序无效"); c.sortIndex = (int) sort; c.hidden = x.getBoolean("hidden"); c.builtin = x.getBoolean("isBuiltin");
            if (c.id.isEmpty() || !validType(c.type) || c.name.trim().isEmpty() || c.name.codePointCount(0, c.name.length()) > 8 || !c.iconKey.matches("food|bag|home|bus|gift|heart|book|briefcase|medical|pet|car|other") || !catIds.add(c.id) || !catNames.add(c.type + ":" + c.name)) throw new IllegalArgumentException("备份分类无效");
            if (!c.hidden) { if (EXPENSE.equals(c.type)) hasExpense = true; else hasIncome = true; }
            validCats.add(c); catById.put(c.id, c);
        }
        if (!hasExpense || !hasIncome) throw new IllegalArgumentException("每种收支至少需要一个可用分类");
        for (int i = 0; i < rows.length(); i++) {
            Entry e = backupEntry(rows.getJSONObject(i));
            validateDetails(e);
            Category cat = catById.get(e.categoryId);
            if (e.id.isEmpty() || !rowIds.add(e.id) || !validType(e.type) || e.amountMinor <= 0 || e.amountMinor > LedgerMath.MAX_MINOR || !LedgerMath.validDate(e.businessDate) || cat == null || !cat.type.equals(e.type) || e.note.codePointCount(0, e.note.length()) > 200 || !validInstant(e.createdAt) || !validInstant(e.updatedAt)) throw new IllegalArgumentException("备份流水无效或分类引用错误");
        }
        List<ContentValues> validBudgets = new ArrayList<>();
        for (int i = 0; i < budgets.length(); i++) {
            JSONObject x = budgets.getJSONObject(i); String month = x.getString("yearMonth"); long amount = integer(x, "amountMinor"); String updated = x.getString("updatedAt");
            if (!month.matches("\\d{4}-(0[1-9]|1[0-2])") || month.compareTo("1900-01") < 0 || !monthIds.add(month) || amount <= 0 || amount > LedgerMath.MAX_MINOR || !validInstant(updated)) throw new IllegalArgumentException("备份预算无效");
            if (destination != null) {
                ContentValues v = new ContentValues();
                v.put("year_month", month); v.put("amount_minor", amount); v.put("updated_at", updated); validBudgets.add(v);
            }
        }
        JSONObject asset = root.isNull("asset") ? null : root.getJSONObject("asset");
        if (asset != null && (integer(asset, "assetMinor") < 0 || integer(asset, "assetMinor") > LedgerMath.MAX_MINOR || integer(asset, "liabilityMinor") < 0 || integer(asset, "liabilityMinor") > LedgerMath.MAX_MINOR || !validInstant(asset.getString("updatedAt")))) throw new IllegalArgumentException("备份资产无效");
        if (destination == null) return;
        SQLiteDatabase db = destination.getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("entries", null, null); db.delete("categories", null, null); db.delete("budgets", null, null);
            db.delete("meta", "key IN ('assetMinor','liabilityMinor','assetUpdatedAt','hideAmountsByDefault')", null);
            for (Category c : validCats) insertCategory(db, c);
            destination.repairDining(db);
            try (SQLiteStatement add = termStatement(db)) {
                for (int i = 0; i < rows.length(); i++) {
                    Entry e = backupEntry(rows.getJSONObject(i));
                    db.insertOrThrow("entries", null, entryValues(e));
                    addTerms(add, e);
                }
            }
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
    /**
     * 清空账本与会话并恢复初始分类。
     */
    public void clear() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.delete("entries", null, null); db.delete("categories", null, null); db.delete("budgets", null, null); db.delete("meta", null, null); db.delete("assistant_messages", null, null); db.delete("assistant_sessions", null, null); seed(db); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }

    /**
     * 建立流水扩展、倒排索引、月汇总及会话表。
     */
    private void createDerivedTables(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE entries ADD COLUMN source_text TEXT");
        db.execSQL("ALTER TABLE entries ADD COLUMN clues TEXT");
        db.execSQL("ALTER TABLE entries ADD COLUMN location TEXT");
        db.execSQL("CREATE TABLE entry_terms(token TEXT NOT NULL,business_date TEXT NOT NULL,entry_id TEXT NOT NULL, "
                + "PRIMARY KEY(token,business_date,entry_id)) WITHOUT ROWID");
        db.execSQL("CREATE INDEX entry_terms_id ON entry_terms(entry_id)");
        db.execSQL("CREATE TABLE monthly_stats(month TEXT PRIMARY KEY,count INTEGER NOT NULL,income INTEGER NOT NULL,expense INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE assistant_sessions(id TEXT PRIMARY KEY,updated_at TEXT NOT NULL,payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE assistant_messages(session_id TEXT NOT NULL,position INTEGER NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(session_id,position)) WITHOUT ROWID");
        String add = "INSERT OR IGNORE INTO monthly_stats VALUES(substr(NEW.business_date,1,7),0,0,0); "
                + "UPDATE monthly_stats SET count=count+1,income=income+CASE WHEN NEW.type='INCOME' THEN NEW.amount_minor ELSE 0 END,"
                + "expense=expense+CASE WHEN NEW.type='EXPENSE' THEN NEW.amount_minor ELSE 0 END WHERE month=substr(NEW.business_date,1,7); ";
        String remove = "UPDATE monthly_stats SET count=count-1,income=income-CASE WHEN OLD.type='INCOME' THEN OLD.amount_minor ELSE 0 END,"
                + "expense=expense-CASE WHEN OLD.type='EXPENSE' THEN OLD.amount_minor ELSE 0 END WHERE month=substr(OLD.business_date,1,7); "
                + "DELETE FROM monthly_stats WHERE month=substr(OLD.business_date,1,7) AND count=0; ";
        db.execSQL("CREATE TRIGGER entry_add AFTER INSERT ON entries BEGIN " + add + "END");
        db.execSQL("CREATE TRIGGER entry_adjust AFTER UPDATE ON entries BEGIN " + remove + add + "END");
        db.execSQL("CREATE TRIGGER entry_remove AFTER DELETE ON entries BEGIN " + remove + "DELETE FROM entry_terms WHERE entry_id=OLD.id; END");
    }

    /**
     * 兼容旧账本中隐藏或重命名的餐饮分类。
     */
    private void repairDining(SQLiteDatabase db) {
        ContentValues visible = new ContentValues();
        visible.put("hidden", 0);
        if (db.update("categories", visible, "type='EXPENSE' AND name='餐饮'", null) > 0) return;
        String id = "expense-0";
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM categories WHERE id=?", new String[]{id})) {
            if (cursor.moveToFirst()) id = UUID.randomUUID().toString();
        }
        insertCategory(db, category(id, EXPENSE, "餐饮", 0, false, true));
    }

    /**
     * 判断分类是否为固定餐饮项。
     */
    public static boolean isDining(Category category) {
        return category != null && EXPENSE.equals(category.type) && "餐饮".equals(category.name);
    }

    /**
     * 准备批量写入索引词元的语句。
     */
    private static SQLiteStatement termStatement(SQLiteDatabase db) {
        return db.compileStatement("INSERT INTO entry_terms(token,business_date,entry_id) VALUES(?,?,?)");
    }

    /**
     * 为一笔流水写入去重词元。
     */
    private static void addTerms(SQLiteStatement statement, Entry entry) {
        statement.bindString(2, entry.businessDate);
        statement.bindString(3, entry.id);
        for (String token : LedgerIndex.tokens(entry)) {
            statement.bindString(1, token);
            statement.executeInsert();
        }
    }

    /**
     * 替换被修改流水的搜索词元。
     */
    private static void replaceTerms(SQLiteDatabase db, Entry entry) {
        db.delete("entry_terms", "entry_id=?", new String[]{entry.id});
        try (SQLiteStatement statement = termStatement(db)) {
            addTerms(statement, entry);
        }
    }

    /**
     * 使用日期和词元索引查询并精确核对候选流水。
     */
    public List<Entry> search(String start, String end, String type, String categoryId, List<String> terms, JSONArray filters) {
        if (start == null) start = "1900-01-01";
        if (end == null) end = "9999-12-31";
        Set<String> keys = LedgerIndex.candidates(terms, filters);
        if (keys.isEmpty() && (filters == null || filters.length() == 0) && terms.isEmpty()) return entries(start, end, type, categoryId);
        SQLiteDatabase db = getReadableDatabase();
        String selected = null;
        long count = Long.MAX_VALUE;
        for (String key : keys) {
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM entry_terms WHERE token=? AND business_date>=? AND business_date<=?",
                    new String[]{key, start, end})) {
                c.moveToFirst();
                if (c.getLong(0) < count) { count = c.getLong(0); selected = key; }
            }
            if (count == 0) return Collections.emptyList();
        }
        List<Entry> candidates;
        if (selected == null) candidates = entries(start, end, type, categoryId);
        else {
            candidates = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT e.* FROM entry_terms t JOIN entries e ON t.entry_id=e.id"
                    + " WHERE t.token=? AND t.business_date>=? AND t.business_date<=?",
                    new String[]{selected, start, end})) {
                while (c.moveToNext()) candidates.add(readEntry(c));
            }
        }
        Map<String, Integer> scores = new HashMap<>();
        candidates.removeIf(entry -> {
            if ((type != null && !type.equals(entry.type)) || (categoryId != null && !categoryId.equals(entry.categoryId))) return true;
            int score = LedgerIndex.score(entry, terms, filters);
            if (score < 0) return true;
            scores.put(entry.id, score);
            return false;
        });
        candidates.sort(Comparator.comparingInt((Entry entry) -> scores.get(entry.id)).reversed()
                .thenComparing(entry -> entry.businessDate, Comparator.reverseOrder())
                .thenComparing(entry -> entry.createdAt, Comparator.reverseOrder()).thenComparing(entry -> entry.id));
        return candidates;
    }

    /**
     * 序列化可跨平台恢复的一笔流水。
     */
    static JSONObject entryJson(Entry e) throws JSONException {
        JSONObject row = new JSONObject().put("id", e.id).put("type", e.type).put("amountMinor", e.amountMinor)
                .put("categoryId", e.categoryId).put("businessDate", e.businessDate).put("note", e.note)
                .put("createdAt", e.createdAt).put("updatedAt", e.updatedAt);
        if (e.sourceText != null) row.put("sourceText", e.sourceText);
        if (e.clues != null) row.put("clues", e.clues);
        if (e.location != null) row.put("location", e.location);
        return row;
    }

    /**
     * 拒绝损坏或过大的扩展字段。
     */
    private static void validateDetails(Entry e) {
        if (e.sourceText != null && e.sourceText.length() > 1200) throw new IllegalArgumentException("记账原话过长");
        if (e.clues != null) {
            if (e.clues.length() > 12) throw new IllegalArgumentException("检索线索过多");
            for (int i = 0; i < e.clues.length(); i++) {
                JSONObject clue = e.clues.optJSONObject(i);
                if (clue == null || !(clue.opt("type") instanceof String) || !(clue.opt("value") instanceof String)
                        || clue.optString("type").trim().isEmpty() || clue.optString("type").length() > 24
                        || clue.optString("value").trim().isEmpty() || clue.optString("value").length() > 120) {
                    throw new IllegalArgumentException("检索线索无效");
                }
            }
        }
        if (e.location != null && (!Double.isFinite(e.location.optDouble("latitude")) || Math.abs(e.location.optDouble("latitude")) > 90
                || !Double.isFinite(e.location.optDouble("longitude")) || Math.abs(e.location.optDouble("longitude")) > 180
                || !Double.isFinite(e.location.optDouble("accuracy")) || e.location.optDouble("accuracy") < 0
                || !validInstant(e.location.optString("capturedAt")))) throw new IllegalArgumentException("定位信息无效");
    }

    /**
     * 返回有流水的月份以便按月导出。
     */
    public List<String> ledgerMonths() {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT month FROM monthly_stats ORDER BY month", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }

    /**
     * 给出整个账本的流水数。
     */
    public long entryCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(SUM(count),0) FROM monthly_stats", null)) {
            c.moveToFirst();
            return c.getLong(0);
        }
    }

    /**
     * 装载本机保存的聊天会话。
     */
    public List<JSONObject> assistantSessions() {
        List<JSONObject> sessions = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT payload FROM assistant_sessions ORDER BY updated_at DESC", null)) {
            while (c.moveToNext()) sessions.add(new JSONObject(c.getString(0)));
        } catch (JSONException error) {
            throw new IllegalStateException("聊天记录损坏", error);
        }
        return sessions;
    }

    /**
     * 持久化 AI 会话及其草稿和结构化回复。
     */
    public void saveAssistantSession(JSONObject session, JSONArray chat, int changedFrom) {
        String id = session.optString("id");
        ContentValues data = new ContentValues();
        data.put("id", id);
        data.put("updated_at", Instant.now().toString());
        data.put("payload", session.toString());
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.replaceOrThrow("assistant_sessions", null, data);
            for (int i = changedFrom; i < chat.length(); i++) {
                JSONObject item = chat.optJSONObject(i);
                ContentValues message = new ContentValues();
                message.put("session_id", id);
                message.put("position", i);
                message.put("payload", item.toString());
                db.replaceOrThrow("assistant_messages", null, message);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /**
     * 读取最近打开的会话 ID。
     */
    public String activeAssistantSession() {
        return meta("activeAssistantSession", "");
    }

    /**
     * 记录最近打开的会话 ID。
     */
    public void setActiveAssistantSession(String id) {
        putMeta(getWritableDatabase(), "activeAssistantSession", id);
    }

    /**
     * 在单个事务内撤销尚未改动的流水。
     */
    public void deleteUnchanged(String id, String updatedAt) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Entry entry = findEntry(id);
            if (entry != null && !updatedAt.equals(entry.updatedAt)) throw new IllegalArgumentException("该账单已修改，请在明细中处理");
            db.delete("entries", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    /**
     * 累加不足完整月份的区间。
     */
    private void addPartialTotals(Totals totals, String start, String end) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT type,SUM(amount_minor) FROM entries WHERE business_date BETWEEN ? AND ? GROUP BY type",
                new String[]{start, end})) {
            while (c.moveToNext()) {
                if (INCOME.equals(c.getString(0))) totals.income += c.getLong(1);
                else totals.expense += c.getLong(1);
            }
        }
    }

    /**
     * 查询有流水的业务日期。
     */
    public Set<String> recordedDates() {
        Set<String> dates = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT business_date FROM entries", null)) {
            while (c.moveToNext()) dates.add(c.getString(0));
        }
        return dates;
    }

    /**
     * 将分类移动到同组目标位置并维护持久顺序。
     */
    public void moveCategoryTo(String id, String destination) {
        Category source = findCategory(id);
        Category target = findCategory(destination);
        if (source == null || target == null || source.id.equals(target.id) || isDining(source) || isDining(target)
                || !source.type.equals(target.type) || source.hidden != target.hidden) return;
        List<Category> categories = categories(source.type, true);
        int from = -1, to = -1;
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id.equals(id)) from = i;
            if (categories.get(i).id.equals(destination)) to = i;
        }
        if (from == -1 || to == -1) return;
        categories.add(to, categories.remove(from));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < categories.size(); i++) {
                ContentValues order = new ContentValues();
                order.put("sort_index", i);
                db.update("categories", order, "id=?", new String[]{categories.get(i).id});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /**
     * 将备份流水还原为索引与存储共用的记录。
     */
    static Entry backupEntry(JSONObject row) throws JSONException {
        Entry entry = new Entry();
        entry.id = row.getString("id");
        entry.type = row.getString("type");
        entry.amountMinor = integer(row, "amountMinor");
        entry.categoryId = row.getString("categoryId");
        entry.businessDate = row.getString("businessDate");
        entry.note = row.getString("note");
        entry.createdAt = row.getString("createdAt");
        entry.updatedAt = row.getString("updatedAt");
        entry.sourceText = row.isNull("sourceText") ? null : row.getString("sourceText");
        entry.clues = row.isNull("clues") ? null : row.getJSONArray("clues");
        entry.location = row.isNull("location") ? null : row.getJSONObject("location");
        return entry;
    }

    /**
     * 读取统计所需字段并跳过扩展数据反序列化。
     */
    public List<Entry> statsEntries(String start, String end, String type) {
        String[] columns = {"id", "type", "amount_minor", "category_id", "business_date", "note", "created_at", "updated_at"};
        String selection = "business_date BETWEEN ? AND ?" + (type == null ? "" : " AND type=?");
        String[] args = type == null ? new String[]{start, end} : new String[]{start, end, type};
        List<Entry> rows = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("entries", columns, selection, args, null, null, "business_date DESC,created_at DESC,id DESC")) {
            while (cursor.moveToNext()) rows.add(readEntry(cursor));
        }
        return rows;
    }

    /**
     * 从逐条存储的记录恢复指定会话的正文和卡片。
     */
    public JSONObject loadAssistantSession(String id) {
        if (id.isEmpty()) return null;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor session = db.rawQuery("SELECT payload FROM assistant_sessions WHERE id=?", new String[]{id})) {
            if (!session.moveToFirst()) return null;
            JSONObject result = new JSONObject(session.getString(0));
            JSONArray messages = new JSONArray();
            try (Cursor rows = db.rawQuery("SELECT payload FROM assistant_messages WHERE session_id=? ORDER BY position", new String[]{id})) {
                while (rows.moveToNext()) messages.put(new JSONObject(rows.getString(0)));
            }
            return result.put("chat", messages);
        } catch (JSONException error) {
            throw new IllegalStateException("聊天记录损坏", error);
        }
    }

    /**
     * 读取 AI 自动记账偏好。
     */
    boolean assistantAutoBooking() {
        return Boolean.parseBoolean(meta("assistantAutoBooking", "false"));
    }

    /**
     * 保存 AI 自动记账偏好。
     */
    void setAssistantAutoBooking(boolean enabled) {
        putMeta(getWritableDatabase(), "assistantAutoBooking", String.valueOf(enabled));
    }

    /**
     * 读取 AI 入账定位偏好。
     */
    boolean assistantAutoLocation() {
        return Boolean.parseBoolean(meta("assistantAutoLocation", "false"));
    }

    /**
     * 保存 AI 入账定位偏好。
     */
    void setAssistantAutoLocation(boolean enabled) {
        putMeta(getWritableDatabase(), "assistantAutoLocation", String.valueOf(enabled));
    }

    /**
     * 用纬度词元筛选候选并核验精确距离。
     */
    List<Entry> nearby(String start, String end, JSONObject origin, double km) {
        double latitude = origin.optDouble("latitude"), span = LedgerLocation.latitudeSpan(km);
        List<Entry> entries = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT e.* FROM entry_terms t JOIN entries e ON t.entry_id=e.id "
                + "WHERE t.token BETWEEN ? AND ? AND t.business_date BETWEEN ? AND ?",
                new String[]{LedgerLocation.latitudeKey(latitude - span), LedgerLocation.latitudeKey(latitude + span), start, end})) {
            while (rows.moveToNext()) {
                Entry entry = readEntry(rows);
                if (LedgerLocation.within(origin, entry.location, km)) entries.add(entry);
            }
        }
        return entries;
    }
}
