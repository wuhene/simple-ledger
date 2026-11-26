package com.wuhene.simpleledger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 执行账本 Skill 的本机召回与开放线索聚合。
 *
 * @author chaoshunh
 * @date 2026/11/26
 */
final class LedgerSkills {
    /**
     * 从用户原话提取程序侧检索词。
     */
    static JSONArray programTerms(String text) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (String part : LedgerIndex.fold(text).split("帮我|找一下|找找|查一下|搜一下|我在|我吃过|有没有|哪里|哪家|是什么|花了多少|的记录|吃的|吃过的|买过的|[\\s\\p{P}]+")) {
            if (part.length() >= 2 && part.length() <= 24 && !part.matches("吃饭|吃了|记账|多少|有没有|最多|餐饮")) words.add(part);
            if (words.size() == 6) break;
        }
        return new JSONArray(words);
    }

    /**
     * 在同一个倒排索引上查两路词并按流水去重。
     */
    static List<LedgerDb.Entry> recall(LedgerDb db, String start, String end, String type, List<String> aiWords,
                                      JSONArray programWords, JSONArray filters, boolean useText) {
        if (!useText || (aiWords.isEmpty() && (programWords == null || programWords.length() == 0))) {
            if (filters == null || filters.length() == 0) return db.statsEntries(start, end, type);
            return new ArrayList<>(db.search(start, end, type, null, new ArrayList<>(), filters));
        }
        LinkedHashSet<String> words = new LinkedHashSet<>(aiWords);
        for (int i = 0; programWords != null && i < Math.min(6, programWords.length()); i++) words.add(programWords.optString(i));
        for (int i = 0; filters != null && i < filters.length(); i++) {
            JSONObject filter = filters.optJSONObject(i);
            if (filter != null) words.remove(LedgerIndex.fold(filter.optString("value")));
        }
        if (words.isEmpty()) return new ArrayList<>(db.search(start, end, type, null, new ArrayList<>(), filters));
        Map<String, LedgerDb.Entry> unique = new LinkedHashMap<>();
        for (String word : words) {
            for (LedgerDb.Entry entry : db.search(start, end, type, null, java.util.Collections.singletonList(word), filters)) {
                unique.putIfAbsent(entry.id, entry);
            }
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 聚合任意开放线索或本地入账小时并记录覆盖率。
     */
    static JSONObject group(List<LedgerDb.Entry> rows, String dimension, String order, int limit) {
        Map<String, JSONObject> counts = new LinkedHashMap<>();
        int covered = 0;
        for (LedgerDb.Entry entry : rows) {
            Map<String, String> labels = new LinkedHashMap<>();
            if ("入账小时".equals(dimension)) {
                try {
                    int hour = Instant.parse(entry.createdAt).atZone(ZoneId.systemDefault()).getHour();
                    labels.put(String.valueOf(hour), String.format(Locale.ROOT, "%02d:00", hour));
                } catch (java.time.DateTimeException ignored) {}
            } else {
                JSONArray clues = LedgerIndex.searchableClues(entry);
                for (int i = 0; i < clues.length(); i++) {
                    JSONObject clue = clues.optJSONObject(i);
                    if (clue == null || !LedgerIndex.fold(clue.optString("type")).equals(LedgerIndex.fold(dimension))) continue;
                    String label = java.text.Normalizer.normalize(clue.optString("value"), java.text.Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
                    if ("用餐时间".equals(dimension) && label.matches("(?:[01]?\\d|2[0-3]):[0-5]\\d")) {
                        String hour = String.format(Locale.ROOT, "%02d", Integer.parseInt(label.substring(0, label.indexOf(':'))));
                        label = hour + ":00–" + hour + ":59";
                    }
                    if (!label.isEmpty()) labels.put(LedgerIndex.fold(label), label);
                }
            }
            if (!labels.isEmpty()) covered++;
            for (Map.Entry<String, String> label : labels.entrySet()) {
                JSONObject current = counts.get(label.getKey());
                if (current == null) current = LedgerAssistant.object("name", label.getValue(), "count", 0, "amountMinor", 0);
                counts.put(label.getKey(), LedgerAssistant.object("name", current.optString("name"), "count", current.optInt("count") + 1,
                        "amountMinor", current.optLong("amountMinor") + entry.amountMinor));
            }
        }
        String metric = "amount".equals(order) ? "amountMinor" : "count";
        List<JSONObject> groups = new ArrayList<>(counts.values());
        groups.sort(Comparator.comparingLong((JSONObject value) -> value.optLong(metric)).reversed().thenComparing(value -> value.optString("name")));
        JSONArray top = new JSONArray();
        for (int i = 0; i < Math.min(limit, groups.size()); i++) top.put(groups.get(i));
        return LedgerAssistant.object("dimension", dimension, "covered", covered, "missing", rows.size() - covered, "groups", top);
    }

    /**
     * 提供不含原始分组名称的有限模型汇总。
     */
    static JSONObject anonymousGroup(JSONObject rank) {
        if (rank == null) return null;
        JSONArray rows = rank.optJSONArray("groups"), top = new JSONArray();
        for (int i = 0; rows != null && i < Math.min(5, rows.length()); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) top.put(LedgerAssistant.object("count", row.optInt("count"), "amountMinor", row.optLong("amountMinor")));
        }
        return LedgerAssistant.object("dimension", rank.optString("dimension"), "covered", rank.optInt("covered"),
                "missing", rank.optInt("missing"), "groups", top);
    }
}
