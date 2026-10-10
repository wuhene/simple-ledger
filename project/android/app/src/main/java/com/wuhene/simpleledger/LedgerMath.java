package com.wuhene.simpleledger;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class LedgerMath {
    public static final long MAX_MINOR = 9_999_999_999L;
    private LedgerMath() {}

    public static long parseMoney(String input, boolean allowZero) {
        String value = input == null ? "" : input.trim();
        if (!value.matches("\\d+(?:\\.\\d{1,2})?")) throw new IllegalArgumentException("金额最多保留两位小数");
        long minor;
        try { minor = new BigDecimal(value).movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("金额超出范围"); }
        if (minor > MAX_MINOR || (!allowZero && minor <= 0)) throw new IllegalArgumentException("请输入有效金额（0.01—99,999,999.99）");
        return minor;
    }

    public static long expressionMinor(String input) {
        String expression = (input == null ? "" : input).replace('-', '−').replace(" ", "");
        if (expression.isEmpty() || expression.endsWith("+") || expression.endsWith("−")) throw new IllegalArgumentException("请完成金额计算");
        if (!expression.matches("\\d+(?:\\.\\d{1,2})?(?:[+−]\\d+(?:\\.\\d{1,2})?)*")) throw new IllegalArgumentException("请输入有效金额，最多两位小数");
        long result = 0;
        int start = 0;
        int sign = 1;
        for (int i = 0; i <= expression.length(); i++) {
            if (i == expression.length() || expression.charAt(i) == '+' || expression.charAt(i) == '−') {
                long part = parseMoney(expression.substring(start, i), true);
                result += sign * part;
                if (i < expression.length()) { sign = expression.charAt(i) == '−' ? -1 : 1; start = i + 1; }
            }
        }
        if (result <= 0 || result > MAX_MINOR) throw new IllegalArgumentException("计算结果需在 0.01—99,999,999.99 元之间");
        return result;
    }

    public static String money(long minor) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.CHINA));
        return (minor < 0 ? "−" : "") + format.format(Math.abs(minor) / 100.0);
    }

    public static boolean validDate(String text) {
        if (text == null || !text.matches("\\d{4}-\\d{2}-\\d{2}") || text.compareTo("1900-01-01") < 0) return false;
        try { return !LocalDate.parse(text).isAfter(LocalDate.now()); }
        catch (DateTimeParseException e) { return false; }
    }

    public static String month(String date) { return date.substring(0, 7); }
    public static String shiftMonth(String month, int delta) { return YearMonth.parse(month).plusMonths(delta).toString(); }
    public static String today() { return LocalDate.now().toString(); }
}
