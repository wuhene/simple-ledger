package com.wuhene.simpleledger;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保存 AI 会话，在本地执行模型返回的记账和查询意图。
 *
 * @author chaoshunh
 * @date 2026/11/25
 */
final class LedgerAssistant {
    private static final String BLOCKS = "|entries|totals|categories|comparison|groupRank|";
    private static final Pattern RANK = Pattern.compile("(?:前|top\\s*)([0-9]{1,3}|[一二三四五六七八九]?十[一二三四五六七八九]?|[一二三四五六七八九])(?=\\s*(?:[笔条项名]|个(?!月|年|星)|[。？！]|$))", Pattern.CASE_INSENSITIVE);
    private final LedgerDb db;
    private final String prompt;
    private volatile HttpURLConnection connection;
    private volatile int generation;
    private String activeId;
    private JSONArray chat = new JSONArray();
    private JSONObject lastQuery, pending, draft;
    volatile String base = "", model = "", key = "";
    String input = "";
    private int persistedMessages;
    volatile String stage = "";
    private final Context locationContext;
    private JSONObject pickedPlace;

    /**
     * 载入共享提示词和最近会话。
     */
    LedgerAssistant(Context context, LedgerDb db) {
        this.db = db;
        locationContext = context.getApplicationContext();
        try (InputStream stream = context.getAssets().open("assistant-prompt.txt");
             InputStream skills = context.getAssets().open("ledger-skill-catalog.txt")) {
            prompt = readText(stream, 40000) + "\n\n" + readText(skills, 20000);
        } catch (Exception error) {
            throw new IllegalStateException("无法读取助手提示词", error);
        }
        open(db.activeAssistantSession());
    }

    /**
     * 确认是否已保存模型配置。
     */
    boolean configured() {
        return !base.isEmpty() && !model.isEmpty();
    }

    /**
     * 返回聊天记录供原生页面渲染。
     */
    synchronized JSONArray messages() {
        try { return new JSONArray(chat.toString()); }
        catch (JSONException error) { throw new IllegalStateException(error); }
    }

    /**
     * 返回当前待确认草稿。
     */
    synchronized JSONObject draft() {
        return draft;
    }

    /**
     * 切换会话并恢复该会话的独立历史。
     */
    synchronized void open(String id) {
        if (activeId != null && activeId.equals(id)) return;
        pickedPlace = null;
        cancel();
        persist();
        JSONObject session = db.loadAssistantSession(id);
        if (session != null) {
            activeId = id;
            chat = session.optJSONArray("chat");
            if (chat == null) chat = new JSONArray();
            persistedMessages = chat.length();
            lastQuery = session.optJSONObject("lastQuery");
            pending = session.optJSONObject("pending");
            draft = session.optJSONObject("draft");
            input = session.optString("input");
            db.setActiveAssistantSession(id);
            return;
        }
        activeId = UUID.randomUUID().toString();
        chat = new JSONArray();
        persistedMessages = 0;
        lastQuery = pending = draft = null;
        input = "";
        db.setActiveAssistantSession(activeId);
    }

    /**
     * 清除内存中的会话状态。
     */
    synchronized void reset() {
        activeId = null;
        open("");
    }

    /**
     * 记录用户输入并返回本轮请求编号。
     */
    synchronized int begin(String text) {
        chat.put(object("kind", "text", "user", true, "text", text));
        input = "";
        persist();
        return ++generation;
    }

    /**
     * 中止连接并使旧结果失效。
     */
    synchronized void cancel() {
        generation++;
        HttpURLConnection open = connection;
        if (open != null) open.disconnect();
    }

    /**
     * 在原生网络连接中测试模型的响应约定。
     */
    void test() throws Exception {
        JSONObject intent = request(object("today", "2026-01-02", "text", "今天午餐花了28元", "expenseCategories", new JSONArray().put("餐饮")), generation);
        if (!"record".equals(intent.optString("kind")) || LedgerMath.parseMoney(intent.optString("amount"), false) != 2800
                || !"2026-01-02".equals(intent.optString("date")) || !LedgerDb.EXPENSE.equals(intent.optString("type"))) {
            throw new IllegalArgumentException("模型没有正确识别示例账单");
        }
    }

    /**
     * 解析自然语言并追加草稿、回复或本地数据卡。
     */
    void finish(String text, int token) throws Exception {
        stage = "正在理解你的问题";
        boolean automatic = db.assistantAutoBooking();
        JSONObject conversation;
        JSONObject previous;
        JSONObject oldPending;
        JSONObject place;
        synchronized (this) {
            JSONObject locationReply = recentLocationReply(text);
            if (locationReply != null && token == generation) {
                chat.put(locationReply);
                persist();
                return;
            }
            conversation = context(text);
            previous = lastQuery;
            oldPending = pending;
            place = pickedPlace;
        }
        JSONObject intent = request(conversation, token);
        String kind = intent.optString("kind");
        JSONObject item = null, nextDraft = null, nextQuery = previous, nextPending = null;
        switch (kind) {
            case "record": {
                String type = intent.optString("type"), date = intent.optString("date");
                if ((!LedgerDb.EXPENSE.equals(type) && !LedgerDb.INCOME.equals(type)) || !LedgerMath.validDate(date)) {
                    throw new IllegalArgumentException("请明确收支类型和记账日期");
                }
                String note = intent.optString("note");
                if (note.codePointCount(0, note.length()) > 200) throw new IllegalArgumentException("备注最多 200 字");
                long minor = LedgerMath.parseMoney(intent.optString("amount"), false);
                String categoryId = "";
                for (LedgerDb.Category category : db.categories(type, false)) if (category.name.equals(intent.optString("category"))) categoryId = category.id;
                String source = text;
                if (oldPending != null && (intent.optBoolean("usesPending") || text.matches("[¥￥]?\\s*\\d+(\\.\\d+)?\\s*(元|块钱?)?[。！!]?$"))) {
                    source = clip(oldPending.optString("original") + "\n" + text, 1200);
                }
                nextDraft = object("type", type, "date", date, "amount", LedgerMath.money(minor).replace(",", ""),
                        "note", note, "sourceText", source, "categoryId", categoryId, "clues", cleanClues(intent.optJSONArray("clues")));
                if (place != null) applyPickedPlace(nextDraft, place);
                item = textItem(categoryId.isEmpty() ? "识别到一笔记录，请先选分类。" :
                        intent.optString("text", "识别到一笔记录，请核对后入账。"));
                put(item, "steps", new JSONArray().put("生成记账草稿 · 等待确认"));
                break;
            }
            case "search": case "stats": case "insight": {
                nextQuery = normalizeQuery(intent, text);
                JSONObject snapshot = query(nextQuery, token);
                JSONArray blocks = fallback(nextQuery, snapshot);
                if (!db.hideAmounts() && nextQuery.optInt("limit") == 0 && token == generation) {
                    stage = "正在整理查询结果";
                    try {
                        JSONObject analysis = object("task", "analyze", "text", text, "facts", modelFacts(snapshot.optJSONObject("facts")),
                                "recentTurns", conversation.optJSONArray("recentTurns"), "resultRefs", conversation.optJSONArray("resultRefs"));
                        JSONArray answer = answerBlocks(request(analysis, token), true);
                        if (answer.length() > 0) {
                            blocks = answer;
                        }
                    } catch (Exception ignored) { /* Local facts remain available. */ }
                }
                ensureBlock(blocks, "groupRank", snapshot.has("groupRank"));
                ensureBlock(blocks, "comparison", snapshot.optJSONObject("facts").has("baseline"));
                ensureBlock(blocks, "entries", "search".equals(kind) && snapshot.optJSONArray("entries").length() > 0);
                item = object("kind", "answer", "blocks", blocks, "snapshot", snapshot, "question", text, "steps", snapshot.optJSONArray("steps"));
                break;
            }
            case "result-dates": case "result-follow-up": {
                JSONObject source = referenced(intent.optString("resultId"), conversation.optJSONArray("resultRefs"));
                if (source == null && "result-dates".equals(kind) && !intent.has("resultId")) source = lastEntryCard();
                if (source == null) {
                    item = textItem("没找到你指的那张结果。可以告诉我分类或时间范围。");
                    break;
                }
                JSONObject snapshot = source.getJSONObject("snapshot");
                nextQuery = snapshot.getJSONObject("query");
                JSONArray blocks;
                if ("result-dates".equals(kind)) {
                    blocks = new JSONArray().put(object("type", "paragraph", "text", "沿用原来的查询范围，补上每笔的完整日期。"))
                            .put(object("type", "entries"));
                } else {
                    String view = intent.optString("view");
                    if (!isBlock(view)) throw new IllegalArgumentException("不支持的账本视图");
                    nextQuery = new JSONObject(nextQuery.toString()).put("view", view);
                    if (!"entries".equals(view)) nextQuery.put("limit", 0).put("order", "");
                    if (!"groupRank".equals(view)) nextQuery.remove("groupBy");
                    snapshot = query(nextQuery, token);
                    blocks = fallback(nextQuery, snapshot);
                }
                item = object("kind", "answer", "blocks", blocks, "snapshot", snapshot, "question", source.optString("question"),
                        "forceYear", "result-dates".equals(kind), "steps", "result-dates".equals(kind) ? null : snapshot.optJSONArray("steps"));
                break;
            }
            case "clarify":
                String question = clip(intent.optString("question"), 100);
                if (question.isEmpty()) throw new IllegalArgumentException("模型没有提供可读的追问");
                nextPending = object("original", oldPending == null ? text : oldPending.optString("original"), "latest", text, "question", question);
                item = textItem(question);
                break;
            case "answer": case "message":
                item = object("kind", "answer", "blocks", answerBlocks(intent, false));
                break;
            default:
                throw new IllegalArgumentException("模型没有返回可识别的记账或统计指令");
        }
        JSONObject captured = nextDraft == null ? null : nextDraft.optJSONObject("location");
        if (automatic && token == generation && nextDraft != null && !nextDraft.optString("categoryId").isEmpty()) {
            if (captured == null && !nextDraft.optBoolean("locationOmitted") && db.assistantAutoLocation()) {
                stage = "正在获取入账位置";
                captured = captureBookingLocation();
            }
        }
        synchronized (this) {
            if (token != generation) return;
            if (nextDraft != null && pickedPlace == place) pickedPlace = null;
            lastQuery = nextQuery;
            pending = nextPending;
            if (automatic && nextDraft != null && !nextDraft.optString("categoryId").isEmpty()) {
                stage = "正在校验并保存流水";
                try {
                    bookDraft(nextDraft, nextDraft.getString("amount"), nextDraft.getString("date"),
                            nextDraft.getString("categoryId"), nextDraft.getString("note"), nextDraft.optJSONArray("clues"), captured, true);
                    return;
                } catch (RuntimeException | JSONException error) {
                    item = textItem("自动记账未能保存，可以核对草稿后重试。");
                    put(item, "steps", new JSONArray().put("生成记账草稿 · 等待确认"));
                }
            }
            draft = nextDraft;
            chat.put(item);
            persist();
        }
    }

    /**
     * 将当前草稿写入账本并保存对应回执。
     */
    void saveDraft(JSONObject expected, String amount, String date, String categoryId, String note, String clueText) throws Exception {
        JSONArray clues = new JSONArray();
        for (String line : clueText.split("\\r?\\n")) {
            int divider = line.indexOf('：');
            if (divider < 0) divider = line.indexOf(':');
            if (divider > 0) clues.put(object("type", line.substring(0, divider), "value", line.substring(divider + 1)));
        }
        JSONObject location = expected.optJSONObject("location");
        if (location == null && !expected.optBoolean("locationOmitted")) location = captureBookingLocation();
        synchronized (this) {
            if (draft != expected || draft == null) throw new IllegalArgumentException("草稿已切换或取消");
            bookDraft(draft, amount, date, categoryId, note, clues, location, false);
        }
    }

    /**
     * 取消当前草稿并留下会话反馈。
     */
    synchronized void discardDraft() {
        draft = null;
        chat.put(textItem("已取消草稿。"));
        persist();
    }

    /**
     * 记录停止或错误反馈。
     */
    synchronized void notice(String text) {
        chat.put(textItem(text));
        persist();
    }

    /**
     * 撤销回执对应且未被修改的流水。
     */
    synchronized void undo(String id) throws Exception {
        for (int i = chat.length() - 1; i >= 0; i--) {
            JSONObject item = chat.getJSONObject(i), saved = item.optJSONObject("entry");
            if (saved == null || !id.equals(saved.optString("id")) || item.optBoolean("undone")) continue;
            db.deleteUnchanged(id, saved.optString("updatedAt"));
            item.put("undone", true);
            persistedMessages = Math.min(i, persistedMessages);
            persist();
            return;
        }
    }

    /**
     * 保存可供历史页和重启恢复的会话快照。
     */
    synchronized void persist() {
        if (activeId == null || (chat.length() == 0 && draft == null)) return;
        String title = "新会话";
        for (int i = 0; i < chat.length(); i++) {
            JSONObject item = chat.optJSONObject(i);
            if (item != null && item.optBoolean("user")) { title = clip(item.optString("text"), 26); break; }
        }
        db.saveAssistantSession(object("id", activeId, "title", title, "count", chat.length(), "lastQuery", lastQuery,
                "pending", pending, "draft", draft, "input", input), chat, persistedMessages);
        persistedMessages = chat.length();
    }

    /**
     * 构造有界对话和可引用的结果元数据。
     */
    private JSONObject context(String text) {
        JSONArray recent = new JSONArray(), refs = new JSONArray();
        String previousInput = "";
        for (int i = chat.length() - 2; i >= 0; i--) {
            JSONObject item = chat.optJSONObject(i);
            if (item != null && item.optBoolean("user")) {
                previousInput = clip(item.optString("text"), 300);
                break;
            }
        }
        int resultsStart = chat.length();
        int found = 0;
        for (int i = chat.length() - 2; i >= 0 && found < 6; i--) {
            JSONObject item = chat.optJSONObject(i);
            if (item != null && item.optJSONObject("snapshot") != null) { resultsStart = i; found++; }
        }
        boolean hidden = db.hideAmounts();
        for (int i = Math.min(Math.max(0, chat.length() - 13), resultsStart); i < chat.length() - 1; i++) {
            JSONObject item = chat.optJSONObject(i);
            if (item == null) continue;
            JSONObject snapshot = item.optJSONObject("snapshot");
            JSONObject turn = null;
            if (snapshot != null && i >= resultsStart) {
                String id = "r" + (i + 1);
                JSONObject facts = snapshot.optJSONObject("facts");
                JSONArray blocks = item.optJSONArray("blocks"), types = new JSONArray(), dates = new JSONArray();
                Set<String> distinct = new LinkedHashSet<>();
                boolean totals = false, comparison = false, categories = false, entries = false;
                StringBuilder interpretation = new StringBuilder();
                for (int j = 0; blocks != null && j < blocks.length(); j++) {
                    JSONObject block = blocks.optJSONObject(j);
                    if (block == null) continue;
                    String type = block.optString("type");
                    if (isBlock(type)) types.put(type);
                    totals |= "totals".equals(type);
                    comparison |= "comparison".equals(type);
                    categories |= "categories".equals(type);
                    entries |= "entries".equals(type);
                    if ("paragraph".equals(type)) interpretation.append(block.optString("text")).append(' ');
                }
                JSONArray shown = snapshot.optJSONArray("entries");
                for (int j = 0; entries && shown != null && j < shown.length() && distinct.size() < 8; j++) {
                    distinct.add(shown.optJSONObject(j).optString("date"));
                }
                for (String date : distinct) dates.put(date);
                JSONObject ref = object("id", id, "question", clip(item.optString("question"), 240), "start", facts.optString("start"),
                        "end", facts.optString("end"), "category", facts.optString("category"), "order", facts.optString("order"),
                        "limit", facts.optInt("limit"), "view", facts.optString("view"), "blocks", types, "count", facts.optLong("count"),
                        "metric", facts.optString("metric"), "dates", dates);
                if (!hidden) {
                    if (totals || comparison) put(ref, "incomeMinor", facts.optLong("incomeMinor"), "expenseMinor", facts.optLong("expenseMinor"));
                    if (categories) put(ref, "categories", snapshot.optJSONArray("ranking"));
                    if (comparison) put(ref, "comparison", facts.optJSONObject("baseline"));
                    put(ref, "interpretation", clip(interpretation.toString().trim(), 280));
                }
                refs.put(ref);
                turn = object("role", "assistant", "resultId", id);
            } else if ("text".equals(item.optString("kind"))) {
                turn = object("role", item.optBoolean("user") ? "user" : "assistant", "text",
                        item.optBoolean("localOnly") ? "已在本机核对入账定位。" : clip(item.optString("text"), 320));
            } else if ("receipt".equals(item.optString("kind"))) {
                JSONObject entry = item.optJSONObject("entry");
                turn = object("role", "assistant", "recordState", item.optBoolean("undone") ? "undone" : "saved",
                        "date", entry.optString("businessDate"), "type", entry.optString("type"));
            } else if (snapshot == null) {
                JSONArray blocks = item.optJSONArray("blocks");
                StringBuilder value = new StringBuilder();
                for (int j = 0; blocks != null && j < blocks.length(); j++) {
                    JSONObject block = blocks.optJSONObject(j);
                    value.append(block.optString("text")).append(' ');
                    JSONArray bullets = block.optJSONArray("items");
                    for (int k = 0; bullets != null && k < bullets.length(); k++) value.append(bullets.optString(k)).append(' ');
                }
                turn = object("role", "assistant", "text", clip(value.toString(), 320));
            }
            if (turn != null && i >= chat.length() - 13) recent.put(turn);
        }
        return object("today", LedgerMath.today(), "text", text, "previousInput", previousInput, "lastQuery", lastQuery, "recentTurns", recent,
                "resultRefs", refs, "pending", pending, "lastResult", refs.optJSONObject(refs.length() - 1));
    }

    /**
     * 仅解析本轮已提供给模型的结果引用。
     */
    private synchronized JSONObject referenced(String id, JSONArray refs) {
        for (int i = 0; refs != null && i < refs.length(); i++) {
            if (!id.equals(refs.optJSONObject(i).optString("id"))) continue;
            return chat.optJSONObject(Integer.parseInt(id.substring(1)) - 1);
        }
        return null;
    }

    /**
     * 查找当前会话最近显示的流水卡。
     */
    private synchronized JSONObject lastEntryCard() {
        for (int i = chat.length() - 1; i >= 0; i--) {
            JSONObject item = chat.optJSONObject(i), snapshot = item.optJSONObject("snapshot");
            if (snapshot == null) continue;
            JSONArray blocks = item.optJSONArray("blocks");
            for (int j = 0; blocks != null && j < blocks.length(); j++) {
                if ("entries".equals(blocks.optJSONObject(j).optString("type"))) return item;
            }
        }
        return null;
    }

    /**
     * 校验模型返回的检索区间、展示方式与筛选条件。
     */
    private JSONObject normalizeQuery(JSONObject intent, String text) {
        String kind = intent.optString("kind"), order = intent.optString("order"), view = intent.optString("view");
        String groupBy = clip(java.text.Normalizer.normalize(intent.optString("groupBy"), java.text.Normalizer.Form.NFKC).trim(), 24);
        int limit = "amount-desc".equals(order) ? Math.min(50, Math.max(1, intent.optInt("limit", 10))) : 0;
        Matcher rank = RANK.matcher(text);
        if (rank.find()) limit = rankNumber(rank.group(1));
        JSONArray terms = intent.optJSONArray("terms");
        JSONArray filters = cleanClues(intent.optJSONArray("filters"));
        double km = intent.optDouble("nearbyKm", 0);
        if (!(km >= .1 && km <= 100)) km = 0;
        JSONArray programWords = km > 0 ? new JSONArray() : LedgerSkills.programTerms(text);
        boolean full = km > 0 || limit > 0 || !groupBy.isEmpty() || "search".equals(kind) || filters.length() > 0 || (terms != null && terms.length() > 0);
        String start = intent.optString("start", full ? "1900-01-01" : LedgerMath.month(LedgerMath.today()) + "-01");
        String end = intent.optString("end", LedgerMath.today());
        LocalDate first = LocalDate.parse(start), last = LocalDate.parse(end), today = LocalDate.parse(LedgerMath.today());
        if (last.isAfter(today)) last = today;
        if (first.isBefore(LocalDate.of(1900, 1, 1)) || first.isAfter(last)) throw new IllegalArgumentException("请确认查询日期");
        String metric = "insight".equals(kind) ? "expense" : intent.optString("metric", "overview");
        if (!metric.matches("income|expense|overview")) throw new IllegalArgumentException("统计类型无效");
        if (limit > 0) { order = "amount-desc"; view = "entries"; }
        if ("search".equals(kind)) {
            view = "entries";
            if (km == 0 && filters.length() == 0 && (terms == null || terms.length() == 0) && programWords.length() == 0) throw new IllegalArgumentException("请提供查找的关键词");
        }
        if (!groupBy.isEmpty()) { view = "groupRank"; order = ""; limit = 0; }
        return object("kind", kind, "start", first.toString(), "end", last.toString(), "metric", metric,
                "category", clip(intent.optString("category"), 12), "terms", terms, "filters", filters, "order", order, "nearbyKm", km,
                "programTerms", programWords, "matchText", intent.optBoolean("matchText") || (!intent.has("matchText") && groupBy.isEmpty() && terms != null && terms.length() > 0), "groupBy", groupBy,
                "groupOrder", "amount".equals(intent.optString("groupOrder")) ? "amount" : "count",
                "groupLimit", Math.max(1, Math.min(50, intent.optInt("groupLimit", 10))),
                "limit", limit, "view", isBlock(view) ? view : "", "compareStart", intent.optString("compareStart"), "compareEnd", intent.optString("compareEnd"));
    }

    /**
     * 将阿拉伯数字或中文十位排名转换为笔数。
     */
    private static int rankNumber(String value) {
        if (value.matches("[0-9]+")) return Math.min(50, Math.max(1, Integer.parseInt(value)));
        String digits = "一二三四五六七八九";
        int ten = value.indexOf('十');
        if (ten < 0) return digits.indexOf(value) + 1;
        int head = ten == 0 ? 1 : digits.indexOf(value.charAt(0)) + 1;
        int tail = ten + 1 == value.length() ? 0 : digits.indexOf(value.charAt(ten + 1)) + 1;
        return Math.min(50, head * 10 + tail);
    }

    /**
     * 在本机计算统计事实、分类排行及显示用流水。
     */
    private JSONObject query(JSONObject query, int token) {
        String start = query.optString("start"), end = query.optString("end"), category = query.optString("category"),
                metric = query.optString("metric"), type = "income".equals(metric) ? LedgerDb.INCOME : LedgerDb.EXPENSE;
        String typeFilter = "overview".equals(metric) ? null : type;
        List<String> terms = new ArrayList<>();
        JSONArray rawTerms = query.optJSONArray("terms");
        for (int i = 0; rawTerms != null && i < Math.min(12, rawTerms.length()); i++) {
            String term = LedgerIndex.fold(clip(rawTerms.optString(i), 80));
            if (!term.isEmpty()) terms.add(term);
        }
        JSONArray filters = query.optJSONArray("filters");
        Map<String, String> names = new HashMap<>();
        for (LedgerDb.Category cat : db.categories(null, true)) names.put(cat.id, cat.name);
        JSONArray steps = new JSONArray();
        String dimension = query.optString("groupBy");
        double km = query.optDouble("nearbyKm", 0);
        JSONObject origin = null;
        if (km > 0) {
            addStep(token, steps, "定位当前位置");
            origin = LedgerLocation.capture(locationContext, false);
            if (origin == null) throw new IllegalArgumentException("无法获取当前位置，请先在 AI 助手设置中允许定位。");
            addStep(token, steps, "按入账坐标筛选 · " + km + " 公里内");
        }
        boolean useText = (km == 0 && "search".equals(query.optString("kind"))) || query.optBoolean("matchText")
                || dimension.isEmpty() && category.isEmpty() && (filters == null || filters.length() == 0) && !terms.isEmpty();
        JSONArray programWords = query.optJSONArray("programTerms");
        addStep(token, steps, "查流水 · " + (useText ? "双路关键词取并集" : "按条件查询完整范围"));
        List<LedgerDb.Entry> rows = scoped(start, end, typeFilter, category, names, terms, filters, programWords, useText, !dimension.isEmpty(), origin, km);
        addStep(token, steps, "汇总账本 · " + rows.size() + " 笔符合条件");
        long income = 0, expense = 0, largest = 0, sideTotal = 0;
        Map<String, Long> byCategory = new HashMap<>();
        for (LedgerDb.Entry entry : rows) {
            if (LedgerDb.INCOME.equals(entry.type)) income += entry.amountMinor;
            else expense += entry.amountMinor;
            largest = Math.max(largest, entry.amountMinor);
            if (type.equals(entry.type)) {
                sideTotal += entry.amountMinor;
                byCategory.merge(entry.categoryId, entry.amountMinor, Long::sum);
            }
        }
        JSONArray ranking = new JSONArray();
        long denominator = sideTotal;
        if (category.isEmpty()) byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(8).forEach(pair -> ranking.put(object("name", names.getOrDefault(pair.getKey(), "未知分类"), "amountMinor", pair.getValue(),
                        "percent", denominator == 0 ? 0 : pair.getValue() * 100.0 / denominator)));
        String order = query.optString("order");
        Comparator<LedgerDb.Entry> latest = Comparator.comparing((LedgerDb.Entry entry) -> entry.businessDate).reversed()
                .thenComparing(entry -> entry.createdAt, Comparator.reverseOrder()).thenComparing(entry -> entry.id);
        if ("amount-desc".equals(order)) rows.sort(Comparator.comparingLong((LedgerDb.Entry entry) -> entry.amountMinor).reversed().thenComparing(latest));
        else if (km > 0 || !"search".equals(query.optString("kind")) || "latest".equals(order)) rows.sort(latest);
        JSONArray shown = new JSONArray();
        int limit = query.optInt("limit");
        for (int i = 0; i < Math.min(rows.size(), limit > 0 ? limit : 20); i++) {
            LedgerDb.Entry entry = rows.get(i);
            JSONObject item = object("id", entry.id, "date", entry.businessDate, "note", entry.note, "amountMinor", entry.amountMinor,
                    "type", entry.type, "category", names.getOrDefault(entry.categoryId, "未知分类"));
            if ("search".equals(query.optString("kind")) && entry.clues != null) {
                ArrayList<String> values = new ArrayList<>();
                for (int j = 0; j < Math.min(entry.clues.length(), 4); j++) {
                    JSONObject clue = entry.clues.optJSONObject(j);
                    if (clue != null) values.add(clue.optString("value"));
                }
                put(item, "clues", String.join(" · ", values));
            }
            shown.put(item);
        }
        long days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1;
        JSONObject facts = object("start", start, "end", end, "days", days, "count", rows.size(), "metric", metric,
                "category", category, "view", query.optString("view"), "order", order, "limit", limit, "incomeMinor", income,
                "expenseMinor", expense, "largestMinor", largest, "top", anonymousRanks(ranking));
        String a = query.optString("compareStart"), b = query.optString("compareEnd");
        if ((!LedgerMath.validDate(a) || !LedgerMath.validDate(b) || a.compareTo(b) > 0)
                && ("insight".equals(query.optString("kind")) || "comparison".equals(query.optString("view")))) {
            a = LocalDate.parse(start).minusDays(days == 1 ? 7 : days).toString();
            b = LocalDate.parse(start).minusDays(1).toString();
        }
        if (LedgerMath.validDate(a) && LedgerMath.validDate(b) && a.compareTo(b) <= 0) {
            addStep(token, steps, "对比账期 · " + a + " 至 " + b);
            List<LedgerDb.Entry> prior = scoped(a, b, typeFilter, category, names, terms, filters, programWords, useText, false, origin, km);
            long previousIncome = 0, previousExpense = 0;
            Set<String> dates = new LinkedHashSet<>();
            for (LedgerDb.Entry entry : prior) {
                if (LedgerDb.INCOME.equals(entry.type)) previousIncome += entry.amountMinor;
                else previousExpense += entry.amountMinor;
                dates.add(entry.businessDate);
            }
            put(facts, "baseline", object("start", a, "end", b, "days", ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)) + 1,
                    "incomeMinor", previousIncome, "expenseMinor", previousExpense, "count", prior.size(), "recordedDays", dates.size()));
        }
        JSONObject groupRank = null;
        if (!dimension.isEmpty()) {
            String groupOrder = query.optString("groupOrder");
            addStep(token, steps, "排行 · 按" + dimension + ("amount".equals(groupOrder) ? "和金额" : "和笔数"));
            groupRank = LedgerSkills.group(rows, dimension, groupOrder, query.optInt("groupLimit", 10));
            put(facts, "groupRank", LedgerSkills.anonymousGroup(groupRank));
        }
        return object("query", query, "facts", facts, "entries", shown, "ranking", ranking, "groupRank", groupRank, "steps", steps);
    }

    /**
     * 应用日期、收支类型、分类与精确线索筛选。
     */
    private List<LedgerDb.Entry> scoped(String start, String end, String type, String category, Map<String, String> names,
                                        List<String> terms, JSONArray filters, JSONArray programWords, boolean useText,
                                        boolean needsClues, JSONObject origin, double km) {
        List<LedgerDb.Entry> rows;
        if (origin != null) {
            rows = db.nearby(start, end, origin, km);
            rows.removeIf(entry -> (type != null && !type.equals(entry.type))
                    || LedgerIndex.score(entry, Collections.emptyList(), filters) < 0
                    || (useText && !terms.isEmpty() && terms.stream().noneMatch(term -> LedgerIndex.score(entry, Collections.singletonList(term), null) >= 0)));
        } else {
            rows = needsClues && !useText && (filters == null || filters.length() == 0)
                    ? db.entries(start, end, type, null) : LedgerSkills.recall(db, start, end, type, terms, programWords, filters, useText);
        }
        if (!category.isEmpty()) rows.removeIf(entry -> !category.equals(names.get(entry.categoryId)));
        return rows;
    }

    /**
     * 构造模型不可用时的本机回复。
     */
    private JSONArray fallback(JSONObject query, JSONObject snapshot) {
        JSONObject facts = snapshot.optJSONObject("facts");
        int count = facts.optInt("count"), shown = snapshot.optJSONArray("entries").length();
        String view = query.optString("view");
        String message;
        if (db.hideAmounts()) message = "账本金额已隐藏，可以查看这次查询的日期和记录。";
        else if (count == 0) message = "这段时间没有找到符合条件的记录。";
        else if ("amount-desc".equals(query.optString("order"))) message = "按金额从高到低列出前 " + shown + " 笔。";
        else if ("search".equals(query.optString("kind"))) message = "找到 " + count
                + (query.optDouble("nearbyKm", 0) > 0 ? " 笔在当前位置 " + query.optDouble("nearbyKm") + " 公里内入账的流水。"
                : " 笔相关流水，点击可查看详情。");
        else message = "匹配 " + count + " 笔，收入 ¥" + LedgerMath.money(facts.optLong("incomeMinor"))
                    + "，支出 ¥" + LedgerMath.money(facts.optLong("expenseMinor")) + "。";
        JSONArray result = new JSONArray().put(object("type", "paragraph", "text", message));
        if ("entries".equals(view) || (view.isEmpty() && !query.optString("category").isEmpty())) {
            if (count > 0) result.put(object("type", "entries"));
        } else if (isBlock(view)) result.put(object("type", view));
        else if ("insight".equals(query.optString("kind")) && facts.has("baseline")) result.put(object("type", "comparison"));
        else if (count > 0) result.put(object("type", "totals"));
        return result;
    }

    /**
     * 从账本结果抽取允许发给模型的汇总字段。
     */
    private static JSONObject modelFacts(JSONObject facts) {
        return object("start", facts.optString("start"), "end", facts.optString("end"), "days", facts.optLong("days"),
                "count", facts.optLong("count"), "incomeMinor", facts.optLong("incomeMinor"), "expenseMinor", facts.optLong("expenseMinor"),
                "largestMinor", facts.optLong("largestMinor"), "metric", facts.optString("metric"), "category", facts.optString("category"),
                "view", facts.optString("view"), "baseline", facts.optJSONObject("baseline"), "top", facts.optJSONArray("top"),
                "groupRank", facts.optJSONObject("groupRank"));
    }

    /**
     * 向用户配置的兼容模型发送有限上下文。
     */
    private JSONObject request(JSONObject task, int token) throws Exception {
        if (token != generation) throw new IllegalStateException("已停止生成");
        URI endpoint = new URI(base);
        String host = endpoint.getHost(), scheme = endpoint.getScheme();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || (!"https".equals(scheme) && !("http".equals(scheme) && local))
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || key.contains("\r") || key.contains("\n")) throw new IllegalArgumentException("请输入有效的 HTTPS API 地址");
        URL url = new URL(base.replaceFirst("/+$", "") + "/chat/completions");
        HttpURLConnection active = (HttpURLConnection) url.openConnection();
        connection = active;
        try {
            active.setConnectTimeout(10000);
            active.setReadTimeout(25000);
            active.setInstanceFollowRedirects(false);
            active.setRequestMethod("POST");
            active.setRequestProperty("Content-Type", "application/json");
            if (!key.isEmpty()) active.setRequestProperty("Authorization", "Bearer " + key);
            active.setDoOutput(true);
            JSONObject body = object("model", model, "stream", false, "messages", new JSONArray()
                    .put(object("role", "system", "content", prompt)).put(object("role", "user", "content", task.toString())));
            try (java.io.OutputStream output = active.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = active.getResponseCode();
            if (status / 100 != 2) throw new IllegalArgumentException("模型服务请求失败（HTTP " + status + "）");
            try (InputStream stream = active.getInputStream()) {
                String text = new JSONObject(readText(stream, 64000)).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                        .replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                if (text.startsWith("{")) return new JSONObject(text);
                if (!text.isEmpty() && !text.startsWith("[")) return object("kind", "message", "text", clip(text, 600));
                throw new IllegalArgumentException("模型未返回有效回复");
            }
        } finally {
            active.disconnect();
            if (connection == active) connection = null;
        }
    }

    /**
     * 读取受大小限制的 UTF-8 内容。
     */
    private static String readText(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1; ) {
            result.write(buffer, 0, count);
            if (result.size() > limit) throw new IllegalArgumentException("模型响应过大");
        }
        return result.toString("UTF-8");
    }

    /**
     * 验证并限制模型或用户提供的开放线索。
     */
    private static JSONArray cleanClues(JSONArray values) {
        JSONArray result = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; values != null && i < Math.min(values.length(), 24) && result.length() < 12; i++) {
            JSONObject clue = values.optJSONObject(i);
            if (clue == null) continue;
            String type = clue.optString("type").trim(), value = clue.optString("value").trim();
            if (!type.isEmpty() && type.length() <= 24 && !value.isEmpty() && value.length() <= 120
                    && seen.add(LedgerIndex.fold(type) + "\n" + LedgerIndex.fold(value))) result.put(object("type", type, "value", value));
        }
        return result;
    }

    /**
     * 解析有界富文本并仅允许本机填充数据块。
     */
    private static JSONArray answerBlocks(JSONObject answer, boolean data) {
        JSONArray result = new JSONArray(), raw = answer.optJSONArray("blocks");
        if ("message".equals(answer.optString("kind"))) return result.put(object("type", "paragraph", "text", clip(answer.optString("text"), 600)));
        for (int i = 0; raw != null && i < Math.min(raw.length(), 8); i++) {
            JSONObject block = raw.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type");
            if (data && isBlock(type)) result.put(object("type", type));
            if ("heading".equals(type) || "paragraph".equals(type)) result.put(object("type", type, "text", clip(block.optString("text"), 600)));
            if ("bullets".equals(type)) {
                JSONArray items = block.optJSONArray("items"), cleaned = new JSONArray();
                for (int j = 0; items != null && j < Math.min(5, items.length()); j++) cleaned.put(clip(items.optString(j), 180));
                result.put(object("type", type, "items", cleaned));
            }
        }
        return result;
    }

    /**
     * 判断是否为受支持的数据块。
     */
    private static boolean isBlock(String type) {
        return !type.isEmpty() && BLOCKS.contains("|" + type + "|");
    }

    /**
     * 生成普通助手消息。
     */
    private static JSONObject textItem(String text) {
        return object("kind", "text", "text", clip(text, 600));
    }

    /**
     * 按 Unicode 字符数截取显示文本。
     */
    private static String clip(String text, int max) {
        return text.substring(0, text.offsetByCodePoints(0, Math.min(max, text.codePointCount(0, text.length()))));
    }

    /**
     * 创建包含协议字段的 JSON 对象。
     */
    static JSONObject object(Object... fields) {
        return put(new JSONObject(), fields);
    }

    /**
     * 添加内部生成的协议字段。
     */
    private static JSONObject put(JSONObject object, Object... fields) {
        try {
            for (int i = 0; i < fields.length; i += 2) object.put((String) fields[i], fields[i + 1]);
            return object;
        } catch (JSONException error) {
            throw new IllegalArgumentException("数据格式无效", error);
        }
    }

    /**
     * 创建独立的连接测试配置。
     */
    private LedgerAssistant(LedgerAssistant source) {
        db = source.db;
        prompt = source.prompt;
        locationContext = source.locationContext;
    }

    /**
     * 校验配置并为独立测试准备模型客户端。
     */
    LedgerAssistant testConfiguration(String address, String name, String secret) throws Exception {
        LedgerAssistant test = new LedgerAssistant(this);
        test.saveConfiguration(address, name, secret.trim().isEmpty() && base.equals(address.trim().replaceFirst("/+$", "")) ? key : secret);
        return test;
    }

    /**
     * 校验并保存本次运行的模型设置。
     */
    synchronized void saveConfiguration(String address, String name, String secret) throws Exception {
        String nextBase = address.trim().replaceFirst("/+$", "");
        String nextModel = name.trim();
        String nextKey = secret.trim();
        URI endpoint = new URI(nextBase);
        String host = endpoint.getHost();
        boolean local = "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        if (host == null || (!"https".equalsIgnoreCase(endpoint.getScheme()) && !("http".equalsIgnoreCase(endpoint.getScheme()) && local))
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("请输入有效的 HTTPS API 地址");
        }
        if (nextModel.isEmpty() || nextModel.length() > 160) throw new IllegalArgumentException("请填写有效的模型名称");
        if (nextKey.contains("\r") || nextKey.contains("\n")) throw new IllegalArgumentException("API Key 格式无效");
        if (nextKey.isEmpty() && nextBase.equals(base)) nextKey = key;
        if (!nextBase.equals(base) || !nextModel.equals(model) || !nextKey.equals(key)) cancel();
        base = nextBase;
        model = nextModel;
        key = nextKey;
    }

    /**
     * 生成供模型分析的匿名分类汇总。
     */
    private static JSONArray anonymousRanks(JSONArray ranking) {
        JSONArray ranks = new JSONArray();
        for (int i = 0; i < Math.min(ranking.length(), 5); i++) {
            JSONObject row = ranking.optJSONObject(i);
            if (row != null) ranks.put(object("amountMinor", row.optLong("amountMinor"), "percent", Math.round(row.optDouble("percent") * 10) / 10d));
        }
        return ranks;
    }

    /**
     * 清除当前运行期间保存的模型凭据。
     */
    synchronized void clearConfiguration() {
        cancel();
        base = model = key = "";
    }

    /**
     * 保存本机实际执行的 Skill 步骤并更新活动进度。
     */
    private void addStep(int token, JSONArray steps, String detail) {
        if (token != generation) throw new IllegalStateException("已停止处理");
        steps.put(detail);
        stage = detail;
    }

    /**
     * 确保本机核算数据保留在模型整理后的答复里。
     */
    private void ensureBlock(JSONArray blocks, String type, boolean required) {
        if (!required) return;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block != null && type.equals(block.optString("type"))) return;
        }
        blocks.put(object("type", type));
    }

    /**
     * 在同一事务内保存流水和会话回执。
     */
    private void bookDraft(JSONObject source, String amount, String date, String categoryId, String note,
                           JSONArray clues, JSONObject location, boolean automatic) throws JSONException {
        LedgerDb.Category category = db.findCategory(categoryId);
        if (category == null || category.hidden || !category.type.equals(source.optString("type"))) {
            throw new IllegalArgumentException("请选择可用分类");
        }
        LedgerDb.Entry entry = new LedgerDb.Entry();
        entry.type = source.getString("type");
        entry.amountMinor = LedgerMath.parseMoney(amount, false);
        entry.businessDate = date;
        entry.categoryId = categoryId;
        entry.note = note.trim();
        entry.sourceText = source.optString("sourceText");
        entry.clues = cleanClues(clues);
        entry.location = location;
        android.database.sqlite.SQLiteDatabase transaction = db.getWritableDatabase();
        int oldChatLength = chat.length();
        int oldPersisted = persistedMessages;
        JSONObject oldDraft = draft;
        try {
            transaction.beginTransaction();
            try {
                db.saveEntry(entry);
                chat.put(object("kind", "receipt", "entry", LedgerDb.entryJson(entry), "undone", false, "steps",
                        automatic ? new JSONArray().put("校验记账信息").put("自动入账") : null));
                draft = null;
                persist();
                transaction.setTransactionSuccessful();
            } finally {
                transaction.endTransaction();
            }
        } catch (RuntimeException | JSONException error) {
            chat.remove(oldChatLength);
            draft = oldDraft;
            persistedMessages = oldPersisted;
            throw error;
        }
    }

    /**
     * 根据设备偏好采集一次入账位置。
     */
    private JSONObject captureBookingLocation() {
        if (!db.assistantAutoLocation()) return null;
        return LedgerLocation.capture(locationContext, true);
    }

    /**
     * 从本机读取最近一笔未撤销回执的实际地点。
     */
    private JSONObject recentLocationReply(String text) {
        if (!text.matches("(?s).*(?:刚才|刚刚|上笔|上一笔|这笔).*(?:位置|地点|在哪|定位|坐标).*")
                && !text.matches("(?:你)?(?:没(?:有)?|有)?记录坐标[吗么？?。]*")) return null;
        for (int i = chat.length() - 1; i >= 0; i--) {
            JSONObject item = chat.optJSONObject(i);
            if (item == null || !"receipt".equals(item.optString("kind")) || item.optBoolean("undone")) continue;
            LedgerDb.Entry entry = db.findEntry(item.optJSONObject("entry").optString("id"));
            if (entry == null) return object("kind", "text", "text", "这笔流水已经不存在。", "localOnly", true);
            List<String> names = new ArrayList<>();
            for (int j = 0; entry.clues != null && j < entry.clues.length(); j++) {
                JSONObject clue = entry.clues.optJSONObject(j);
                if (clue != null && clue.optString("type").matches("城市|区县|地点|商家")) names.add(clue.optString("value"));
            }
            String answer = names.isEmpty() ? "这笔账没有明确的消费地点线索。" : "你提到的消费地点线索是 " + String.join(" · ", names) + "。";
            JSONObject point = entry.location;
            if (point == null) answer += "这笔未保存入账定位。";
            else {
                String name = (point.optString("city") + " " + point.optString("district")).trim();
                answer += "入账时保存了手机定位" + (name.isEmpty() ? "" : "（" + name + "）") + String.format(Locale.CHINA,
                        "，坐标为 %.5f, %.5f，精度约 %.0f 米。", point.optDouble("latitude"), point.optDouble("longitude"), point.optDouble("accuracy"));
            }
            return object("kind", "text", "text", answer, "localOnly", true);
        }
        return null;
    }

    /**
     * 保存待使用的一次性地点选择。
     */
    synchronized void pickPlace(String name, JSONObject location) {
        JSONObject place = object("name", name, "location", location);
        if (draft == null) pickedPlace = place;
        else {
            applyPickedPlace(draft, place);
            persist();
        }
    }

    /**
     * 返回当前聊天中尚未用于记账的地点。
     */
    synchronized String pickedPlaceName() {
        if (pickedPlace == null) return "";
        return pickedPlace.optString("name");
    }

    /**
     * 判断当前聊天是否有手动选中的地点或坐标。
     */
    synchronized boolean hasPickedPlace() {
        return (pickedPlace != null && !pickedPlace.optBoolean("omit")) || (draft != null && draft.optJSONObject("location") != null);
    }

    /**
     * 移除待入账的手动定位。
     */
    synchronized void clearPickedPlace() {
        if (draft == null) pickedPlace = object("omit", true);
        else {
            draft.remove("location");
            put(draft, "locationOmitted", true);
            persist();
        }
    }

    /**
     * 将选择地点追加到草稿且保留用户原话里的消费地点优先级。
     */
    private void applyPickedPlace(JSONObject target, JSONObject place) {
        String name = place.optString("name");
        JSONArray clues = target.optJSONArray("clues");
        put(target, "clues", LedgerLocation.cluesWithPlace(clues, name), "note", LedgerLocation.noteWithPlace(target.optString("note"), name),
                "location", place.optJSONObject("location"), "locationOmitted", place.optBoolean("omit"));
    }
}
