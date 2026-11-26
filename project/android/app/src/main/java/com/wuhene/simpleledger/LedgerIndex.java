package com.wuhene.simpleledger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成并核对与 Web 账本一致的全文索引词元。
 *
 * @author chaoshunh
 * @date 2026/11/25
 */
final class LedgerIndex {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}]+");

    /**
     * 将检索文本归一化为统一宽度与小写形式。
     */
    static String fold(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return SPACE.matcher(normalized).replaceAll(" ").trim();
    }

    /**
     * 收集流水中参与全文检索的文本。
     */
    static List<String> values(LedgerDb.Entry entry) {
        List<String> values = new ArrayList<>();
        values.add(fold(entry.note));
        values.add(fold(entry.sourceText));
        JSONArray clues = searchableClues(entry);
        for (int i = 0; i < clues.length(); i++) {
                JSONObject clue = clues.optJSONObject(i);
                if (clue == null) continue;
                values.add(fold(clue.optString("type")));
                values.add(fold(clue.optString("value")));
        }
        return values;
    }

    /**
     * 生成单字和双字倒排词元。
     */
    static Set<String> tokens(LedgerDb.Entry entry) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values(entry)) {
            Matcher words = WORD.matcher(value);
            while (words.find()) {
                int[] points = words.group().codePoints().toArray();
                for (int i = 0; i < points.length; i++) {
                    result.add(new String(points, i, 1));
                    if (i + 1 < points.length) result.add(new String(points, i, 2));
                }
            }
        }
        if (entry.location != null && Double.isFinite(entry.location.optDouble("latitude"))) {
            result.add(LedgerLocation.latitudeKey(entry.location.optDouble("latitude")));
        }
        return result;
    }

    /**
     * 提取用于缩小检索范围的首尾词元。
     */
    static Set<String> candidates(List<String> terms, JSONArray filters) {
        List<String> values = new ArrayList<>(terms);
        if (filters != null) {
            for (int i = 0; i < filters.length(); i++) {
                JSONObject filter = filters.optJSONObject(i);
                if (filter != null) values.add(clueValue(filter));
            }
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String value : values) {
            Matcher words = WORD.matcher(fold(value));
            while (words.find()) {
                int[] points = words.group().codePoints().toArray();
                int width = Math.min(points.length, 2);
                keys.add(new String(points, 0, width));
                keys.add(new String(points, points.length - width, width));
            }
        }
        return keys;
    }

    /**
     * 校验候选流水的全文及精确线索命中。
     */
    static int score(LedgerDb.Entry entry, List<String> terms, JSONArray filters) {
        int score = 0;
        if (filters != null) {
            for (int i = 0; i < filters.length(); i++) {
                JSONObject filter = filters.optJSONObject(i);
                if (filter == null || !hasClue(searchableClues(entry), filter)) return -1;
                score += 3;
            }
        }
        List<String> values = values(entry);
        for (String term : terms) {
            String word = fold(term);
            if (word.isEmpty()) continue;
            if (values.contains(word)) score += 2;
            else if (values.stream().anyMatch(value -> value.contains(word))) score++;
            else return -1;
        }
        return score;
    }

    /**
     * 核对一对开放类型和线索值。
     */
    static boolean hasClue(JSONArray clues, JSONObject filter) {
        if (clues == null) return false;
        String type = fold(filter.optString("type"));
        String value = clueValue(filter);
        for (int i = 0; i < clues.length(); i++) {
            JSONObject clue = clues.optJSONObject(i);
            if (clue != null && type.equals(fold(clue.optString("type"))) && value.equals(clueValue(clue))) return true;
        }
        return false;
    }

    /**
     * 统一同一个城市带或不带市字的写法。
     */
    private static String clueValue(JSONObject clue) {
        String value = fold(clue.optString("value"));
        String type = fold(clue.optString("type"));
        if ("省份".equals(type) && value.length() > 2 && value.endsWith("省")) {
            return value.substring(0, value.length() - 1);
        }
        if (("城市".equals(type) || "入账城市".equals(type)) && value.length() > 2 && value.endsWith("市")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 合并消费线索和带独立来源的入账地点。
     */
    static JSONArray searchableClues(LedgerDb.Entry entry) {
        JSONArray clues = new JSONArray();
        for (int i = 0; entry.clues != null && i < entry.clues.length(); i++) {
            JSONObject clue = entry.clues.optJSONObject(i);
            if (clue == null || fold(clue.optString("type")).matches("入账城市|入账区县")) continue;
            clues.put(clue);
        }
        if (entry.location != null) {
            String city = entry.location.optString("city"), district = entry.location.optString("district");
            if (!city.isEmpty() && city.length() <= 120) clues.put(LedgerAssistant.object("type", "入账城市", "value", city));
            if (!district.isEmpty() && district.length() <= 120) clues.put(LedgerAssistant.object("type", "入账区县", "value", district));
        }
        return clues;
    }
}
