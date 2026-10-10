package com.wuhene.simpleledger;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.window.OnBackInvokedDispatcher;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int BG = 0xfff7f8f4, WHITE = Color.WHITE, GREEN = 0xff246b54, DARK = 0xff1e332a, MUTED = 0xff63766b, LINE = 0xffdee7de, SOFT = 0xffedf3ed, RED = 0xffb9574f;
    private static final int EXPORT = 41, IMPORT = 42;
    private final ArrayDeque<Page> history = new ArrayDeque<>();
    private LedgerDb db;
    private FrameLayout root, host, entryCard;
    private LinearLayout entryKeypad;
    private TextView amountView, dateView;
    private EditText noteView;
    private Page page = new Page("home", null);
    private String month = LedgerMath.month(LedgerMath.today()), statsKind = "month", statsType = LedgerDb.EXPENSE, statsAnchor = LedgerMath.today(), billKind = "month";
    private String detailStart, detailEnd;
    private boolean hidden, entryClosing, saving, showAllMonths, showAllCategories;
    private LedgerDb.Entry draft;
    private String expression = "", initialDraft = "";

    private static final class Page { final String name, id; Page(String name, String id) { this.name = name; this.id = id; } }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        db = new LedgerDb(this); hidden = db.hideAmounts();
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom; boolean keyboard;
            if (Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = bars.bottom; keyboard = insets.isVisible(WindowInsets.Type.ime()); }
            else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); keyboard = bottom > dp(120); }
            if (entryKeypad != null) entryKeypad.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            v.setPadding(0, top, 0, bottom); return insets;
        });
        host = new FrameLayout(this); root.addView(host, new FrameLayout.LayoutParams(-1, -1)); setContentView(root); render();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    }
    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private GradientDrawable outline(int color, int radius, int stroke) { GradientDrawable d = shape(color, radius); d.setStroke(dp(1), stroke); return d; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(1); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(0); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void padding(View v, int h, int vert) { v.setPadding(dp(h), dp(vert), dp(h), dp(vert)); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private TextView text(String value, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private void space(LinearLayout l, int h) { l.addView(new View(this), lp(1, h)); }
    private TextView button(String label, boolean primary, Runnable action) { TextView b = text(label, 15, primary ? WHITE : GREEN, true); b.setGravity(Gravity.CENTER); b.setBackground(shape(primary ? GREEN : SOFT, 16)); b.setMinHeight(dp(48)); b.setOnClickListener(v -> action.run()); return b; }
    private TextView segment(String label, boolean selected, Runnable action) { TextView b = text(label, 15, selected ? GREEN : MUTED, selected); b.setGravity(Gravity.CENTER); b.setBackground(shape(selected ? WHITE : SOFT, 14)); if (selected) b.setElevation(dp(1)); b.setMinHeight(dp(44)); b.setOnClickListener(v -> action.run()); return b; }
    private TextView ghost(String label, Runnable action) { TextView b = text(label, 15, GREEN, true); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(44)); b.setOnClickListener(v -> action.run()); return b; }
    private LinearLayout card() { LinearLayout l = column(); l.setBackground(outline(WHITE, 20, LINE)); padding(l, 18, 16); return l; }
    private void addCard(LinearLayout parent, View card) { LinearLayout.LayoutParams p = lp(-1, -2); p.setMargins(dp(18), 0, dp(18), dp(12)); parent.addView(card, p); }
    private String money(long minor) { return hidden ? "••••" : "¥ " + LedgerMath.money(minor); }
    private String icon(String key) { switch (key) { case "food": return "◒"; case "bag": return "▣"; case "home": return "⌂"; case "bus": return "▰"; case "gift": return "◇"; case "heart": return "♡"; case "book": return "▤"; case "briefcase": return "▥"; case "medical": return "+"; case "pet": return "♧"; case "car": return "▭"; default: return "◌"; } }
    private void error(Exception e) { Toast.makeText(this, e.getMessage() == null ? "操作失败" : e.getMessage(), Toast.LENGTH_LONG).show(); }
    private void success(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private boolean isMain() { return page.name.equals("home") || page.name.equals("stats") || page.name.equals("tools") || page.name.equals("me"); }
    private void navigate(String target, String id) { history.push(page); page = new Page(target, id); render(); }
    private void tab(String target) { page = new Page(target, null); history.clear(); render(); }
    private void header(LinearLayout body, String eyebrow, String title) { LinearLayout block = column(); padding(block, 20, 2); block.addView(text(title, 24, DARK, true)); space(block, 14); body.addView(block); }
    private void section(LinearLayout body, String label) { LinearLayout l = column(); padding(l, 24, 10); l.addView(text(label, 20, DARK, true)); body.addView(l); }
    private void menu(LinearLayout body, String name, String caption, Runnable action) {
        LinearLayout c = card(); padding(c, 15, 14); LinearLayout line = row();
        String iconKey = name.contains("账单") || name.contains("报告") ? "book" : name.contains("分类") ? "bag" : name.contains("资产") ? "home" : name.contains("预算") ? "briefcase" : name.contains("成就") ? "gift" : "other";
        FrameLayout badge = new FrameLayout(this); badge.setBackground(shape(SOFT, 13)); badge.addView(categoryIconView(iconKey, GREEN), new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)); line.addView(badge, lp(42, 42));
        LinearLayout labels = column(); labels.setPadding(dp(12), 0, 0, 0); labels.addView(text(name, 16, DARK, true)); space(labels, 4); labels.addView(text(caption, 12, MUTED, false)); line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text("›", 25, MUTED, false)); c.addView(line); c.setOnClickListener(v -> action.run()); addCard(body, c);
    }
    private String monthEnd(String m) { return YearMonth.parse(m).atEndOfMonth().toString(); }

    private void render() {
        host.removeAllViews();
        LinearLayout shell = column(); shell.setBackgroundColor(BG); host.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false); shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column(); body.setPadding(0, dp(isMain() ? 8 : 20), 0, dp(24)); scroll.addView(body);
        switch (page.name) {
            case "home": renderHome(body); break;
            case "stats": renderStats(body); break;
            case "tools": renderTools(body); break;
            case "bills": renderBills(body); break;
            case "me": renderMe(body); break;
            case "detail": renderDetail(body); break;
            case "categoryDetail": renderCategoryDetail(body); break;
            case "budget": renderBudget(body); break;
            case "assets": renderAssets(body); break;
            case "categories": renderCategories(body); break;
            case "settings": renderSettings(body); break;
            case "report": renderReport(body); break;
            case "achievements": renderAchievements(body); break;
        }
        if (isMain()) renderTabs(shell);
    }
    private void renderTabs(LinearLayout shell) {
        LinearLayout nav = row(); nav.setBackgroundColor(WHITE); nav.setPadding(dp(8), dp(5), dp(8), dp(5));
        String[] names = {"home", "stats", "entry", "tools", "me"};
        String[] labels = {"明细", "统计", "记一笔", "工具", "我的"};
        for (int i = 0; i < names.length; i++) {
            final String target = names[i]; boolean entry = target.equals("entry"); boolean active = page.name.equals(target);
            LinearLayout tab = column(); tab.setGravity(Gravity.CENTER); tab.setContentDescription(labels[i]);
            if (entry) { TextView iconView = text("+", 30, WHITE, true); iconView.setGravity(Gravity.CENTER); iconView.setBackground(shape(GREEN, 19)); iconView.setElevation(dp(3)); tab.addView(iconView, lp(54, 54)); }
            else { View iconView = navIcon(target, active ? GREEN : MUTED); if (active) iconView.setBackground(shape(SOFT, 15)); tab.addView(iconView, lp(48, 35)); }
            TextView labelView = text(labels[i], 14, entry || active ? GREEN : MUTED, entry || active);
            labelView.setGravity(Gravity.CENTER); tab.addView(labelView, lp(-1, -2));
            tab.setMinimumHeight(dp(80));
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, -2, entry ? 1.25f : 1f);
            nav.addView(tab, tabParams);
            tab.setOnClickListener(v -> { if (target.equals("entry")) openEntry(null); else if (!page.name.equals(target)) tab(target); });
        }
        shell.addView(nav, lp(-1, -2));
    }
    private View navIcon(String name, int color) {
        return new View(this) { @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            float scale = dp(24) / 24f; canvas.save(); canvas.translate((getWidth() - dp(24)) / 2f, (getHeight() - dp(24)) / 2f); canvas.scale(scale, scale);
            switch (name) {
                case "home": canvas.drawRoundRect(4, 3, 20, 21, 3, 3, p); canvas.drawLine(8, 8, 16, 8, p); canvas.drawLine(8, 12, 16, 12, p); canvas.drawLine(8, 16, 13, 16, p); break;
                case "stats": canvas.drawLine(3, 4, 3, 20, p); canvas.drawLine(3, 20, 21, 20, p); Path trend = new Path(); trend.moveTo(7, 15); trend.lineTo(11, 11); trend.lineTo(14, 13); trend.lineTo(20, 6); canvas.drawPath(trend, p); break;
                case "tools": for (int x : new int[]{3, 14}) for (int y : new int[]{3, 14}) canvas.drawRoundRect(x, y, x + 7, y + 7, 1.5f, 1.5f, p); break;
                case "me": canvas.drawCircle(12, 7, 4, p); Path shoulders = new Path(); shoulders.moveTo(4, 21); shoulders.cubicTo(4, 16, 7, 13, 12, 13); shoulders.cubicTo(17, 13, 20, 16, 20, 21); canvas.drawPath(shoulders, p); break;
            }
            canvas.restore();
        }};
    }
    private View categoryIconView(String key, int color) {
        return new View(this) { @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            float scale = dp(24) / 24f; canvas.save(); canvas.translate((getWidth() - dp(24)) / 2f, (getHeight() - dp(24)) / 2f); canvas.scale(scale, scale);
            Path path = new Path();
            switch (key) {
                case "food": canvas.drawLine(4, 12, 20, 12, p); canvas.drawArc(6, 6, 18, 18, 0, 180, false, p); canvas.drawLine(8, 8, 7, 5, p); canvas.drawLine(16, 8, 17, 5, p); break;
                case "bag": path.moveTo(4, 8); path.lineTo(20, 8); path.lineTo(18.5f, 21); path.lineTo(5.5f, 21); path.close(); canvas.drawPath(path, p); canvas.drawArc(8, 2, 16, 10, 180, 180, false, p); break;
                case "home": path.moveTo(3, 11); path.lineTo(12, 3); path.lineTo(21, 11); path.lineTo(21, 21); path.lineTo(3, 21); path.close(); canvas.drawPath(path, p); canvas.drawRect(9, 14, 15, 21, p); break;
                case "bus": canvas.drawRoundRect(4, 4, 20, 19, 3, 3, p); canvas.drawLine(4, 11, 20, 11, p); canvas.drawLine(8, 19, 8, 21, p); canvas.drawLine(16, 19, 16, 21, p); break;
                case "gift": canvas.drawRoundRect(3, 9, 21, 21, 2, 2, p); canvas.drawLine(2, 9, 22, 9, p); canvas.drawLine(12, 9, 12, 21, p); canvas.drawArc(5, 2, 12, 10, 180, 180, false, p); canvas.drawArc(12, 2, 19, 10, 180, 180, false, p); break;
                case "heart": path.moveTo(12, 20); path.cubicTo(10, 18, 4, 14, 4, 9); path.cubicTo(4, 4, 10, 3, 12, 7); path.cubicTo(14, 3, 20, 4, 20, 9); path.cubicTo(20, 14, 14, 18, 12, 20); canvas.drawPath(path, p); break;
                case "book": canvas.drawRoundRect(4, 4, 20, 21, 2, 2, p); canvas.drawLine(4, 17, 20, 17, p); canvas.drawLine(8, 8, 16, 8, p); break;
                case "briefcase": canvas.drawRoundRect(3, 7, 21, 21, 2, 2, p); canvas.drawRect(9, 4, 15, 7, p); canvas.drawLine(3, 13, 21, 13, p); break;
                case "medical": path.moveTo(9, 3); path.lineTo(15, 3); path.lineTo(15, 9); path.lineTo(21, 9); path.lineTo(21, 15); path.lineTo(15, 15); path.lineTo(15, 21); path.lineTo(9, 21); path.lineTo(9, 15); path.lineTo(3, 15); path.lineTo(3, 9); path.lineTo(9, 9); path.close(); canvas.drawPath(path, p); break;
                case "pet": canvas.drawOval(7, 12, 17, 20, p); for (int x : new int[]{5, 9, 15, 19}) canvas.drawCircle(x, x < 7 || x > 17 ? 8 : 5, 1, p); break;
                case "car": canvas.drawRoundRect(4, 10, 20, 19, 2, 2, p); path.moveTo(5, 10); path.lineTo(7, 5); path.lineTo(17, 5); path.lineTo(19, 10); canvas.drawPath(path, p); canvas.drawLine(7, 19, 7, 21, p); canvas.drawLine(17, 19, 17, 21, p); break;
                default: canvas.drawCircle(12, 12, 9, p); canvas.drawLine(12, 8, 12, 16, p); canvas.drawLine(8, 12, 16, 12, p);
            }
            canvas.restore();
        }};
    }
    private void monthSelector(LinearLayout body) {
        LinearLayout bar = row(); padding(bar, 18, 5);
        TextView prev = ghost("‹", () -> { month = LedgerMath.shiftMonth(month, -1); render(); }); prev.setTextSize(27); prev.setBackground(outline(WHITE, 16, LINE)); bar.addView(prev, lp(48, 48));
        TextView label = text(month.substring(0, 4) + " 年 " + month.substring(5) + " 月  ▾", 17, DARK, true); label.setGravity(Gravity.CENTER); label.setOnClickListener(v -> {
            YearMonth selected = YearMonth.parse(month);
            DatePickerDialog picker = new DatePickerDialog(this, (view, year, zeroMonth, day) -> { month = String.format(Locale.ROOT, "%04d-%02d", year, zeroMonth + 1); render(); }, selected.getYear(), selected.getMonthValue() - 1, 1);
            picker.getDatePicker().setMinDate(-2208988800000L); picker.getDatePicker().setMaxDate(System.currentTimeMillis()); picker.show();
        }); label.setBackground(outline(WHITE, 16, LINE)); LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, dp(48), 1); middle.setMargins(dp(8), 0, dp(8), 0); bar.addView(label, middle);
        TextView next = ghost("›", () -> { month = LedgerMath.shiftMonth(month, 1); render(); }); next.setTextSize(27); next.setBackground(outline(WHITE, 16, LINE)); next.setEnabled(month.compareTo(LedgerMath.month(LedgerMath.today())) < 0); next.setAlpha(next.isEnabled() ? 1 : .3f); bar.addView(next, lp(48, 48)); body.addView(bar); space(body, 10);
    }
    private void totalCard(LinearLayout body, LedgerDb.Totals totals, String label) {
        LinearLayout c = card(); c.setBackground(shape(WHITE, 22)); padding(c, 20, 20); c.setElevation(dp(3));
        LinearLayout caption = row(); caption.addView(text(label, 14, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1)); TextView accent = text("●", 14, 0xffe8b878, true); caption.addView(accent); c.addView(caption);
        space(c, 14); c.addView(text(money(totals.balance()), 34, DARK, true)); space(c, 16);
        View rule = new View(this); rule.setBackgroundColor(LINE); c.addView(rule, lp(-1, 1)); space(c, 12);
        LinearLayout summary = row(); summary.setBackground(shape(SOFT, 14)); padding(summary, 14, 11);
        LinearLayout income = column(); income.addView(text("收入", 12, MUTED, false)); space(income, 4); income.addView(text(money(totals.income), 17, GREEN, true)); summary.addView(income, new LinearLayout.LayoutParams(0, -2, 1));
        View divider = new View(this); divider.setBackgroundColor(0xffd6e2d7); LinearLayout.LayoutParams divParams = lp(1, 40); divParams.setMargins(dp(8), 0, dp(12), 0); summary.addView(divider, divParams);
        LinearLayout expense = column(); expense.addView(text("支出", 12, MUTED, false)); space(expense, 4); expense.addView(text(money(totals.expense), 17, DARK, true)); summary.addView(expense, new LinearLayout.LayoutParams(0, -2, 1)); c.addView(summary); addCard(body, c);
    }

    private void renderHome(LinearLayout body) {
        monthSelector(body);
        LedgerDb.Totals totals = db.totals(month + "-01", monthEnd(month)); totalCard(body, totals, "本月结余");
        List<LedgerDb.Entry> rows = db.entries(month + "-01", monthEnd(month), null, null);
        LinearLayout heading = row(); padding(heading, 20, 6); heading.addView(text("本月明细", 20, DARK, true), new LinearLayout.LayoutParams(0, -2, 1)); heading.addView(text(rows.size() + " 笔", 12, MUTED, false)); body.addView(heading); space(body, 8);
        entryList(body, rows);
    }
    private void entryList(LinearLayout body, List<LedgerDb.Entry> entries) {
        if (entries.isEmpty()) { LinearLayout empty = card(); empty.addView(text("还没有记录，点“记一笔”开始吧", 15, MUTED, false)); addCard(body, empty); return; }
        Map<String, LedgerDb.Totals> perDay = new HashMap<>(); for (LedgerDb.Entry e : entries) { LedgerDb.Totals t = perDay.computeIfAbsent(e.businessDate, key -> new LedgerDb.Totals()); if (e.type.equals(LedgerDb.EXPENSE)) t.expense += e.amountMinor; else t.income += e.amountMinor; }
        String last = ""; LinearLayout group = null;
        for (LedgerDb.Entry e : entries) {
            if (!e.businessDate.equals(last)) {
                group = column(); LedgerDb.Totals day = perDay.get(e.businessDate); LinearLayout dayHeader = row();
                dayHeader.addView(text(e.businessDate, 14, DARK, true), new LinearLayout.LayoutParams(0, -2, 1));
                dayHeader.addView(text("收 " + money(day.income) + "  ·  支 " + money(day.expense), 12, MUTED, false)); group.addView(dayHeader); space(group, 8);
                LinearLayout.LayoutParams groupParams = lp(-1, -2); groupParams.setMargins(dp(20), dp(8), dp(20), dp(14)); body.addView(group, groupParams); last = e.businessDate;
            }
            LedgerDb.Category cat = db.findCategory(e.categoryId); LinearLayout line = row(); line.setMinimumHeight(dp(64));
            FrameLayout badge = new FrameLayout(this); badge.setBackground(shape(SOFT, 13)); badge.addView(categoryIconView(cat == null ? "other" : cat.iconKey, GREEN), new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)); line.addView(badge, lp(42, 42));
            LinearLayout left = column(); left.setPadding(dp(12), 0, 0, 0); left.addView(text(cat == null ? "已删除分类" : cat.name, 16, DARK, true)); if (!e.note.isEmpty()) left.addView(text(e.note, 12, MUTED, false)); line.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text((LedgerDb.EXPENSE.equals(e.type) ? "−" : "+") + money(e.amountMinor), 16, LedgerDb.EXPENSE.equals(e.type) ? DARK : GREEN, true)); line.setOnClickListener(v -> navigate("detail", e.id)); group.addView(line);
            View divider = new View(this); divider.setBackgroundColor(LINE); group.addView(divider, lp(-1, 1));
        }
    }
    private void renderDetail(LinearLayout body) {
        LedgerDb.Entry e = db.findEntry(page.id); if (e == null) { header(body, "账目", "记录已不存在"); return; }
        LedgerDb.Category cat = db.findCategory(e.categoryId); header(body, "账目详情", cat == null ? "分类" : cat.name);
        LinearLayout c = card(); c.addView(text((LedgerDb.EXPENSE.equals(e.type) ? "支出  −" : "收入  +") + money(e.amountMinor), 30, DARK, true)); space(c, 18);
        c.addView(text("日期  " + e.businessDate, 15, MUTED, false)); space(c, 12); c.addView(text("备注  " + (e.note.isEmpty() ? "无" : e.note), 15, MUTED, false)); addCard(body, c);
        LinearLayout actions = row(); padding(actions, 18, 8); TextView edit = button("编辑", true, () -> openEntry(e)); actions.addView(edit, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView del = button("删除", false, () -> new AlertDialog.Builder(this).setMessage("删除这笔记录？").setNegativeButton("保留", null).setPositiveButton("删除", (d, w) -> { db.deleteEntry(e.id); backPage(); offerUndo(e); }).show()); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1); p.leftMargin = dp(10); actions.addView(del, p); body.addView(actions);
    }
    private void offerUndo(LedgerDb.Entry deleted) {
        final TextView[] holder = new TextView[1]; TextView undo = button("已删除 · 点击撤销", false, () -> { try { db.restoreEntry(deleted); root.removeView(holder[0]); render(); success("已撤销删除"); } catch (Exception e) { error(e); } }); holder[0] = undo;
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(240), dp(50), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); p.bottomMargin = dp(75); root.addView(undo, p); undo.postDelayed(() -> { if (undo.getParent() == root) root.removeView(undo); }, 5000);
    }
    private void statsPeriod(int delta) {
        if (statsKind.equals("year")) statsAnchor = LocalDate.parse(statsAnchor).plusYears(delta).toString();
        else if (statsKind.equals("week")) statsAnchor = LocalDate.parse(statsAnchor).plusWeeks(delta).toString();
        else statsAnchor = LocalDate.parse(statsAnchor).plusMonths(delta).toString(); render();
    }
    private String[] statsBounds() {
        LocalDate d = LocalDate.parse(statsAnchor), start, end;
        switch (statsKind) { case "week": start = d.with(WeekFields.ISO.dayOfWeek(), 1); end = start.plusDays(6); break; case "year": start = d.withDayOfYear(1); end = start.plusYears(1).minusDays(1); break; default: start = d.withDayOfMonth(1); end = start.plusMonths(1).minusDays(1); }
        LocalDate today = LocalDate.now(); if (end.isAfter(today)) end = today; return new String[]{start.toString(), end.toString()};
    }
    private void renderStats(LinearLayout body) {
        LinearLayout kinds = row(); padding(kinds, 18, 3); String[] keys = {"week", "month", "year"}, labels = {"周", "月", "年"};
        kinds.setBackground(shape(SOFT, 18)); kinds.setPadding(dp(4), dp(4), dp(4), dp(4)); for (int i = 0; i < 3; i++) { final String k = keys[i]; TextView b = segment(labels[i], statsKind.equals(k), () -> { statsKind = k; render(); }); kinds.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1)); } body.addView(kinds);
        LinearLayout period = row(); padding(period, 18, 2); period.addView(ghost("‹", () -> statsPeriod(-1)), lp(48, 48)); TextView label = text(statsBounds()[0] + "  —  " + statsBounds()[1], 14, DARK, true); label.setGravity(Gravity.CENTER); period.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1)); TextView next = ghost("›", () -> statsPeriod(1)); next.setEnabled(statsBounds()[1].compareTo(LedgerMath.today()) < 0); next.setAlpha(next.isEnabled() ? 1 : .3f); period.addView(next, lp(48, 48)); body.addView(period);
        String[] bounds = statsBounds(); LedgerDb.Totals totals = db.totals(bounds[0], bounds[1]); totalCard(body, totals, "本期结余");
        LinearLayout toggle = row(); padding(toggle, 18, 4); toggle.addView(button("支出", statsType.equals(LedgerDb.EXPENSE), () -> { statsType = LedgerDb.EXPENSE; render(); }), new LinearLayout.LayoutParams(0, dp(44), 1)); toggle.addView(button("收入", statsType.equals(LedgerDb.INCOME), () -> { statsType = LedgerDb.INCOME; render(); }), new LinearLayout.LayoutParams(0, dp(44), 1)); body.addView(toggle); section(body, "分类排行");
        List<LedgerDb.Entry> rows = db.entries(bounds[0], bounds[1], statsType, null); renderTrend(body, rows, bounds[0], bounds[1], statsKind);
        Map<String, Long> sums = new HashMap<>(); long total = 0;
        for (LedgerDb.Entry e : rows) { sums.put(e.categoryId, sums.getOrDefault(e.categoryId, 0L) + e.amountMinor); total += e.amountMinor; }
        final long denom = total; List<Map.Entry<String, Long>> ranking = new ArrayList<>(sums.entrySet()); ranking.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (ranking.isEmpty()) { LinearLayout c = card(); c.addView(text("本期还没有记录", 15, MUTED, false)); addCard(body, c); }
        for (Map.Entry<String, Long> item : ranking) { LedgerDb.Category cat = db.findCategory(item.getKey()); if (cat == null) continue; LinearLayout c = card(); LinearLayout line = row(); line.addView(text(cat.name, 16, DARK, true), new LinearLayout.LayoutParams(0, -2, 1)); line.addView(text(money(item.getValue()), 16, GREEN, true)); c.addView(line); space(c, 9); c.addView(text(String.format(Locale.CHINA, "%.1f%%", item.getValue() * 100d / denom), 12, MUTED, false)); c.setOnClickListener(v -> { detailStart = bounds[0]; detailEnd = bounds[1]; navigate("categoryDetail", cat.id); }); addCard(body, c); }
    }
    private void renderTrend(LinearLayout body, List<LedgerDb.Entry> rows, String start, String end, String kind) {
        LinearLayout c = card(); c.addView(text("收支趋势", 17, DARK, true)); space(c, 8);
        Map<String, Long> by = new HashMap<>(), byDay = new HashMap<>(); long sum = 0;
        for (LedgerDb.Entry e : rows) { String key = kind.equals("year") ? e.businessDate.substring(0, 7) : e.businessDate; by.put(key, by.getOrDefault(key, 0L) + e.amountMinor); byDay.put(e.businessDate, byDay.getOrDefault(e.businessDate, 0L) + e.amountMinor); sum += e.amountMinor; }
        List<String> labels = new ArrayList<>(); List<Long> values = new ArrayList<>();
        if (start.compareTo(end) <= 0) { if (kind.equals("year")) { for (YearMonth cursor = YearMonth.parse(start.substring(0, 7)); cursor.atDay(1).toString().compareTo(end) <= 0; cursor = cursor.plusMonths(1)) { labels.add(cursor.toString()); values.add(by.getOrDefault(cursor.toString(), 0L)); } }
            else { for (LocalDate cursor = LocalDate.parse(start); !cursor.toString().equals(end); cursor = cursor.plusDays(1)) { labels.add(cursor.toString()); values.add(by.getOrDefault(cursor.toString(), 0L)); } labels.add(end); values.add(by.getOrDefault(end, 0L)); } }
        if (labels.isEmpty()) c.addView(text("这一期还没有可统计的日期", 14, MUTED, false));
        else { long max = 0; for (long value : values) max = Math.max(max, value); final long peak = max; View chart = new View(this) {
            @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); Paint paint = new Paint(3); float width = getWidth() / (float) values.size(); float usable = getHeight() - dp(24); paint.setColor(LINE); canvas.drawLine(0, usable, getWidth(), usable, paint); for (int i = 0; i < values.size(); i++) { float height = peak == 0 ? 0 : (values.get(i) / (float) peak) * (usable - dp(8)); paint.setColor(GREEN); canvas.drawRoundRect(i * width + dp(2), usable - height, (i + 1) * width - dp(2), usable, dp(4), dp(4), paint); } paint.setColor(MUTED); paint.setTextSize(dp(11)); canvas.drawText(labels.get(0).substring(5), dp(2), getHeight() - dp(4), paint); if (labels.size() > 1) canvas.drawText(labels.get(labels.size() - 1).substring(5), getWidth() - dp(42), getHeight() - dp(4), paint); }
        }; chart.setOnTouchListener((v, event) -> { if (event.getAction() == android.view.MotionEvent.ACTION_UP) { int i = Math.min(values.size() - 1, Math.max(0, (int) (event.getX() / v.getWidth() * values.size()))); success(labels.get(i) + " · " + money(values.get(i))); return true; } return true; }); c.addView(chart, lp(-1, 125)); }
        long days = start.compareTo(end) > 0 ? 0 : ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)) + 1; long daily = days == 0 ? 0 : Math.round(sum / (double) days);
        long highest = 0; String peakDay = "—"; for (Map.Entry<String, Long> item : byDay.entrySet()) if (item.getValue() > highest || item.getValue() == highest && item.getKey().compareTo(peakDay) < 0) { highest = item.getValue(); peakDay = item.getKey(); }
        space(c, 10); c.addView(text("日均  " + money(daily) + "   ·   单日最高  " + money(highest), 13, MUTED, false)); if (highest > 0) c.addView(text("最高日  " + peakDay, 12, MUTED, false)); addCard(body, c);
    }
    private void renderCategoryDetail(LinearLayout body) { LedgerDb.Category cat = db.findCategory(page.id); if (cat == null) return; header(body, "分类明细", cat.name); String[] b = statsBounds(); entryList(body, db.entries(detailStart == null ? b[0] : detailStart, detailEnd == null ? b[1] : detailEnd, cat.type, cat.id)); }
    private void renderBills(LinearLayout body) {
        header(body, "每个月，都有迹可循", "账单"); LinearLayout toggles = row(); padding(toggles, 18, 2); toggles.addView(button("月账单", billKind.equals("month"), () -> { billKind = "month"; render(); }), new LinearLayout.LayoutParams(0, dp(44), 1)); toggles.addView(button("年账单", billKind.equals("year"), () -> { billKind = "year"; render(); }), new LinearLayout.LayoutParams(0, dp(44), 1)); body.addView(toggles);
        if (billKind.equals("month")) {
            int year = Integer.parseInt(month.substring(0, 4)); LinearLayout selector = row(); padding(selector, 18, 2); selector.addView(ghost("‹", () -> { month = (Integer.parseInt(month.substring(0, 4)) - 1) + "-01"; render(); }), lp(48, 48)); TextView label = text(year + " 年", 17, DARK, true); label.setGravity(Gravity.CENTER); selector.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1)); TextView next = ghost("›", () -> { month = (Integer.parseInt(month.substring(0, 4)) + 1) + "-01"; render(); }); next.setEnabled(year < LocalDate.now().getYear()); next.setAlpha(next.isEnabled() ? 1 : .3f); selector.addView(next, lp(48, 48)); body.addView(selector);
            totalCard(body, db.totals(year + "-01-01", year + "-12-31"), "全年结余");
            int count = 0; for (int i = 12; i >= 1; i--) { String m = String.format(Locale.ROOT, "%04d-%02d", year, i); if (m.compareTo(LedgerMath.month(LedgerMath.today())) > 0) continue; LedgerDb.Totals t = db.totals(m + "-01", monthEnd(m)); if (!showAllMonths && t.income == 0 && t.expense == 0 && db.budget(m) == 0) continue; count++; final String selected = m; menu(body, i + " 月", "收入 " + money(t.income) + "  ·  支出 " + money(t.expense), () -> { month = selected; navigate("report", null); }); }
            if (count == 0) { LinearLayout c = card(); c.addView(text("这一年还没有账单", 15, MUTED, false)); addCard(body, c); }
            LinearLayout action = column(); padding(action, 18, 2); action.addView(ghost(showAllMonths ? "仅显示有记录月份" : "查看全部月份", () -> { showAllMonths = !showAllMonths; render(); })); body.addView(action);
        } else { List<String> years = db.years(); if (!years.contains(String.valueOf(LocalDate.now().getYear()))) years.add(0, String.valueOf(LocalDate.now().getYear()));
            for (String year : years) { LedgerDb.Totals t = db.totals(year + "-01-01", year + "-12-31"); menu(body, year + " 年账单", "收入 " + money(t.income) + "  ·  支出 " + money(t.expense) + "  ·  结余 " + money(t.balance()), () -> { month = year + "-01"; navigate("report", null); }); }
        }
    }
    private void renderTools(LinearLayout body) {
        menu(body, "账单", "月度与年度收支报告", () -> navigate("bills", null));
        menu(body, "月总预算", "设置预算，查看已用与剩余", () -> navigate("budget", null));
        menu(body, "资产概览", "手动维护资产与负债", () -> navigate("assets", null));
    }
    private void renderReport(LinearLayout body) {
        boolean annual = billKind.equals("year"); String start = annual ? month.substring(0, 4) + "-01-01" : month + "-01"; String end = annual ? month.substring(0, 4) + "-12-31" : monthEnd(month);
        header(body, "给自己的财务摘要", annual ? month.substring(0, 4) + " 年账单" : month + " 账单");
        if (start.compareTo(LedgerMath.today()) > 0) { LinearLayout empty = card(); empty.addView(text("未来月份暂无报告", 15, MUTED, false)); addCard(body, empty); return; }
        LedgerDb.Totals t = db.totals(start, end); totalCard(body, t, start + " — " + end); List<LedgerDb.Entry> all = db.entries(start, end, null, null);
        if (!annual) { String previous = LedgerMath.shiftMonth(month, -1); LedgerDb.Totals prev = db.totals(previous + "-01", monthEnd(previous)); LinearLayout comparison = card(); comparison.addView(text("上月结余  " + money(prev.balance()), 16, DARK, true)); space(comparison, 8); comparison.addView(text("本月支出较上月  " + (t.expense >= prev.expense ? "增加 " : "减少 ") + money(Math.abs(t.expense - prev.expense)), 13, MUTED, false)); addCard(body, comparison); }
        reportTypeSection(body, all, LedgerDb.EXPENSE, "支出组成");
        if (!annual) { section(body, "支出单笔前 5 名"); List<LedgerDb.Entry> top = new ArrayList<>(); for (LedgerDb.Entry e : all) if (e.type.equals(LedgerDb.EXPENSE)) top.add(e); top.sort((a, b) -> { int c = Long.compare(b.amountMinor, a.amountMinor); return c != 0 ? c : b.businessDate.compareTo(a.businessDate); }); int count = Math.min(5, top.size()); for (int i = 0; i < count; i++) { LedgerDb.Entry e = top.get(i); LedgerDb.Category cat = db.findCategory(e.categoryId); menu(body, (i + 1) + ". " + (cat == null ? "分类" : cat.name), e.businessDate + "  ·  " + money(e.amountMinor), () -> navigate("detail", e.id)); } }
        section(body, annual ? "年度月趋势" : "本月日趋势"); List<LedgerDb.Entry> expense = new ArrayList<>(); for (LedgerDb.Entry e : all) if (e.type.equals(LedgerDb.EXPENSE)) expense.add(e); renderTrend(body, expense, start, end.compareTo(LedgerMath.today()) > 0 ? LedgerMath.today() : end, annual ? "year" : "month");
        if (!annual) { String sixStart = YearMonth.parse(month).minusMonths(5).atDay(1).toString(); section(body, "最近 6 个月支出对比"); renderTrend(body, db.entries(sixStart, end, LedgerDb.EXPENSE, null), sixStart, end.compareTo(LedgerMath.today()) > 0 ? LedgerMath.today() : end, "year"); reportCategoryChanges(body, expense); }
        reportTypeSection(body, all, LedgerDb.INCOME, "收入组成");
        if (annual) { section(body, "年度收入月趋势"); renderTrend(body, db.entries(start, end, LedgerDb.INCOME, null), start, end.compareTo(LedgerMath.today()) > 0 ? LedgerMath.today() : end, "year"); }
        if (!annual) { String sixStart = YearMonth.parse(month).minusMonths(5).atDay(1).toString(); section(body, "最近 6 个月收入对比"); renderTrend(body, db.entries(sixStart, end, LedgerDb.INCOME, null), sixStart, end.compareTo(LedgerMath.today()) > 0 ? LedgerMath.today() : end, "year"); }
        LinearLayout achievement = card(); achievement.addView(text("本期记账  " + all.size() + " 笔", 16, DARK, true)); addCard(body, achievement);
        LinearLayout action = column(); padding(action, 18, 8); action.addView(button("分享报告图片", true, () -> shareReport(start, end, t)), lp(-1, 50)); body.addView(action);
    }
    private void reportTypeSection(LinearLayout body, List<LedgerDb.Entry> all, String type, String title) {
        section(body, title); Map<String, Long> sums = new HashMap<>(); long total = 0;
        for (LedgerDb.Entry e : all) if (e.type.equals(type)) { sums.put(e.categoryId, sums.getOrDefault(e.categoryId, 0L) + e.amountMinor); total += e.amountMinor; }
        if (sums.isEmpty()) { LinearLayout empty = card(); empty.addView(text("暂无记录", 14, MUTED, false)); addCard(body, empty); return; }
        final long amount = total; List<Map.Entry<String, Long>> ranking = new ArrayList<>(sums.entrySet()); ranking.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        LinearLayout ringCard = card(); View ring = new View(this) { @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); int[] colors = {GREEN, 0xff74a989, 0xffb5caa8, 0xffddbb7e, 0xffe6d8b6, 0xffc3cbc4}; Paint p = new Paint(3); float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = Math.min(getWidth(), getHeight()) * .43f; android.graphics.RectF rect = new android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius); float angle = -90;
            long remainder = amount; for (int i = 0; i < Math.min(5, ranking.size()); i++) { long value = ranking.get(i).getValue(); p.setColor(colors[i]); float sweep = value * 360f / amount; canvas.drawArc(rect, angle, sweep, true, p); angle += sweep; remainder -= value; } if (remainder > 0) { p.setColor(colors[5]); canvas.drawArc(rect, angle, remainder * 360f / amount, true, p); }
            p.setColor(WHITE); canvas.drawCircle(cx, cy, radius * .58f, p); p.setColor(DARK); p.setTextSize(dp(18)); p.setTypeface(Typeface.DEFAULT_BOLD); String label = money(amount); canvas.drawText(label, cx - p.measureText(label) / 2, cy + dp(6), p);
        } }; ringCard.addView(ring, lp(-1, 190)); addCard(body, ringCard);
        for (Map.Entry<String, Long> item : ranking) { LedgerDb.Category cat = db.findCategory(item.getKey()); if (cat == null) continue; menu(body, cat.name + "  ·  " + String.format(Locale.CHINA, "%.1f%%", item.getValue() * 100d / amount), money(item.getValue()), () -> { detailStart = billKind.equals("year") ? month.substring(0, 4) + "-01-01" : month + "-01"; detailEnd = billKind.equals("year") ? month.substring(0, 4) + "-12-31" : monthEnd(month); navigate("categoryDetail", cat.id); }); }
    }
    private void reportCategoryChanges(LinearLayout body, List<LedgerDb.Entry> current) {
        String previous = LedgerMath.shiftMonth(month, -1); Map<String, Long> now = new HashMap<>(), before = new HashMap<>();
        for (LedgerDb.Entry e : current) now.put(e.categoryId, now.getOrDefault(e.categoryId, 0L) + e.amountMinor);
        for (LedgerDb.Entry e : db.entries(previous + "-01", monthEnd(previous), LedgerDb.EXPENSE, null)) before.put(e.categoryId, before.getOrDefault(e.categoryId, 0L) + e.amountMinor);
        java.util.Set<String> ids = new java.util.HashSet<>(now.keySet()); ids.addAll(before.keySet()); List<String> ranked = new ArrayList<>(ids); ranked.removeIf(id -> now.getOrDefault(id, 0L).equals(before.getOrDefault(id, 0L)));
        ranked.sort((a, b) -> Long.compare(Math.abs(now.getOrDefault(b, 0L) - before.getOrDefault(b, 0L)), Math.abs(now.getOrDefault(a, 0L) - before.getOrDefault(a, 0L))));
        section(body, "变化最大的分类"); if (ranked.isEmpty()) { LinearLayout c = card(); c.addView(text("较上月无分类变化", 14, MUTED, false)); addCard(body, c); }
        for (int i = 0; i < Math.min(3, ranked.size()); i++) { String id = ranked.get(i); LedgerDb.Category cat = db.findCategory(id); if (cat == null) continue; long old = before.getOrDefault(id, 0L), value = now.getOrDefault(id, 0L), delta = value - old; String phrase = old == 0 && value > 0 ? "新增支出" : delta > 0 ? "增加" : "减少"; menu(body, cat.name, phrase + "  " + money(Math.abs(delta)), () -> { detailStart = month + "-01"; detailEnd = monthEnd(month); navigate("categoryDetail", id); }); }
    }
    private void shareReport(String start, String end, LedgerDb.Totals totals) {
        LinearLayout preview = column(); padding(preview, 18, 8); ImageView imageView = new ImageView(this); imageView.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.addView(imageView, lp(-1, 330));
        CheckBox ranks = new CheckBox(this), achievements = new CheckBox(this); ranks.setText("包含分类排行"); achievements.setText("包含记账成就"); preview.addView(ranks); preview.addView(achievements);
        Runnable refresh = () -> imageView.setImageBitmap(reportBitmap(start, end, totals, ranks.isChecked(), achievements.isChecked())); refresh.run(); ranks.setOnCheckedChangeListener((button, checked) -> refresh.run()); achievements.setOnCheckedChangeListener((button, checked) -> refresh.run());
        new AlertDialog.Builder(this).setTitle("报告图片预览").setView(preview).setNegativeButton("取消", null).setPositiveButton("系统分享", (dialog, which) -> {
            try { Bitmap image = reportBitmap(start, end, totals, ranks.isChecked(), achievements.isChecked()); java.io.File file = new java.io.File(getCacheDir(), "report.png"); try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } image.recycle(); Intent intent = new Intent(Intent.ACTION_SEND); intent.setType("image/png"); intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://com.wuhene.simpleledger.report/report.png")); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(intent, "分享收支报告")); } catch (Exception e) { error(e); }
        }).show();
    }
    private Bitmap reportBitmap(String start, String end, LedgerDb.Totals totals, boolean withRanks, boolean withAchievement) {
        Bitmap image = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(image); c.drawColor(BG); Paint p = new Paint(3); p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); p.setColor(GREEN); p.setTextSize(68); c.drawText("简账 · 收支报告", 72, 150, p); p.setColor(DARK); p.setTextSize(38); c.drawText(start + "  —  " + end, 72, 260, p); p.setTextSize(56); c.drawText("收入  " + money(totals.income), 72, 430, p); c.drawText("支出  " + money(totals.expense), 72, 540, p); c.drawText("结余  " + money(totals.balance()), 72, 650, p);
        int y = 750; if (withRanks) { Map<String, Long> sums = new HashMap<>(); for (LedgerDb.Entry e : db.entries(start, end, LedgerDb.EXPENSE, null)) sums.put(e.categoryId, sums.getOrDefault(e.categoryId, 0L) + e.amountMinor); List<Map.Entry<String, Long>> sorted = new ArrayList<>(sums.entrySet()); sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue())); p.setTextSize(32); for (int i = 0; i < Math.min(3, sorted.size()); i++) { LedgerDb.Category cat = db.findCategory(sorted.get(i).getKey()); c.drawText((cat == null ? "分类" : cat.name) + "  " + money(sorted.get(i).getValue()), 72, y, p); y += 58; } }
        if (withAchievement) { p.setTextSize(32); c.drawText("本期记账 " + db.entries(start, end, null, null).size() + " 笔", 72, y + 35, p); }
        p.setColor(MUTED); p.setTextSize(32); c.drawText("仅保存在你的设备上", 72, 1070, p); return image;
    }

    private void renderMe(LinearLayout body) {
        menu(body, "分类管理", "调整分类名称与显示状态", () -> navigate("categories", null)); menu(body, "记账成就", "看看你记录了多少天", () -> navigate("achievements", null)); menu(body, "设置与备份", "金额隐藏、本地导出与恢复", () -> navigate("settings", null));
    }
    private void moneyDialog(String title, long current, boolean allowZero, java.util.function.LongConsumer save) {
        EditText field = new EditText(this); field.setSingleLine(true); field.setInputType(8194); field.setText(LedgerMath.money(current).replace(",", "")); field.selectAll(); LinearLayout wrap = column(); padding(wrap, 24, 8); wrap.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(wrap).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try { save.accept(LedgerMath.parseMoney(field.getText().toString(), allowZero)); dialog.dismiss(); render(); } catch (Exception e) { field.setError(e.getMessage()); } })); dialog.show();
    }
    private void renderBudget(LinearLayout body) {
        header(body, "给支出一个边界", "月总预算"); monthSelector(body); long budget = db.budget(month); long spent = db.totals(month + "-01", monthEnd(month)).expense;
        LinearLayout c = card(); c.addView(text("本月预算", 14, MUTED, false)); space(c, 7); c.addView(text(budget == 0 ? "尚未设置" : money(budget), 28, DARK, true)); space(c, 16); c.addView(text("已支出  " + money(spent), 15, MUTED, false)); if (budget > 0) { space(c, 8); c.addView(text(spent > budget ? "已超出  " + money(spent - budget) : "还可用  " + money(budget - spent), 15, spent > budget ? RED : GREEN, true)); } addCard(body, c);
        LinearLayout actions = column(); padding(actions, 18, 6); actions.addView(button(budget == 0 ? "设置预算" : "调整预算", true, () -> moneyDialog("月总预算 · " + month, budget, false, value -> db.setBudget(month, value))));
        if (budget > 0) { space(actions, 10); actions.addView(button("删除本月预算", false, () -> new AlertDialog.Builder(this).setMessage("删除 " + month + " 的预算？流水不会删除。") .setNegativeButton("保留", null).setPositiveButton("删除预算", (d, w) -> { db.setBudget(month, 0); render(); }).show())); } body.addView(actions);
    }
    private void renderAssets(LinearLayout body) {
        header(body, "手动记录，轻松了解", "资产概览"); long assets = db.asset(false), debt = db.asset(true); LinearLayout c = card(); c.addView(text("净资产", 14, MUTED, false)); space(c, 8); c.addView(text(db.hasAsset() ? money(assets - debt) : "尚未设置", 30, DARK, true)); space(c, 16); c.addView(text("资产  " + (db.hasAsset() ? money(assets) : "尚未设置"), 16, GREEN, true)); space(c, 6); c.addView(text("负债  " + (db.hasAsset() ? money(debt) : "尚未设置"), 16, RED, true)); space(c, 10); c.addView(text("资产为手动维护，收支记录不会自动改变资产。", 12, MUTED, false)); if (db.hasAsset()) c.addView(text("更新于  " + db.assetUpdatedAt().substring(0, 10), 12, MUTED, false)); addCard(body, c);
        menu(body, "修改资产金额", "手动输入总资产", () -> moneyDialog("资产金额", assets, true, value -> db.setAssets(value, db.asset(true)))); menu(body, "修改负债金额", "手动输入总负债", () -> moneyDialog("负债金额", debt, true, value -> db.setAssets(db.asset(false), value)));
    }
    private void renderCategories(LinearLayout body) {
        header(body, "让每笔账归到合适的地方", "分类管理");
        for (String type : new String[]{LedgerDb.EXPENSE, LedgerDb.INCOME}) {
            section(body, type.equals(LedgerDb.EXPENSE) ? "支出分类" : "收入分类");
            for (LedgerDb.Category cat : db.categories(type, true)) { LinearLayout c = card(); LinearLayout line = row(); line.addView(text(icon(cat.iconKey) + "  " + cat.name + (cat.hidden ? "  ·  已隐藏" : ""), 16, cat.hidden ? MUTED : DARK, true), new LinearLayout.LayoutParams(0, -2, 1)); line.addView(text("管理  ›", 13, GREEN, true)); c.addView(line); c.setOnClickListener(v -> categoryActions(cat)); addCard(body, c); }
            LinearLayout action = column(); padding(action, 18, 4); action.addView(button("+ 添加" + (type.equals(LedgerDb.EXPENSE) ? "支出" : "收入") + "分类", false, () -> categoryNameDialog(null, type))); body.addView(action);
        }
    }
    private void categoryActions(LedgerDb.Category cat) {
        List<String> choices = new ArrayList<>(); choices.add("重命名"); choices.add("选择图标"); choices.add("向前排序"); choices.add("向后排序"); choices.add(cat.hidden ? "显示分类" : "隐藏分类"); if (!cat.builtin) choices.add("删除分类");
        new AlertDialog.Builder(this).setTitle(cat.name).setItems(choices.toArray(new String[0]), (d, which) -> { try {
            if (which == 0) categoryNameDialog(cat, cat.type);
            else if (which == 1) categoryIconDialog(cat);
            else if (which == 2 || which == 3) { db.moveCategory(cat.id, which == 2 ? -1 : 1); render(); }
            else if (which == 4) { db.setCategoryHidden(cat.id, !cat.hidden); render(); }
            else new AlertDialog.Builder(this).setMessage("删除分类“" + cat.name + "”？").setNegativeButton("保留", null).setPositiveButton("删除", (a, b) -> { try { db.deleteCategory(cat.id); render(); } catch (Exception e) { error(e); } }).show();
        } catch (Exception e) { error(e); } }).show();
    }
    private void categoryIconDialog(LedgerDb.Category cat) {
        String[] keys = {"food", "bag", "home", "bus", "gift", "heart", "book", "briefcase", "medical", "pet", "car", "other"};
        String[] labels = {"餐食", "购物", "住家", "交通", "礼物", "爱心", "书籍", "工作", "医疗", "宠物", "汽车", "其它"};
        new AlertDialog.Builder(this).setTitle("选择图标 · " + cat.name).setItems(labels, (d, which) -> { db.setCategoryIcon(cat.id, keys[which]); render(); }).show();
    }
    private void categoryNameDialog(LedgerDb.Category cat, String type) {
        EditText field = new EditText(this); field.setSingleLine(true); if (cat != null) field.setText(cat.name); LinearLayout wrap = column(); padding(wrap, 24, 8); wrap.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(cat == null ? "添加分类" : "重命名分类").setView(wrap).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try { if (cat == null) db.addCategory(type, field.getText().toString()); else db.renameCategory(cat.id, field.getText().toString()); dialog.dismiss(); render(); } catch (Exception e) { field.setError(e.getMessage()); } })); dialog.show();
    }
    private void renderAchievements(LinearLayout body) {
        header(body, "每一笔都算数", "记账成就"); List<LedgerDb.Entry> all = db.entries(null, null, null, null); java.util.Set<String> days = new java.util.HashSet<>(); for (LedgerDb.Entry e : all) days.add(e.businessDate);
        LocalDate cursor = LocalDate.now(); if (!days.contains(cursor.toString())) cursor = cursor.minusDays(1); int streak = 0; while (days.contains(cursor.toString())) { streak++; cursor = cursor.minusDays(1); }
        LinearLayout c = card(); c.addView(text("累计记账  " + all.size() + " 笔", 20, DARK, true)); space(c, 16); c.addView(text("记录天数  " + days.size() + " 天", 17, GREEN, true)); space(c, 12); c.addView(text("连续记录  " + streak + " 天", 17, GREEN, true)); addCard(body, c);
    }
    private void renderSettings(LinearLayout body) {
        header(body, "数据只在你的设备", "设置与备份"); menu(body, hidden ? "显示金额" : "隐藏金额", "在页面中切换金额显示", () -> { hidden = !hidden; db.setHideAmounts(hidden); render(); });
        menu(body, "导出 JSON 备份", "保存到你选择的位置", this::exportBackup);
        menu(body, "恢复 JSON 备份", "验证通过后替换当前账本", () -> { Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("application/json"); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, IMPORT); });
        menu(body, "清空本地数据", "删除记账并恢复默认分类", this::confirmClear);
        LinearLayout tip = card(); tip.addView(text("单人离线使用 · 没有账号与云同步", 13, MUTED, false)); addCard(body, tip);
    }
    private void exportBackup() { Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("application/json"); i.addCategory(Intent.CATEGORY_OPENABLE); i.putExtra(Intent.EXTRA_TITLE, "simple-ledger-" + LedgerMath.today() + ".json"); startActivityForResult(i, EXPORT); }
    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("清空本地数据？").setMessage("所有记账、预算和分类将被删除。建议先导出备份。") .setNegativeButton("保留", null).setNeutralButton("先导出备份", (d, w) -> exportBackup()).setPositiveButton("继续", (d, w) -> new AlertDialog.Builder(this).setTitle("最后确认").setMessage("清空后无法撤销。确认清空本机账本？") .setNegativeButton("取消", null).setPositiveButton("确认清空", (a, b) -> { db.clear(); hidden = false; month = LedgerMath.month(LedgerMath.today()); render(); success("已清空"); }).show()).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT) exportToUri(data.getData()); else if (request == IMPORT) readImportPreview(data.getData());
    }
    private AlertDialog progress(String message) { AlertDialog dialog = new AlertDialog.Builder(this).setMessage(message).setCancelable(false).create(); dialog.show(); return dialog; }
    private void exportToUri(Uri uri) {
        AlertDialog busy = progress("正在导出账本…"); new Thread(() -> { Exception failure = null;
            try { byte[] bytes = db.exportJson().toString(2).getBytes(StandardCharsets.UTF_8); try (OutputStream out = getContentResolver().openOutputStream(uri)) { if (out == null) throw new IllegalStateException("无法写入文件"); out.write(bytes); } }
            catch (Exception e) { failure = e; } Exception result = failure; runOnUiThread(() -> { busy.dismiss(); if (result == null) success("备份已保存"); else error(result); });
        }).start();
    }
    private void readImportPreview(Uri uri) {
        AlertDialog busy = progress("正在读取备份…"); new Thread(() -> { JSONObject json = null; Exception failure = null;
            try { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (InputStream in = getContentResolver().openInputStream(uri)) { if (in == null) throw new IllegalStateException("无法读取文件"); byte[] chunk = new byte[8192]; int n; while ((n = in.read(chunk)) != -1) { bytes.write(chunk, 0, n); if (bytes.size() > 60_000_000) throw new IllegalArgumentException("备份文件过大"); } } json = new JSONObject(bytes.toString("UTF-8")); }
            catch (Exception e) { failure = e; } JSONObject preview = json; Exception result = failure; runOnUiThread(() -> { busy.dismiss(); if (result == null) showRestorePreview(preview); else error(result); });
        }).start();
    }
    private void showRestorePreview(JSONObject json) {
        try { int entries = json.getJSONArray("entries").length(), categories = json.getJSONArray("categories").length(), budgets = json.getJSONArray("budgets").length(); final boolean[] confirmed = {false};
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle("恢复预览").setMessage("备份包含 " + entries + " 笔记账、" + categories + " 个分类、" + budgets + " 个月预算。恢复会替换当前账本。") .setMultiChoiceItems(new String[]{"我已保留当前账本的备份"}, new boolean[]{false}, (d, which, checked) -> confirmed[0] = checked).setNegativeButton("保留当前", null).setPositiveButton("恢复", null).create();
            dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { if (!confirmed[0]) { success("请先备份当前数据并勾选确认"); return; } dialog.dismiss(); AlertDialog busy = progress("正在验证并恢复…"); new Thread(() -> { Exception failure = null; try { db.importJson(json); } catch (Exception e) { failure = e; } Exception result = failure; runOnUiThread(() -> { busy.dismiss(); if (result == null) { hidden = db.hideAmounts(); render(); success("恢复成功"); } else error(result); }); }).start(); })); dialog.show();
        } catch (Exception e) { error(e); }
    }

    private void openEntry(LedgerDb.Entry source) {
        draft = new LedgerDb.Entry(); showAllCategories = false; if (source == null) { draft.type = LedgerDb.EXPENSE; draft.businessDate = LedgerMath.today(); draft.note = ""; draft.categoryId = db.defaultCategory(draft.type); expression = ""; }
        else { draft.id = source.id; draft.type = source.type; draft.amountMinor = source.amountMinor; draft.categoryId = source.categoryId; draft.businessDate = source.businessDate; draft.note = source.note; expression = LedgerMath.money(source.amountMinor).replace(",", ""); }
        initialDraft = snapshot(); entryClosing = false; entryCard = new FrameLayout(this); entryCard.setBackground(shape(BG, 24)); root.addView(entryCard, new FrameLayout.LayoutParams(-1, -1)); buildEntry();
        if (ValueAnimator.areAnimatorsEnabled()) { entryCard.setTranslationY(root.getHeight()); entryCard.animate().translationY(0).setDuration(310).setInterpolator(new DecelerateInterpolator()).start(); }
    }
    private String snapshot() { return draft.type + "|" + draft.categoryId + "|" + draft.businessDate + "|" + draft.note + "|" + expression; }
    private void buildEntry() {
        if (entryCard == null) return; entryCard.removeAllViews(); LinearLayout shell = column(); entryCard.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = row(); padding(top, 20, 14); TextView title = text(draft.id == null ? "记一笔" : "编辑记录", 25, DARK, true); title.setGravity(Gravity.CENTER); top.addView(title, new LinearLayout.LayoutParams(-1, dp(40))); shell.addView(top);
        LinearLayout typeSwitch = row(); typeSwitch.setBackground(shape(SOFT, 18)); typeSwitch.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (String type : new String[]{LedgerDb.EXPENSE, LedgerDb.INCOME}) { boolean selected = draft.type.equals(type); TextView choice = segment(type.equals(LedgerDb.EXPENSE) ? "支出" : "收入", selected, () -> { if (draft.type.equals(type)) return; preserveNote(); draft.type = type; draft.categoryId = db.defaultCategory(type); buildEntry(); }); typeSwitch.addView(choice, new LinearLayout.LayoutParams(0, dp(44), 1)); }
        LinearLayout.LayoutParams switchParams = lp(-1, -2); switchParams.setMargins(dp(18), 0, dp(18), dp(8)); shell.addView(typeSwitch, switchParams);
        ScrollView scroll = new ScrollView(this); scroll.setVerticalScrollBarEnabled(false); shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); LinearLayout content = column(); scroll.addView(content);
        List<LedgerDb.Category> categories = db.categories(draft.type, true); int available = 0; for (LedgerDb.Category cat : categories) if (!cat.hidden || cat.id.equals(draft.categoryId)) available++;
        LinearLayout categoryHeading = row(); padding(categoryHeading, 20, 4); categoryHeading.addView(text("选择分类", 16, DARK, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (available > 8) categoryHeading.addView(ghost(showAllCategories ? "收起" : "全部分类", () -> { preserveNote(); showAllCategories = !showAllCategories; buildEntry(); })); content.addView(categoryHeading);
        GridLayout grid = new GridLayout(this); grid.setColumnCount(4); grid.setPadding(dp(16), 0, dp(16), 0); int shown = 0;
        for (LedgerDb.Category cat : categories) { if (cat.hidden && !cat.id.equals(draft.categoryId)) continue; boolean selected = draft.categoryId.equals(cat.id); if (!showAllCategories && shown >= 8 && !selected) continue; shown++; LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setBackground(outline(selected ? SOFT : WHITE, 16, selected ? GREEN : LINE)); item.addView(categoryIconView(cat.iconKey, GREEN), lp(30, 30)); TextView name = text(cat.name, 12, DARK, selected); name.setGravity(Gravity.CENTER); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.END); item.addView(name, lp(-1, 24)); GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = 0; p.height = dp(66); p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); p.setMargins(dp(3), dp(3), dp(3), dp(3)); grid.addView(item, p); item.setOnClickListener(v -> { preserveNote(); draft.categoryId = cat.id; buildEntry(); }); }
        content.addView(grid); space(content, 8);
        LinearLayout amountCard = card(); amountCard.addView(text(draft.type.equals(LedgerDb.EXPENSE) ? "支出金额" : "收入金额", 13, MUTED, true)); space(amountCard, 8); amountView = text(expression.isEmpty() ? "¥ 0.00" : "¥ " + expression, 38, DARK, true); amountCard.addView(amountView); space(amountCard, 12);
        View dateRule = new View(this); dateRule.setBackgroundColor(LINE); amountCard.addView(dateRule, lp(-1, 1));
        dateView = text("▦  " + draft.businessDate + "  ·  点击选择日期  ›", 14, GREEN, true); dateView.setMinHeight(dp(46)); dateView.setOnClickListener(v -> showDatePicker()); amountCard.addView(dateView); addCard(content, amountCard);
        LinearLayout noteCard = card(); noteView = new EditText(this); noteView.setSingleLine(true); noteView.setTextSize(15); noteView.setHint("备注（可选）"); noteView.setText(draft.note); noteView.setBackgroundColor(Color.TRANSPARENT); noteCard.addView(noteView); addCard(content, noteCard);
        LinearLayout keypad = column(); keypad.setBackgroundColor(BG); padding(keypad, 14, 5); String[][] keys = {{"7", "8", "9", "⌫"}, {"4", "5", "6", "+"}, {"1", "2", "3", "−"}, {"清空", "0", ".", "今天"}};
        for (String[] line : keys) { LinearLayout keyrow = row(); for (String key : line) { boolean special = key.equals("今天") || key.equals("+") || key.equals("−") || key.equals("⌫") || key.equals("清空"); TextView b = text(key, key.equals("今天") || key.equals("清空") ? 15 : 23, special ? GREEN : DARK, true); b.setGravity(Gravity.CENTER); b.setBackground(shape(special ? SOFT : WHITE, 12)); b.setMinHeight(dp(48)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1); p.setMargins(dp(2), dp(2), dp(2), dp(2)); keyrow.addView(b, p); b.setOnClickListener(v -> onKey(key)); } keypad.addView(keyrow); }
        TextView save = button("保存记录", true, this::saveDraft); LinearLayout.LayoutParams saveParams = lp(-1, 54); saveParams.setMargins(dp(2), dp(6), dp(2), dp(2)); keypad.addView(save, saveParams); shell.addView(keypad); entryKeypad = keypad; shell.setFocusableInTouchMode(true); shell.requestFocus(); root.requestApplyInsets();
    }
    private void preserveNote() { if (noteView != null) draft.note = noteView.getText().toString(); }
    private void onKey(String key) {
        if (key.equals("今天")) { showDatePicker(); return; } if (key.equals("清空")) { expression = ""; amountView.setText("¥ 0.00"); return; }
        if (key.equals("⌫")) { if (!expression.isEmpty()) expression = expression.substring(0, expression.length() - 1); }
        else if (key.equals("+") || key.equals("−")) { if (expression.isEmpty()) return; if (expression.endsWith("+") || expression.endsWith("−")) expression = expression.substring(0, expression.length() - 1) + key; else expression += key; }
        else if (expression.length() < 30) { int lastOperator = Math.max(expression.lastIndexOf('+'), expression.lastIndexOf('−')); String part = expression.substring(lastOperator + 1); if (key.equals(".")) { if (part.contains(".")) return; if (part.isEmpty()) expression += "0"; } else if (part.contains(".") && part.substring(part.indexOf('.') + 1).length() >= 2) return; expression += key; }
        if (amountView != null) amountView.setText(expression.isEmpty() ? "¥ 0.00" : "¥ " + expression);
    }
    private void showDatePicker() {
        LocalDate d = LocalDate.parse(draft.businessDate); DatePickerDialog picker = new DatePickerDialog(this, (view, y, m, day) -> { preserveNote(); draft.businessDate = LocalDate.of(y, m + 1, day).toString(); dateView.setText("▦  " + draft.businessDate + "  ·  点击选择日期  ›"); }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
        picker.getDatePicker().setMinDate(-2208988800000L); picker.getDatePicker().setMaxDate(System.currentTimeMillis()); picker.setButton(DatePickerDialog.BUTTON_NEUTRAL, "今天", (dialog, which) -> { preserveNote(); draft.businessDate = LedgerMath.today(); dateView.setText("▦  " + draft.businessDate + "  ·  点击选择日期  ›"); }); picker.show();
    }
    private void saveDraft() { if (saving || entryClosing) return; saving = true; try { preserveNote(); draft.note = draft.note.trim(); draft.amountMinor = LedgerMath.expressionMinor(expression); db.saveEntry(draft); closeEntry(true); } catch (Exception e) { error(e); } finally { saving = false; } }
    private void closeEntry(boolean saved) {
        if (entryCard == null || entryClosing) return; entryClosing = true; FrameLayout card = entryCard;
        if (saved) render();
        Runnable finish = () -> { root.removeView(card); entryCard = null; entryKeypad = null; draft = null; if (saved) success("已保存"); };
        if (ValueAnimator.areAnimatorsEnabled()) card.animate().translationY(root.getHeight()).setDuration(270).setInterpolator(new DecelerateInterpolator()).setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) { finish.run(); } }).start(); else finish.run();
    }
    private void backPage() { if (!history.isEmpty()) page = history.pop(); else page = new Page("home", null); render(); }
    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        if (entryCard != null) { if (entryClosing) return; preserveNote(); if (!snapshot().equals(initialDraft)) new AlertDialog.Builder(this).setTitle("放弃这笔记录？").setMessage("已输入的内容不会保存。") .setNegativeButton("继续填写", null).setPositiveButton("放弃", (d, w) -> closeEntry(false)).show(); else closeEntry(false); return; }
        if (!history.isEmpty()) { backPage(); return; } if (!page.name.equals("home")) { tab("home"); return; } finish();
    }
}
