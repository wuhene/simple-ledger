package com.wuhene.simpleledger;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 处理 Web 和 Android 共用的分月备份。
 *
 * @author chaoshunh
 * @date 2026/11/25
 */
final class LedgerBackup {
    private static final int LIMIT = 180 * 1024 * 1024;
    private static final int ROW_LIMIT = 120000;
    private static final String FORMAT = "simple-ledger-monthly";
    private static final String PART = "entries/\\d{4}-(0[1-9]|1[0-2])\\.jsonl";

    /**
     * 逐月写出 Web 可读取的 ZIP 和校验清单。
     */
    static void write(LedgerDb db, OutputStream output) throws Exception {
        JSONArray parts = new JSONArray();
        int count = 0;
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            byte[] metadata = db.exportMetadata().toString().getBytes(StandardCharsets.UTF_8);
            file(zip, "ledger.json", metadata);
            for (String month : db.ledgerMonths()) {
                String name = "entries/" + month + ".jsonl";
                StringBuilder content = new StringBuilder();
                int rows = 0;
                for (LedgerDb.Entry entry : db.entries(month + "-01", YearMonth.parse(month).atEndOfMonth().toString(), null, null)) {
                    content.append(LedgerDb.entryJson(entry)).append('\n');
                    rows++;
                }
                byte[] data = content.toString().getBytes(StandardCharsets.UTF_8);
                file(zip, name, data);
                parts.put(new JSONObject().put("name", name).put("count", rows).put("sha256", hash(data)));
                count += rows;
            }
            JSONObject manifest = new JSONObject().put("format", FORMAT).put("version", 1).put("count", count)
                    .put("ledgerSha256", hash(metadata)).put("shards", parts);
            file(zip, "manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 识别分月 ZIP 或兼容 JSON 并验证分片。
     */
    static JSONObject read(InputStream input) throws Exception {
        try (BufferedInputStream stream = new BufferedInputStream(input)) {
            stream.mark(4);
            int first = stream.read(), second = stream.read();
            stream.reset();
            if (first != 'P' || second != 'K') return new JSONObject(new String(readBytes(stream, LIMIT), StandardCharsets.UTF_8));
            Map<String, byte[]> files = new HashMap<>();
            int remaining = LIMIT;
            try (ZipInputStream zip = new ZipInputStream(stream)) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    String name = entry.getName();
                    if (entry.isDirectory() || (!"ledger.json".equals(name) && !"manifest.json".equals(name) && !name.matches(PART))
                            || files.containsKey(name) || files.size() >= 4000) throw new IllegalArgumentException("备份含未知或重复文件");
                    byte[] data = readBytes(zip, remaining);
                    remaining -= data.length;
                    files.put(name, data);
                    zip.closeEntry();
                }
            }
            byte[] metadata = files.remove("ledger.json"), manifestData = files.remove("manifest.json");
            if (metadata == null || manifestData == null) throw new IllegalArgumentException("缺少备份清单");
            JSONObject manifest = new JSONObject(new String(manifestData, StandardCharsets.UTF_8));
            JSONObject ledger = new JSONObject(new String(metadata, StandardCharsets.UTF_8));
            JSONArray parts = manifest.getJSONArray("shards"), rows = new JSONArray();
            int expected = manifest.getInt("count");
            if (!FORMAT.equals(manifest.optString("format")) || manifest.optInt("version") != 1 || expected < 0 || expected > ROW_LIMIT
                    || !hash(metadata).equals(manifest.optString("ledgerSha256")) || parts.length() != files.size()) {
                throw new IllegalArgumentException("备份版本或清单校验失败");
            }
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                String name = part.getString("name");
                byte[] bytes = files.remove(name);
                if (bytes == null || !name.matches(PART) || !hash(bytes).equals(part.getString("sha256"))) {
                    throw new IllegalArgumentException("备份分片校验失败");
                }
                String month = name.substring(8, 15);
                int count = 0;
                for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n")) {
                    JSONObject row = new JSONObject(line);
                    if (!row.getString("businessDate").startsWith(month + "-") || rows.length() >= expected) {
                        throw new IllegalArgumentException("分片月份或笔数不符");
                    }
                    rows.put(row);
                    count++;
                }
                if (count != part.getInt("count")) throw new IllegalArgumentException("备份分片笔数不符");
            }
            if (rows.length() != expected) throw new IllegalArgumentException("备份总笔数不符");
            return ledger.put("entries", rows);
        }
    }

    /**
     * 写入一个固定名称的 ZIP 条目。
     */
    private static void file(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /**
     * 在容量限制内读取一个文件或 ZIP 条目。
     */
    private static byte[] readBytes(InputStream stream, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int length; (length = stream.read(buffer)) != -1; ) {
            if (length > limit - output.size()) throw new IllegalArgumentException("备份文件过大");
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    /**
     * 计算与 Web 清单一致的 SHA-256。
     */
    private static String hash(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(Character.forDigit((value & 255) >>> 4, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }
}
