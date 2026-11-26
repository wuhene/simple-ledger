package com.wuhene.simpleledger;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
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
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.Spinner;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int BG = Color.WHITE, WHITE = Color.WHITE, DARK = 0xff23252d, MUTED = 0xff626773, LINE = 0xffe2e4e9, RED = 0xffb23832;
    private static final int EXPENSE_COLOR = 0xff21634d, FIELD = 0xfff2f3f6, CORAL = 0xffd77b6e;
    private static final int EXPORT = 41, IMPORT = 42, EXPORT_JSON = 43, LOCATION = 44, EXPORT_REPORT = 45;
    private final ArrayDeque<Page> history = new ArrayDeque<>();
    private LedgerDb db;
    private FrameLayout root, host, entryCard;
    private LinearLayout entryKeypad;
    private TextView amountView, dateView;
    private EditText noteView;
    private Page page = new Page("home", null);
    private String month = LedgerMath.month(LedgerMath.today()), statsKind = "month", statsType = LedgerDb.EXPENSE, statsAnchor = LedgerMath.today(), billKind = "month";
    private String detailStart, detailEnd;
    private boolean hidden, entryClosing, saving, showAllMonths;
    private LedgerDb.Entry draft;
    private String expression = "", initialDraft = "";
    private int themeId, GREEN, SOFT, ACTION, ACTION_TEXT, TOP, TOP_TEXT;
    private LedgerAssistant assistant;
    private ScrollView pageScroll, entryCategoryScroll;
    private LinearLayout entryDock;
    private boolean ready, assistantBusy, assistantFollowBottom, locating, locationConsentPending, managingEntryCategories;
    private String searchText = "", savedEntryId;
    private List<LedgerDb.Entry> searchRows = new ArrayList<>();
    private int searchLimit = 40, assistantRequest;
    private EditText assistantComposer;
    private String assistantProgress = "";
    private Page renderedPage;
    private String categoryType = LedgerDb.EXPENSE;
    private boolean showAllReportEntries;
    private int locationRequest;
    private int billYear = LocalDate.now().getYear();
    private boolean animateNavigation;
    private View highlightedEntry;
    private TextView saveStatus;
    private ListView recordList;

    private static final class Page { final String name, id; int scroll, firstRow, rowOffset; Page(String name, String id) { this.name = name; this.id = id; } }

    /**
     * 初始化安全区、原生页面和本地账本。
     */
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        db = new LedgerDb(this);
        applyPalette(getPreferences(MODE_PRIVATE).getInt("theme", 0));
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom; boolean keyboard;
            if (Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom); keyboard = insets.isVisible(WindowInsets.Type.ime()); }
            else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); keyboard = bottom > dp(120); }
            if (entryKeypad != null) entryKeypad.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            v.setPadding(0, top, 0, bottom); return insets;
        });
        host = new FrameLayout(this); root.addView(host, new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        TextView loading = text("正在打开本地账本…", 16, MUTED, false);
        loading.setGravity(Gravity.CENTER);
        host.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        new Thread(() -> {
            try {
                prepareAcceptanceLedger(loading);
                boolean hide = db.hideAmounts();
                LedgerAssistant restored = new LedgerAssistant(this, db);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    assistant = restored;
                    hidden = hide;
                    ready = true;
                    render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> loading.setText("无法打开本地账本\n" + error.getMessage()));
            }
        }, "ledger-open").start();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    }
    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    /**
     * 创建带边框的圆角背景。
     */
    private GradientDrawable outline(int color, int radius, int stroke) { GradientDrawable d = shape(color, radius); d.setStroke(dp(1), stroke); return d; }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(1); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(0); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void padding(View v, int h, int vert) { v.setPadding(dp(h), dp(vert), dp(h), dp(vert)); }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)); }
    private TextView text(String value, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private void space(LinearLayout l, int h) { l.addView(new View(this), lp(1, h)); }
    /**
     * 创建采用当前主题的操作按钮。
     */
    private TextView button(String label, boolean primary, Runnable action) { TextView b = text(label, 15, primary ? ACTION_TEXT : GREEN, true); b.setGravity(Gravity.CENTER); b.setBackground(shape(primary ? ACTION : SOFT, 14)); b.setMinHeight(dp(48)); b.setOnClickListener(v -> action.run()); return b; }
    /**
     * 生成有选中样式的分段按钮。
     */
    private TextView segment(String label, boolean selected, Runnable action) {
        TextView button = text(label, 15, selected ? GREEN : MUTED, selected);
        button.setGravity(Gravity.CENTER);
        if (selected) {
            button.setBackground(shape(WHITE, 14));
            button.setElevation(dp(1));
        }
        button.setMinHeight(dp(44));
        button.setOnClickListener(view -> action.run());
        return button;
    }
    private TextView ghost(String label, Runnable action) { TextView b = text(label, 15, GREEN, true); b.setGravity(Gravity.CENTER); b.setMinHeight(dp(44)); b.setOnClickListener(v -> action.run()); return b; }
    /**
     * 创建白底内容卡片。
     */
    private LinearLayout card() { LinearLayout l = column(); l.setBackground(outline(WHITE, 20, LINE)); padding(l, 18, 16); return l; }
    private void addCard(LinearLayout parent, View card) { LinearLayout.LayoutParams p = lp(-1, -2); p.setMargins(dp(18), 0, dp(18), dp(12)); parent.addView(card, p); }
    /**
     * 使用账本的金额显示偏好格式化金额。
     */
    private String money(long minor) { return hidden ? "••••" : (minor < 0 ? "−" : "") + "¥" + LedgerMath.money(Math.abs(minor)); }
    private String icon(String key) { switch (key) { case "food": return "◒"; case "bag": return "▣"; case "home": return "⌂"; case "bus": return "▰"; case "gift": return "◇"; case "heart": return "♡"; case "book": return "▤"; case "briefcase": return "▥"; case "medical": return "+"; case "pet": return "♧"; case "car": return "▭"; default: return "◌"; } }
    private void error(Exception e) { Toast.makeText(this, e.getMessage() == null ? "操作失败" : e.getMessage(), Toast.LENGTH_LONG).show(); }
    private void success(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private boolean isMain() { return page.name.equals("home") || page.name.equals("stats") || page.name.equals("tools") || page.name.equals("me"); }
    /**
     * 打开二级页面并保留来源页的阅读位置。
     */
    private void navigate(String target, String id) {
        keepScroll();
        history.push(page);
        page = new Page(target, id);
        animateNavigation = true;
        render();
    }
    private void tab(String target) { page = new Page(target, null); history.clear(); render(); }
    private void section(LinearLayout body, String label) { LinearLayout l = column(); padding(l, 24, 10); l.addView(text(label, 20, DARK, true)); body.addView(l); }
    /**
     * 显示带图标和说明的功能入口。
     */
    private void menu(LinearLayout body, String name, String caption, Runnable action) {
        LinearLayout c = card(); padding(c, 15, 14); LinearLayout line = row();
        String iconKey = name.contains("账单") || name.contains("报告") ? "book" : name.contains("分类") ? "bag" : name.contains("资产") ? "home" : name.contains("预算") ? "briefcase" : name.contains("成就") ? "gift" : "other";
        FrameLayout badge = new FrameLayout(this); badge.setBackground(shape(SOFT, 13)); badge.addView(categoryIconView(iconKey, GREEN), new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)); line.addView(badge, lp(42, 42));
        LinearLayout labels = column(); labels.setPadding(dp(12), 0, 0, 0); labels.addView(text(name, 16, DARK, true)); space(labels, 4); labels.addView(text(caption, 12, MUTED, false)); line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text("›", 25, MUTED, false)); c.addView(line); c.setOnClickListener(v -> action.run()); addCard(body, c);
    }
    private String monthEnd(String m) { return YearMonth.parse(m).atEndOfMonth().toString(); }

    /**
     * 渲染当前页面并恢复滚动位置。
     */
    private void render() {
        if (!ready) return;
        if (renderedPage == page) keepScroll();
        highlightedEntry = null;
        saveStatus = null;
        host.removeAllViews();
        LinearLayout shell = column(); shell.setBackgroundColor(BG); host.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        if ("home".equals(page.name)) {
            renderHomePage(shell);
            return;
        }
        recordList = null;
        boolean chat = "assistant".equals(page.name);
        if (chat) renderAssistantHeader(shell);
        String secondaryTitle = switch (page.name) {
            case "detail" -> "记录详情";
            case "assets" -> "资产概览";
            case "bills" -> "账单";
            case "budget" -> "月总预算";
            case "report" -> billKind.equals("year") ? page.id.substring(0, 4) + "年账单" : monthLabel(page.id) + "账单";
            case "categories" -> "分类管理";
            case "settings" -> "设置与数据";
            case "search" -> "搜索流水";
            case "assistantSettings" -> "AI 助手设置";
            case "assistantHistory" -> "历史会话";
            case "tools" -> "工具";
            case "me" -> "我的";
            default -> "";
        };
        if (!secondaryTitle.isEmpty()) renderSecondaryBar(shell, secondaryTitle);
        if ("categoryDetail".equals(page.name)) {
            renderCategoryDetail(shell);
            return;
        }
        if ("stats".equals(page.name)) {
            LinearLayout heading = row();
            padding(heading, 18, 6);
            heading.addView(text("统计", 22, DARK, true), new LinearLayout.LayoutParams(0, dp(48), 1));
            View eye = eyeButton(DARK);
            eye.setBackground(shape(SOFT, 15));
            heading.addView(eye, lp(46, 44));
            shell.addView(heading);
        }
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        int topPadding = switch (page.name) {
            case "detail", "assets" -> 28;
            case "bills" -> 22;
            case "assistant", "stats" -> 0;
            default -> 14;
        };
        body.setPadding(0, dp(topPadding), 0, dp(chat ? 8 : 24));
        scroll.addView(body);
        switch (page.name) {
            case "stats": renderStats(body); break;
            case "tools": renderTools(body); break;
            case "bills": renderBills(body); break;
            case "me": renderMe(body); break;
            case "detail": renderDetail(body); break;
            case "budget": renderBudget(body); break;
            case "assets": renderAssets(body); break;
            case "categories": renderCategories(body); break;
            case "settings": renderSettings(body); break;
            case "report": renderReport(body); break;
            case "achievements": renderAchievements(body); break;
            case "search": renderSearch(body); break;
            case "assistant": renderAssistantMessages(body); break;
            case "assistantSettings": renderAssistantSettings(body); break;
            case "assistantHistory": renderAssistantHistory(body); break;
        }
        if (chat) {
            TextView latest = ghost("回到最新 ↓", () -> scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)));
            latest.setVisibility(View.GONE);
            latest.setBackground(outline(WHITE, 16, LINE));
            FrameLayout.LayoutParams jump = new FrameLayout.LayoutParams(dp(105), dp(44), Gravity.END | Gravity.BOTTOM);
            jump.setMargins(0, 0, dp(12), dp(66));
            host.addView(latest, jump);
            scroll.setOnScrollChangeListener((view, x, y, previousX, previousY) ->
                    latest.setVisibility(scroll.getChildAt(0).getBottom() - y - scroll.getHeight() > dp(120) ? View.VISIBLE : View.GONE));
            if (assistant.configured()) renderAssistantComposer(shell);
        }
        if (isMain()) renderTabs(shell);
        if ("home".equals(page.name) || "stats".equals(page.name)) addAssistantFab();
        if (animateNavigation) {
            animateNavigation = false;
            animateIn(scroll, 12, .5f, 260);
        }
        renderedPage = page;
        boolean follow = chat && assistantFollowBottom;
        if (chat) assistantFollowBottom = false;
        int position = page.scroll;
        scroll.post(() -> {
            if (pageScroll != scroll) return;
            scroll.scrollTo(0, follow || position == Integer.MAX_VALUE ? scroll.getChildAt(0).getHeight() : position);
        });
    }
    /**
     * 绘制与 Web 同序的底部导航。
     */
    private void renderTabs(LinearLayout shell) {
        LinearLayout nav = row(); nav.setBackgroundColor(WHITE); nav.setPadding(dp(8), dp(5), dp(8), dp(5));
        String[] names = {"home", "stats", "entry", "tools", "me"};
        String[] labels = {"明细", "统计", "记一笔", "工具", "我的"};
        for (int i = 0; i < names.length; i++) {
            final String target = names[i]; boolean entry = target.equals("entry"); boolean active = page.name.equals(target);
            LinearLayout tab = column(); tab.setGravity(Gravity.CENTER); tab.setContentDescription(labels[i]);
            if (entry) { TextView iconView = text("+", 28, ACTION_TEXT, true); iconView.setGravity(Gravity.CENTER); iconView.setBackground(shape(ACTION, 15)); tab.addView(iconView, lp(48, 48)); }
            else { View iconView = navIcon(target, active ? ACTION_TEXT : MUTED); if (active) iconView.setBackground(shape(ACTION, 15)); tab.addView(iconView, lp(48, 32)); }
            TextView labelView = text(labels[i], 14, entry || active ? GREEN : MUTED, entry || active);
            labelView.setGravity(Gravity.CENTER); tab.addView(labelView, lp(-1, -2));
            tab.setMinimumHeight(dp(72));
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
    /**
     * 渲染账单共用的余额卡和收支摘要。
     */
    private void totalCard(LinearLayout body, LedgerDb.Totals totals, String label) {
        addCard(body, summaryPanel(label, money(totals.balance()), "收入", money(totals.income), GREEN, "支出", money(totals.expense), DARK));
    }

    /**
     * 展示主题色月头、月度摘要和白底流水。
     */
    private void renderHome(LinearLayout body, int count) {
        LinearLayout hero = column();
        hero.setBackgroundColor(themeId == 0 ? ACTION : TOP);
        int headerInk = themeId == 0 ? ACTION_TEXT : TOP_TEXT;
        LinearLayout date = row();
        padding(date, 18, 8);
        TextView previous = ghost("‹", () -> shiftHome(-1, false));
        previous.setTextColor(headerInk);
        previous.setTextSize(26);
        previous.setContentDescription("上个月");
        date.addView(previous, lp(42, 44));
        TextView current = text(monthLabel(month) + "⌄", 16, headerInk, true);
        current.setContentDescription("选择月份 " + monthLabel(month));
        current.setOnClickListener(view -> chooseMonth());
        date.addView(current, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView following = ghost("›", () -> shiftHome(1, false));
        following.setTextColor(headerInk);
        following.setTextSize(26);
        following.setContentDescription("下个月");
        date.addView(following, lp(42, 44));
        date.addView(eyeButton(headerInk), lp(44, 44));
        hero.addView(date);
        LedgerDb.Totals totals = db.totals(month + "-01", monthEnd(month));
        LinearLayout summary = column();
        summary.setBackground(shape(WHITE, 12));
        padding(summary, 14, 11);
        summary.addView(text("本月结余", 12, MUTED, false));
        summary.addView(text(money(totals.balance()), 28, DARK, true));
        space(summary, 6);
        rule(summary, LINE);
        space(summary, 5);
        LinearLayout numbers = row();
        numbers.addView(coloredAmount("收入", totals.income, RED), new LinearLayout.LayoutParams(0, -2, 1));
        numbers.addView(coloredAmount("支出", totals.expense, EXPENSE_COLOR), new LinearLayout.LayoutParams(0, -2, 1));
        summary.addView(numbers);
        addCard(hero, summary);
        body.addView(hero);
        LinearLayout heading = row();
        padding(heading, 20, 8);
        heading.addView(text("流水", 16, DARK, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (savedEntryId != null) {
            saveStatus = text("✓ 已保存", 12, GREEN, true);
            saveStatus.setPadding(0, 0, dp(8), 0);
            heading.addView(saveStatus);
        }
        heading.addView(text(count + " 笔", 12, MUTED, false));
        TextView find = ghost("⌕", () -> navigate("search", null));
        find.setTextSize(24);
        find.setContentDescription("搜索全部流水");
        heading.addView(find, lp(44, 44));
        body.addView(heading);
    }
    /**
     * 按业务日期分组并展示带分类图标的流水。
     */
    private void entryList(LinearLayout body, List<LedgerDb.Entry> entries) {
        if (entries.isEmpty()) {
            LinearLayout empty = card(); empty.addView(text("这个范围暂无记录", 15, MUTED, false));
            if ("home".equals(page.name)) empty.addView(button("记第一笔", false, () -> openEntry(null)));
            addCard(body, empty);
            return;
        }
        Map<String, LedgerDb.Category> categories = new HashMap<>();
        for (LedgerDb.Category category : db.categories(null, true)) categories.put(category.id, category);
        Map<String, LedgerDb.Totals> perDay = new HashMap<>(); for (LedgerDb.Entry e : entries) { LedgerDb.Totals t = perDay.computeIfAbsent(e.businessDate, key -> new LedgerDb.Totals()); if (e.type.equals(LedgerDb.EXPENSE)) t.expense += e.amountMinor; else t.income += e.amountMinor; }
        String last = ""; LinearLayout group = null;
        for (LedgerDb.Entry e : entries) {
            if (!e.businessDate.equals(last)) {
                group = column(); LedgerDb.Totals day = perDay.get(e.businessDate); LinearLayout dayHeader = row();
                padding(dayHeader, 7, 7);
                dayHeader.addView(text(dayLabel(e.businessDate), 13, DARK, true), new LinearLayout.LayoutParams(0, -2, 1));
                String summary = (day.income > 0 ? "收入 " + money(day.income) : "") + (day.income > 0 && day.expense > 0 ? " · " : "")
                        + (day.expense > 0 ? "支出 " + money(day.expense) : "");
                dayHeader.addView(text(summary, 11, MUTED, false)); group.addView(dayHeader); rule(group, LINE);
                LinearLayout.LayoutParams groupParams = lp(-1, -2); groupParams.setMargins(dp(20), 0, dp(20), dp(12)); body.addView(group, groupParams); last = e.businessDate;
            }
            LedgerDb.Category cat = categories.get(e.categoryId);
            LinearLayout line = row();
            padding(line, 8, 6);
            line.setMinimumHeight(dp(58));
            if (e.id.equals(savedEntryId)) {
                line.setBackground(shape(SOFT, 8));
                highlightedEntry = line;
            }
            line.addView(categoryBadge(cat, true, false), lp(32, 32));
            String name = cat == null ? "未知分类" : cat.name;
            LinearLayout left = column();
            left.setPadding(dp(10), 0, dp(8), 0);
            left.addView(text(e.note.isEmpty() ? name : e.note, 15, DARK, true));
            if (!e.note.isEmpty()) {
                space(left, 3);
                left.addView(text(name, 12, MUTED, false));
            }
            line.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
            line.addView(text((LedgerDb.EXPENSE.equals(e.type) ? "−" : "+") + (hidden ? "••••" : LedgerMath.money(e.amountMinor)),
                    15, LedgerDb.EXPENSE.equals(e.type) ? DARK : GREEN, true));
            line.setOnClickListener(v -> navigate("detail", e.id)); group.addView(line); rule(group, 0xfff7f8fa);
        }
    }
    /**
     * 展示可编辑流水及原话、线索和已授权位置。
     */
    private void renderDetail(LinearLayout body) {
        LedgerDb.Entry entry = db.findEntry(page.id);
        if (entry == null) {
            LinearLayout empty = card();
            empty.addView(text("记录已不存在", 15, MUTED, false));
            addCard(body, empty);
            return;
        }
        LedgerDb.Category cat = db.findCategory(entry.categoryId);
        String categoryName = cat == null ? "未知分类" : cat.name;
        boolean income = LedgerDb.INCOME.equals(entry.type);
        LinearLayout hero = card();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        padding(hero, 20, 20);
        hero.addView(text(income ? "收入" : "支出", 13, MUTED, false), lp(-2, -2));
        space(hero, 14);
        hero.addView(text((income ? "+" : "−") + money(entry.amountMinor), 34, income ? GREEN : DARK, true), lp(-2, -2));
        space(hero, 18);
        TextView category = text(categoryName, 12, GREEN, false);
        padding(category, 10, 4);
        category.setBackground(shape(0xffe7f1ec, 20));
        hero.addView(category, lp(-2, -2));
        addCard(body, hero);

        LinearLayout facts = card();
        padding(facts, 19, 13);
        detailMetric(facts, "分类", categoryName);
        detailMetric(facts, "日期", entry.businessDate);
        detailMetric(facts, "备注", entry.note.isEmpty() ? "无" : entry.note);
        if (entry.location != null) detailMetric(facts, "定位", String.format(Locale.ROOT, "%.5f, %.5f · 约 %.0f 米",
                entry.location.optDouble("latitude"), entry.location.optDouble("longitude"), entry.location.optDouble("accuracy")));
        if (entry.sourceText != null) detailMetric(facts, "记账原话", entry.sourceText);
        if (entry.clues != null && entry.clues.length() > 0) detailMetric(facts, "检索线索", clueLines(entry.clues));
        detailMetric(facts, "创建", localTimestamp(entry.createdAt));
        addCard(body, facts);

        LinearLayout actions = column();
        padding(actions, 18, 2);
        actions.addView(button("编辑记录", true, () -> openEntry(entry)), lp(-1, 52));
        space(actions, 12);
        TextView delete = button("删除记录", false, () -> confirmSheet("删除这笔记录？", "删除后五秒内可以撤销。",
                "保留", "删除", () -> { db.deleteEntry(entry.id); backPage(); offerUndo(entry); }));
        delete.setTextColor(RED);
        delete.setBackground(outline(WHITE, 15, 0xffe9cece));
        actions.addView(delete, lp(-1, 52));
        body.addView(actions);
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
    /**
     * 渲染周期、趋势和可下钻的分类排行。
     */
    private void renderStats(LinearLayout body) {
        body.setBackgroundColor(FIELD);
        String[] bounds = statsBounds();
        boolean income = LedgerDb.INCOME.equals(statsType);
        String periodText = "year".equals(statsKind) ? bounds[0].substring(0, 4) + "年"
                : "week".equals(statsKind) ? bounds[0].substring(5) + " — " + bounds[1].substring(5) : monthLabel(bounds[0].substring(0, 7));
        LinearLayout period = row();
        padding(period, 18, 10);
        TextView previous = ghost("‹", () -> statsPeriod(-1));
        previous.setBackground(shape(WHITE, 12));
        period.addView(previous, lp(40, 44));
        TextView label = text(periodText, 15, DARK, true);
        label.setGravity(Gravity.CENTER);
        period.addView(label, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView next = ghost("›", () -> statsPeriod(1));
        next.setBackground(shape(WHITE, 12));
        next.setEnabled(bounds[1].compareTo(LedgerMath.today()) < 0);
        next.setAlpha(next.isEnabled() ? 1 : .35f);
        period.addView(next, lp(40, 44));
        TextView scope = ghost(("year".equals(statsKind) ? "按年" : "week".equals(statsKind) ? "按周" : "按月") + " ⌄",
                () -> new AlertDialog.Builder(this).setItems(new String[]{"按周", "按月", "按年"}, (dialog, index) -> {
                    statsKind = new String[]{"week", "month", "year"}[index]; render();
                }).show());
        scope.setBackground(shape(WHITE, 12));
        LinearLayout.LayoutParams scopeParams = lp(60, 44);
        scopeParams.leftMargin = dp(7);
        period.addView(scope, scopeParams);
        body.addView(period);
        List<LedgerDb.Entry> rows = db.statsEntries(bounds[0], bounds[1], statsType);
        Map<String, Long> sums = new HashMap<>(); long total = 0;
        for (LedgerDb.Entry e : rows) { sums.put(e.categoryId, sums.getOrDefault(e.categoryId, 0L) + e.amountMinor); total += e.amountMinor; }
        LinearLayout trend = card();
        LinearLayout trendBar = row();
        trendBar.addView(text(income ? "收入趋势" : "支出趋势", 14, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout toggle = row();
        padding(toggle, 3, 3);
        toggle.setBackground(shape(FIELD, 12));
        toggle.addView(segment("支出", !income, () -> { statsType = LedgerDb.EXPENSE; render(); }), lp(52, 42));
        toggle.addView(segment("收入", income, () -> { statsType = LedgerDb.INCOME; render(); }), lp(52, 42));
        trendBar.addView(toggle);
        trend.addView(trendBar);
        trend.addView(text(money(total), 29, DARK, true));
        long days = ChronoUnit.DAYS.between(LocalDate.parse(bounds[0]), LocalDate.parse(bounds[1])) + 1;
        trend.addView(text("日均 " + money(Math.round(total / (double) days)) + " · " + days + " 天", 13, MUTED, false));
        renderStatsChart(trend, rows, bounds, income ? GREEN : CORAL);
        addCard(body, trend);
        section(body, "分类排行");
        final long denom = total; List<Map.Entry<String, Long>> ranking = new ArrayList<>(sums.entrySet()); ranking.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        LinearLayout list = card();
        if (ranking.isEmpty()) list.addView(text("本期还没有记录", 15, MUTED, false));
        for (Map.Entry<String, Long> item : ranking) {
            LedgerDb.Category cat = db.findCategory(item.getKey());
            if (cat == null) continue;
            float ratio = item.getValue() / (float) denom;
            LinearLayout group = column();
            LinearLayout line = row();
            line.addView(categoryBadge(cat, true, false), lp(32, 32));
            TextView name = text(cat.name, 15, DARK, true);
            name.setPadding(dp(8), 0, 0, 0);
            line.addView(name, new LinearLayout.LayoutParams(0, dp(42), 1));
            line.addView(text(money(item.getValue()) + (hidden ? "" : String.format(Locale.CHINA, "  %.1f%%", ratio * 100)), 14, DARK, true));
            group.addView(line);
            android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgress(hidden ? 0 : Math.round(1000 * ratio));
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(income ? GREEN : CORAL));
            group.addView(progress, lp(-1, 8));
            space(group, 8);
            group.setOnClickListener(v -> { detailStart = bounds[0]; detailEnd = bounds[1]; navigate("categoryDetail", cat.id); });
            list.addView(group);
        }
        addCard(body, list);
    }

    /**
     * 绘制账期柱状图及可查看的金额数据。
     */
    private void renderStatsChart(LinearLayout trend, List<LedgerDb.Entry> rows, String[] bounds, int fill) {
        boolean annual = "year".equals(statsKind);
        Map<String, Long> sums = new HashMap<>();
        for (LedgerDb.Entry entry : rows) sums.merge(annual ? entry.businessDate.substring(0, 7) : entry.businessDate, entry.amountMinor, Long::sum);
        List<String> days = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        long max = 0;
        LocalDate last = LocalDate.parse(bounds[1]);
        for (LocalDate day = LocalDate.parse(bounds[0]); !day.isAfter(last); day = annual ? day.plusMonths(1) : day.plusDays(1)) {
            String key = annual ? day.toString().substring(0, 7) : day.toString();
            long value = sums.getOrDefault(key, 0L);
            days.add(key);
            values.add(value);
            max = Math.max(max, value);
        }
        long peak = max;
        TextView selection = text("", 13, MUTED, false);
        View chart = new View(this) {
            /**
             * 布置网格刻度与当前统计类型的柱条。
             */
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                float left = dp(39), top = dp(10), bottom = getHeight() - dp(19);
                float width = (getWidth() - left) / Math.max(1, days.size());
                for (int i = 0; i < 3; i++) {
                    float y = top + (bottom - top) * i / 2f;
                    paint.setColor(LINE);
                    canvas.drawLine(left, y, getWidth(), y, paint);
                    paint.setColor(MUTED);
                    paint.setTextSize(dp(10));
                    String tick = hidden ? "••" : String.valueOf(Math.round(peak * (1 - i / 2.0) / 100));
                    canvas.drawText(tick, 0, y + dp(4), paint);
                }
                if (peak == 0 || hidden) return;
                paint.setColor(fill);
                for (int i = 0; i < values.size(); i++) {
                    float height = (bottom - top) * values.get(i) / peak;
                    float x = left + i * width + width * .23f;
                    canvas.drawRoundRect(x, bottom - height, x + width * .54f, bottom, dp(2), dp(2), paint);
                }
            }
        };
        chart.setContentDescription("当期金额趋势图");
        chart.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP && !days.isEmpty()) {
                float left = dp(39);
                int index = Math.min(days.size() - 1, Math.max(0, (int) ((event.getX() - left) / Math.max(1, view.getWidth() - left) * days.size())));
                selection.setText(days.get(index) + " · " + money(values.get(index)));
                view.performClick();
            }
            return true;
        });
        space(trend, 14);
        trend.addView(chart, lp(-1, 145));
        chatValue(trend, bounds[0], bounds[1]);
        trend.addView(selection);
        LinearLayout data = column();
        data.setVisibility(View.GONE);
        TextView expand = ghost("▸ 查看图表数据", () -> {
            boolean open = data.getVisibility() == View.GONE;
            data.setVisibility(open ? View.VISIBLE : View.GONE);
            ((TextView) trend.getChildAt(trend.indexOfChild(data) - 1)).setText(open ? "▾ 收起图表数据" : "▸ 查看图表数据");
        });
        expand.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        trend.addView(expand);
        for (int i = 0; i < days.size(); i++) chatValue(data, days.get(i), money(values.get(i)));
        trend.addView(data);
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
    /**
     * 用可复用列表展示分类对应账期的明细。
     */
    private void renderCategoryDetail(LinearLayout shell) {
        LedgerDb.Category category = db.findCategory(page.id);
        if (category == null) return;
        renderSecondaryBar(shell, category.name);
        String[] bounds = statsBounds();
        List<LedgerDb.Entry> records = db.statsEntries(detailStart == null ? bounds[0] : detailStart, detailEnd == null ? bounds[1] : detailEnd, category.type);
        records.removeIf(entry -> !category.id.equals(entry.categoryId));
        LinearLayout heading = column();
        space(heading, 14);
        if (records.isEmpty()) entryList(heading, records);
        attachRecordList(shell, new ListView(this), heading, records);
    }
    /**
     * 显示月度或年度账单的汇总与四列表格。
     */
    private void renderBills(LinearLayout body) {
        boolean monthly = "month".equals(billKind);
        LinearLayout toggles = row();
        padding(toggles, 4, 4);
        toggles.setBackground(shape(FIELD, 16));
        toggles.addView(segment("月账单", monthly, () -> { billKind = "month"; render(); }), new LinearLayout.LayoutParams(0, dp(44), 1));
        toggles.addView(segment("年账单", !monthly, () -> { billKind = "year"; render(); }), new LinearLayout.LayoutParams(0, dp(44), 1));
        addCard(body, toggles);
        LinearLayout table = card();
        padding(table, 18, 15);
        addBillRow(table, new String[]{monthly ? "月份" : "年份", "收入", "支出", "结余"}, null);
        String current = LedgerMath.month(LedgerMath.today());
        if (monthly) {
            LinearLayout selector = row();
            padding(selector, 18, 0);
            TextView prev = ghost("‹", () -> { billYear--; render(); });
            prev.setTextSize(24);
            prev.setEnabled(billYear > 1900);
            prev.setAlpha(prev.isEnabled() ? 1f : .35f);
            selector.addView(prev, lp(48, 44));
            TextView year = text(billYear + "年", 16, DARK, true);
            year.setGravity(Gravity.CENTER);
            selector.addView(year, new LinearLayout.LayoutParams(0, dp(44), 1));
            TextView next = ghost("›", () -> { billYear++; render(); });
            next.setTextSize(24);
            next.setEnabled(billYear < LocalDate.now().getYear());
            next.setAlpha(next.isEnabled() ? 1f : .35f);
            selector.addView(next, lp(48, 44));
            body.addView(selector);
            space(body, 8);
            totalCard(body, db.totals(billYear + "-01-01", billYear + "-12-31"), billYear + "年结余");
            LinearLayout options = column();
            padding(options, 18, 0);
            TextView toggle = ghost(showAllMonths ? "只看有记录月份" : "展开全部月份", () -> { showAllMonths = !showAllMonths; render(); });
            toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            options.addView(toggle, lp(-1, 44));
            body.addView(options);
            for (int i = 12; i >= 1; i--) {
                String selected = YearMonth.of(billYear, i).toString();
                LedgerDb.Totals totals = db.totals(selected + "-01", monthEnd(selected));
                if (!showAllMonths && (selected.compareTo(current) > 0 || totals.income == 0 && totals.expense == 0 && db.budget(selected) == 0)) continue;
                addBillRow(table, billValues(i + "月" + (selected.equals(current) ? " · 至今" : ""), totals),
                        () -> navigate("report", selected));
            }
        } else {
            totalCard(body, db.totals("1900-01-01", LedgerMath.today()), "累计结余");
            for (String year : db.years()) {
                LedgerDb.Totals totals = db.totals(year + "-01-01", year + "-12-31");
                addBillRow(table, billValues(year + "年", totals), () -> navigate("report", year + "-01"));
            }
        }
        if (table.getChildCount() == 1) {
            TextView empty = text("暂无账单", 14, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            table.addView(empty, lp(-1, 54));
        }
        addCard(body, table);
    }
    /**
     * 展示当前账期的账单、预算和资产快捷入口。
     */
    private void renderTools(LinearLayout body) {
        LinearLayout options = column();
        padding(options, 20, 0);
        compactMenu(options, "账单", monthLabel(month) + " · 结余 " + money(db.totals(month + "-01", monthEnd(month)).balance()), "▤", () -> {
            billYear = YearMonth.parse(month).getYear();
            navigate("bills", null);
        });
        compactMenu(options, "月总预算", db.budget(month) > 0 ? "查看已用与剩余" : "设置本月预算", "◌", () -> navigate("budget", null));
        String asset = db.hasAsset() ? "净资产 " + money(db.asset(false) - db.asset(true)) : "填写资产与负债";
        compactMenu(options, "资产概览", asset, "◇", () -> navigate("assets", null));
        body.addView(options);
    }
    /**
     * 展示月度或年度账单报告及图片入口。
     */
    private void renderReport(LinearLayout body) {
        String reportingMonth = page.id;
        boolean annual = billKind.equals("year");
        String start = annual ? reportingMonth.substring(0, 4) + "-01-01" : reportingMonth + "-01";
        String end = annual ? reportingMonth.substring(0, 4) + "-12-31" : monthEnd(reportingMonth);
        if (start.compareTo(LedgerMath.today()) > 0) { LinearLayout empty = card(); empty.addView(text("未来月份暂无报告", 15, MUTED, false)); addCard(body, empty); return; }
        LedgerDb.Totals t = db.totals(start, end);
        totalCard(body, t, (annual ? reportingMonth.substring(0, 4) + "年" : monthLabel(reportingMonth)) + "结余");
        List<LedgerDb.Entry> all = db.statsEntries(start, end, null);
        if (!annual) {
            String previous = LedgerMath.shiftMonth(reportingMonth, -1);
            LedgerDb.Totals prior = db.totals(previous + "-01", monthEnd(previous));
            TextView caption = text("上月结余 " + money(prior.balance()) + " · "
                    + (reportingMonth.equals(LedgerMath.month(LedgerMath.today())) ? "本月至今对比上月全月" : "本月全月对比上月全月"), 12, MUTED, false);
            padding(caption, 20, 2);
            body.addView(caption);
        }
        reportTypeSection(body, all, LedgerDb.EXPENSE, "支出组成");
        if (!annual) renderReportRanking(body, all);
        section(body, annual ? "年度月趋势" : "本月日趋势"); List<LedgerDb.Entry> expense = new ArrayList<>(); for (LedgerDb.Entry e : all) if (e.type.equals(LedgerDb.EXPENSE)) expense.add(e); renderTrend(body, expense, start, end.compareTo(LedgerMath.today()) > 0 ? LedgerMath.today() : end, annual ? "year" : "month");
        if (annual) {
            section(body, "12 个月支出");
            LinearLayout months = card();
            for (int i = 1; i <= 12; i++) {
                YearMonth bucket = YearMonth.of(Integer.parseInt(reportingMonth.substring(0, 4)), i);
                chatValue(months, i + " 月", money(db.totals(bucket.atDay(1).toString(), bucket.atEndOfMonth().toString()).expense));
            }
            addCard(body, months);
        }
        if (!annual) { String sixStart = YearMonth.parse(reportingMonth).minusMonths(5).atDay(1).toString(); section(body, "最近 6 个月支出对比"); renderTrend(body, db.entries(sixStart, end, LedgerDb.EXPENSE, null), sixStart, end.compareTo(LedgerMath.today()) > 0 ? LedgerMath.today() : end, "year"); reportCategoryChanges(body, expense); }
        reportTypeSection(body, all, LedgerDb.INCOME, "收入组成");
        if (annual) { section(body, "年度收入月趋势"); renderTrend(body, db.entries(start, end, LedgerDb.INCOME, null), start, end.compareTo(LedgerMath.today()) > 0 ? LedgerMath.today() : end, "year"); }
        if (!annual) { String sixStart = YearMonth.parse(reportingMonth).minusMonths(5).atDay(1).toString(); section(body, "最近 6 个月收入对比"); renderTrend(body, db.entries(sixStart, end, LedgerDb.INCOME, null), sixStart, end.compareTo(LedgerMath.today()) > 0 ? LedgerMath.today() : end, "year"); }
        if (!annual) {
            section(body, "当前记账成就");
            renderAchievements(body);
        }
        LinearLayout action = column(); padding(action, 18, 8); action.addView(button("预览并保存报告图片", true, () -> shareReport(start, end, t)), lp(-1, 50)); body.addView(action);
    }
    /**
     * 展示收支分类组成、比例图例和明细入口。
     */
    private void reportTypeSection(LinearLayout body, List<LedgerDb.Entry> all, String type, String title) {
        section(body, title); Map<String, Long> sums = new HashMap<>(); long total = 0;
        for (LedgerDb.Entry e : all) if (e.type.equals(type)) { sums.put(e.categoryId, sums.getOrDefault(e.categoryId, 0L) + e.amountMinor); total += e.amountMinor; }
        if (sums.isEmpty()) { LinearLayout empty = card(); empty.addView(text("暂无记录", 14, MUTED, false)); addCard(body, empty); return; }
        final long amount = total; List<Map.Entry<String, Long>> ranking = new ArrayList<>(sums.entrySet()); ranking.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Map<String, LedgerDb.Category> categories = new HashMap<>();
        for (LedgerDb.Category category : db.categories(type, true)) categories.put(category.id, category);
        if (LedgerDb.EXPENSE.equals(type)) addCard(body, reportDonut(ranking, categories, amount));
        LinearLayout rows = card();
        String reportMonth = page.id;
        boolean annual = "year".equals(billKind);
        for (Map.Entry<String, Long> item : ranking) {
            LedgerDb.Category category = categories.get(item.getKey());
            if (category == null) continue;
            LinearLayout group = column();
            padding(group, 0, 6);
            LinearLayout line = row();
            line.addView(categoryBadge(category, true, false), lp(31, 31));
            TextView label = text(category.name, 14, DARK, false);
            label.setPadding(dp(8), 0, 0, 0);
            line.addView(label, new LinearLayout.LayoutParams(0, dp(34), 1));
            line.addView(text(money(item.getValue()), 14, DARK, true));
            if (!hidden) {
                TextView percent = text(String.format(Locale.ROOT, "%.1f%%", item.getValue() * 100d / amount), 12, MUTED, false);
                percent.setPadding(dp(7), 0, 0, 0);
                line.addView(percent);
            }
            group.addView(line);
            if (!hidden) {
                android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(1000);
                bar.setProgress((int) (item.getValue() * 1000 / amount));
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
                group.addView(bar, lp(-1, 6));
            }
            group.setOnClickListener(view -> {
                detailStart = annual ? reportMonth.substring(0, 4) + "-01-01" : reportMonth + "-01";
                detailEnd = annual ? reportMonth.substring(0, 4) + "-12-31" : monthEnd(reportMonth);
                navigate("categoryDetail", category.id);
            });
            if (rows.getChildCount() > 0) rule(rows, 0xffedf1ee);
            rows.addView(group);
        }
        addCard(body, rows);
    }
    private void reportCategoryChanges(LinearLayout body, List<LedgerDb.Entry> current) {
        String reportMonth = page.id;
        String previous = LedgerMath.shiftMonth(reportMonth, -1); Map<String, Long> now = new HashMap<>(), before = new HashMap<>();
        for (LedgerDb.Entry e : current) now.put(e.categoryId, now.getOrDefault(e.categoryId, 0L) + e.amountMinor);
        for (LedgerDb.Entry e : db.entries(previous + "-01", monthEnd(previous), LedgerDb.EXPENSE, null)) before.put(e.categoryId, before.getOrDefault(e.categoryId, 0L) + e.amountMinor);
        java.util.Set<String> ids = new java.util.HashSet<>(now.keySet()); ids.addAll(before.keySet()); List<String> ranked = new ArrayList<>(ids); ranked.removeIf(id -> now.getOrDefault(id, 0L).equals(before.getOrDefault(id, 0L)));
        ranked.sort((a, b) -> Long.compare(Math.abs(now.getOrDefault(b, 0L) - before.getOrDefault(b, 0L)), Math.abs(now.getOrDefault(a, 0L) - before.getOrDefault(a, 0L))));
        section(body, "变化最大的分类"); if (ranked.isEmpty()) { LinearLayout c = card(); c.addView(text("较上月无分类变化", 14, MUTED, false)); addCard(body, c); }
        for (int i = 0; i < Math.min(3, ranked.size()); i++) { String id = ranked.get(i); LedgerDb.Category cat = db.findCategory(id); if (cat == null) continue; long old = before.getOrDefault(id, 0L), value = now.getOrDefault(id, 0L), delta = value - old; String phrase = old == 0 && value > 0 ? "新增支出" : delta > 0 ? "增加" : "减少"; menu(body, cat.name, phrase + "  " + money(Math.abs(delta)), () -> { detailStart = reportMonth + "-01"; detailEnd = monthEnd(reportMonth); navigate("categoryDetail", id); }); }
    }
    /**
     * 预览报告图片并选择保存或通过系统分享。
     */
    private void shareReport(String start, String end, LedgerDb.Totals totals) {
        LinearLayout preview = column();
        padding(preview, 18, 8);
        CheckBox amounts = new CheckBox(this), ranks = new CheckBox(this), achievements = new CheckBox(this);
        amounts.setText("图片包含金额");
        amounts.setChecked(!hidden);
        ranks.setText("包含单笔支出排行");
        achievements.setText("包含记账成就");
        preview.addView(amounts);
        preview.addView(ranks);
        preview.addView(achievements);
        ImageView imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.addView(imageView, lp(-1, 260));
        List<LedgerDb.Entry> largest = biggestExpenses(db.entries(start, end, LedgerDb.EXPENSE, null));
        Bitmap[] current = {null};
        Runnable refresh = () -> {
            Bitmap old = current[0];
            current[0] = reportBitmap(start, end, totals, largest, amounts.isChecked(), ranks.isChecked(), achievements.isChecked());
            imageView.setImageBitmap(current[0]);
            if (old != null) old.recycle();
        };
        refresh.run();
        for (CheckBox option : new CheckBox[]{amounts, ranks, achievements}) option.setOnCheckedChangeListener((view, checked) -> refresh.run());
        ScrollView panel = new ScrollView(this);
        panel.addView(preview);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("报告图片预览").setView(panel)
                .setNeutralButton("系统分享", (view, action) -> writeReportImage(current[0], false))
                .setPositiveButton("保存 PNG", (view, action) -> writeReportImage(current[0], true)).create();
        dialog.setOnDismissListener(view -> { imageView.setImageDrawable(null); current[0].recycle(); });
        dialog.show();
    }

    /**
     * 生成预览与保存共用的报告位图。
     */
    private Bitmap reportBitmap(String start, String end, LedgerDb.Totals totals, List<LedgerDb.Entry> ranked,
                                boolean withAmounts, boolean withRanks, boolean withAchievement) {
        Bitmap image = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(WHITE);
        Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
        pen.setColor(ACTION);
        canvas.drawRect(0, 0, 900, 210, pen);
        pen.setTypeface(Typeface.DEFAULT_BOLD);
        pen.setColor(ACTION_TEXT);
        pen.setTextSize(56);
        canvas.drawText("爱记账 · 收支报告", 72, 123, pen);
        pen.setTextSize(32);
        canvas.drawText(start + "  —  " + end, 72, 174, pen);
        pen.setColor(DARK);
        pen.setTextSize(51);
        canvas.drawText("收入  " + (withAmounts ? reportAmount(totals.income) : "••••"), 72, 336, pen);
        canvas.drawText("支出  " + (withAmounts ? reportAmount(totals.expense) : "••••"), 72, 450, pen);
        canvas.drawText("结余  " + (withAmounts ? reportAmount(totals.balance()) : "••••"), 72, 564, pen);
        int y = 680;
        pen.setTextSize(34);
        if (withRanks) {
            canvas.drawText("单笔支出排行", 72, y, pen);
            for (int i = 0; i < Math.min(4, ranked.size()); i++) {
                LedgerDb.Entry entry = ranked.get(i);
                LedgerDb.Category cat = db.findCategory(entry.categoryId);
                y += 61;
                canvas.drawText((i + 1) + ". " + (cat == null ? "分类" : cat.name) + " · " + entry.businessDate
                        + (withAmounts ? "  " + reportAmount(entry.amountMinor) : ""), 72, y, pen);
            }
        }
        if (withAchievement) canvas.drawText("累计记账 " + db.entryCount() + " 笔", 72, y + 72, pen);
        return image;
    }

    /**
     * 展示记账成就和账户内的使用入口。
     */
    private void renderMe(LinearLayout body) {
        LinearLayout profile = card();
        LinearLayout identity = row();
        TextView icon = text("账", 22, GREEN, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(SOFT, 16));
        identity.addView(icon, lp(52, 52));
        LinearLayout labels = column();
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text("认真记录每一天", 15, DARK, true));
        labels.addView(text("爱记账 · 本地账本", 12, MUTED, false));
        identity.addView(labels);
        profile.addView(identity);
        addCard(body, profile);
        renderAchievements(body);
        LinearLayout options = card();
        padding(options, 1, 2);
        compactMenu(options, "分类管理", "", "◇", () -> navigate("categories", null));
        compactMenu(options, "设置与数据", "", "⚙", () -> navigate("settings", null));
        compactMenu(options, "使用说明", "", "?", () -> infoSheet("使用说明",
                "点「记一笔」选择分类后输入金额，可点「今天」补记以前的账。点流水可编辑或删除。\n\n明细右下角进入 AI 助手。账本保存在本机，可在设置与数据中导出备份。"));
        compactMenu(options, "关于爱记账", "", "♡", () -> infoSheet("关于爱记账", "爱记账 Android 0.2.3。流水保存在手机本机。"));
        addCard(body, options);
    }
    /**
     * 显示月预算使用进度并提供当月编辑。
     */
    private void renderBudget(LinearLayout body) {
        monthSelector(body);
        long budget = db.budget(month), spent = db.totals(month + "-01", monthEnd(month)).expense;
        LinearLayout summary = card();
        summary.addView(text(monthLabel(month) + " · 月总预算", 14, MUTED, false));
        space(summary, 7);
        summary.addView(text(budget == 0 ? "尚未设置预算" : (spent > budget ? "已超支 " : "剩余 ") + money(Math.abs(budget - spent)),
                24, spent > budget && budget > 0 ? RED : DARK, true));
        if (budget > 0) {
            double ratio = spent / (double) budget;
            chatValue(summary, "已用 " + money(spent), hidden ? "" : String.format(Locale.CHINA, "%.2f%%", ratio * 100));
            android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgress(hidden ? 0 : (int) Math.round(Math.min(ratio, 1) * 1000));
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(spent > budget ? RED : GREEN));
            summary.addView(progress, lp(-1, 8));
        }
        addCard(body, summary);
        LinearLayout actions = column();
        padding(actions, 20, 6);
        EditText field = amountField(actions, "月总预算（元）", budget, budget > 0);
        field.setBackground(outline(WHITE, 12, LINE));
        actions.addView(text("每个月独立设置，收入不会增加预算。", 13, MUTED, false));
        space(actions, 8);
        actions.addView(button("保存预算", true, () -> {
            try {
                db.setBudget(month, LedgerMath.parseMoney(field.getText().toString(), false));
                hideKeyboard(); render(); success("预算已保存");
            } catch (Exception error) { field.setError(error.getMessage()); }
        }));
        if (budget > 0) {
            space(actions, 10);
            TextView delete = button("删除本月预算", false, () -> confirmSheet("删除本月预算？", monthLabel(month), "保留", "删除预算",
                    () -> { db.setBudget(month, 0); render(); }));
            delete.setTextColor(RED);
            delete.setBackground(outline(WHITE, 15, 0xffe9cece));
            actions.addView(delete);
        }
        body.addView(actions);
    }

    /**
     * 展示净资产并一次保存资产与负债。
     */
    private void renderAssets(LinearLayout body) {
        long assets = db.asset(false), debt = db.asset(true);
        boolean configured = db.hasAsset();
        addCard(body, summaryPanel("净资产", configured ? money(assets - debt) : "尚未设置",
                "资产", configured ? money(assets) : "—", DARK, "负债", configured ? money(debt) : "—", DARK));
        LinearLayout form = column();
        padding(form, 18, 0);
        TextView tip = text("资产为手动维护，收支记录不会自动改变资产。", 13, MUTED, false);
        padding(tip, 12, 10);
        tip.setLineSpacing(dp(3), 1f);
        tip.setBackground(shape(FIELD, 12));
        form.addView(tip);
        space(form, 12);
        LinearLayout fields = row();
        fields.setGravity(Gravity.TOP);
        LinearLayout left = column(), right = column();
        LinearLayout.LayoutParams leftSize = new LinearLayout.LayoutParams(0, -2, 1);
        leftSize.rightMargin = dp(10);
        fields.addView(left, leftSize);
        fields.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        EditText asset = amountField(left, "资产总额", assets, configured);
        EditText liability = amountField(right, "负债总额", debt, configured);
        asset.setBackground(outline(WHITE, 12, LINE));
        liability.setBackground(outline(WHITE, 12, LINE));
        form.addView(fields);
        space(form, 32);
        form.addView(button("保存资产概览", true, () -> {
            try {
                db.setAssets(LedgerMath.parseMoney(asset.getText().toString(), true), LedgerMath.parseMoney(liability.getText().toString(), true));
                hideKeyboard(); render(); success("资产已保存");
            } catch (Exception error) { error(error); }
        }), lp(-1, 52));
        if (configured) {
            space(form, 12);
            form.addView(text("更新于 " + localTimestamp(db.assetUpdatedAt()), 12, MUTED, false));
        }
        body.addView(form);
    }
    /**
     * 按收支和显示状态管理分类。
     */
    private void renderCategories(LinearLayout body) {
        LinearLayout types = row();
        padding(types, 4, 4);
        types.setBackground(shape(FIELD, 16));
        for (String type : new String[]{LedgerDb.EXPENSE, LedgerDb.INCOME}) {
            types.addView(segment(LedgerDb.EXPENSE.equals(type) ? "支出" : "收入", categoryType.equals(type), () -> {
                categoryType = type; page.scroll = 0; renderedPage = null; render();
            }), new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        addCard(body, types);
        LinearLayout action = column();
        padding(action, 18, 8);
        action.addView(button("＋ 新增分类", true, () -> categoryNameDialog(null, categoryType)));
        body.addView(action);
        List<LedgerDb.Category> list = db.categories(categoryType, true);
        for (boolean hiddenCategory : new boolean[]{false, true}) {
            boolean labelled = false;
            LinearLayout items = column();
            for (LedgerDb.Category cat : list) {
                if (cat.hidden != hiddenCategory) continue;
                if (!labelled) { section(body, hiddenCategory ? "已隐藏" : "使用中的分类"); labelled = true; }
                LinearLayout item = column();
                padding(item, 12, 7);
                LinearLayout line = row();
                line.addView(categoryBadge(cat, !cat.hidden, false), lp(34, 34));
                boolean fixed = LedgerDb.isDining(cat);
                LinearLayout label = column();
                label.setPadding(dp(9), 0, 0, 0);
                label.addView(text(cat.name, 15, DARK, true));
                label.addView(text((cat.builtin ? "内置" : "自建") + (fixed ? " · 置顶" : " · 长按拖动"), 12, MUTED, false));
                line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(ghost("编辑", () -> categoryActions(cat)), lp(50, 44));
                if (!fixed) {
                    line.addView(ghost(cat.hidden ? "恢复" : "隐藏", () -> {
                        try { db.setCategoryHidden(cat.id, !cat.hidden); render(); } catch (Exception error) { error(error); }
                    }), lp(50, 44));
                    item.setOnLongClickListener(view -> view.startDragAndDrop(null, new View.DragShadowBuilder(view), cat, 0));
                    item.setOnDragListener((view, event) -> categoryDrag(view, event, cat));
                }
                item.addView(line);
                if (items.getChildCount() > 0) rule(items, 0xffedf1ee);
                items.addView(item);
            }
            if (labelled) addCard(body, items);
        }
    }
    /**
     * 提供与分类权限一致的编辑选项。
     */
    private void categoryActions(LedgerDb.Category cat) {
        List<String> choices = new ArrayList<>();
        choices.add("选择图标");
        if (!LedgerDb.isDining(cat)) choices.addAll(Arrays.asList("重命名", "向前排序", "向后排序"));
        if (!cat.builtin && !LedgerDb.isDining(cat)) choices.add("删除分类");
        new AlertDialog.Builder(this).setTitle(cat.name).setItems(choices.toArray(new String[0]), (d, which) -> { try {
            switch (choices.get(which)) {
                case "重命名": categoryNameDialog(cat, cat.type); break;
                case "选择图标": categoryIconDialog(cat); break;
                case "向前排序": db.moveCategory(cat.id, -1); render(); break;
                case "向后排序": db.moveCategory(cat.id, 1); render(); break;
                default: new AlertDialog.Builder(this).setMessage("删除分类“" + cat.name + "”？").setNegativeButton("保留", null)
                        .setPositiveButton("删除", (a, b) -> { try { db.deleteCategory(cat.id); render(); } catch (Exception e) { error(e); } }).show();
            }
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
    /**
     * 通过日期索引与月汇总计算记账成就。
     */
    private void renderAchievements(LinearLayout body) {
        java.util.Set<String> days = db.recordedDates();
        LocalDate cursor = LocalDate.now();
        if (!days.contains(cursor.toString())) cursor = cursor.minusDays(1);
        int streak = 0;
        while (days.contains(cursor.toString())) { streak++; cursor = cursor.minusDays(1); }
        LinearLayout counters = row();
        padding(counters, 12, 14);
        counters.setBackground(outline(WHITE, 16, LINE));
        long[] values = {streak, days.size(), db.entryCount()};
        String[] labels = {"连续记账", "记账天数", "总笔数"};
        for (int i = 0; i < values.length; i++) {
            LinearLayout counter = column();
            counter.setGravity(Gravity.CENTER);
            counter.addView(text(String.valueOf(values[i]), 20, DARK, true));
            counter.addView(text(labels[i], 12, MUTED, false));
            counters.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        }
        addCard(body, counters);
    }
    /**
     * 提供主题、模型与可跨平台的备份设置。
     */
    private void renderSettings(LinearLayout body) {
        renderThemes(body);
        LinearLayout options = column();
        padding(options, 20, 0);
        compactMenu(options, "AI 助手设置", "", "", () -> navigate("assistantSettings", null));
        compactMenu(options, "默认隐藏金额", hidden ? "已开启" : "已关闭", "", this::toggleAmounts);
        compactMenu(options, "导出分月 ZIP 备份", "", "", this::exportBackup);
        compactMenu(options, "导出兼容 JSON 备份", "", "", () -> chooseExport(true));
        compactMenu(options, "从备份恢复", "", "", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "application/zip", "application/octet-stream"});
            startActivityForResult(intent, IMPORT);
        });
        space(options, 10);
        TextView tip = text("备份包含备注、检索线索和已添加的定位，请保管好文件。", 13, MUTED, false);
        tip.setBackground(shape(FIELD, 12));
        padding(tip, 12, 10);
        options.addView(tip);
        space(options, 12);
        TextView clear = button("清空全部数据", false, this::confirmClear);
        clear.setTextColor(RED);
        clear.setBackground(outline(WHITE, 15, 0xffe9cece));
        options.addView(clear, lp(-1, 52));
        body.addView(options);
    }
    /**
     * 打开分月备份的系统保存界面。
     */
    private void exportBackup() { chooseExport(false); }
    /**
     * 要求备份并输入确认语后才清空账本。
     */
    private void confirmClear() {
        LinearLayout content = column();
        padding(content, 22, 8);
        CheckBox backup = new CheckBox(this);
        backup.setText("我已保留备份");
        EditText phrase = new EditText(this);
        phrase.setSingleLine(true);
        phrase.setHint("输入清空账本");
        content.addView(backup);
        content.addView(phrase);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("清空全部数据")
                .setMessage("流水、预算、分类、资产和 AI 会话都将删除。").setView(content)
                .setNegativeButton("取消", null).setNeutralButton("先导出备份", (d, w) -> exportBackup())
                .setPositiveButton("确认清空", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!backup.isChecked() || !"清空账本".contentEquals(phrase.getText())) { phrase.setError("请确认备份并输入清空账本"); return; }
            dialog.dismiss();
            AlertDialog working = progress("正在清空…");
            new Thread(() -> {
                assistant.cancel();
                db.clear();
                assistant.reset();
                runOnUiThread(() -> { working.dismiss(); hidden = false; month = LedgerMath.month(LedgerMath.today()); tab("home"); success("已清空"); });
            }, "ledger-clear").start();
        }));
        dialog.show();
    }
    /**
     * 分派系统文件选择器返回的文件。
     */
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT || request == EXPORT_JSON) exportToUri(data.getData(), request == EXPORT_JSON);
        else if (request == IMPORT) readImportPreview(data.getData());
        else if (request == EXPORT_REPORT) saveReportTo(data.getData());
    }
    private AlertDialog progress(String message) { AlertDialog dialog = new AlertDialog.Builder(this).setMessage(message).setCancelable(false).create(); dialog.show(); return dialog; }
    /**
     * 在后台写出兼容备份。
     */
    private void exportToUri(Uri uri, boolean legacy) {
        AlertDialog busy = progress("正在导出账本…"); new Thread(() -> { Exception failure = null;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("无法写入文件");
                if (legacy) out.write(db.exportJson().toString().getBytes(StandardCharsets.UTF_8));
                else LedgerBackup.write(db, out);
            }
            catch (Exception e) { failure = e; } Exception result = failure; runOnUiThread(() -> { busy.dismiss(); if (result == null) success("备份已保存"); else error(result); });
        }).start();
    }
    /**
     * 在后台校验备份内容并打开恢复预览。
     */
    private void readImportPreview(Uri uri) {
        AlertDialog busy = progress("正在读取备份…"); new Thread(() -> { JSONObject json = null; Exception failure = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("无法读取文件");
                json = LedgerBackup.read(in);
                LedgerDb.validateBackup(json);
            }
            catch (Exception e) { failure = e; } JSONObject preview = json; Exception result = failure; runOnUiThread(() -> { busy.dismiss(); if (result == null) showRestorePreview(preview); else error(result); });
        }).start();
    }
    /**
     * 展示备份校验结果，确认后替换账本。
     */
    private void showRestorePreview(JSONObject json) {
        int entries = json.optJSONArray("entries").length();
        int categories = json.optJSONArray("categories").length();
        int budgets = json.optJSONArray("budgets").length();
        LinearLayout details = column();
        padding(details, 24, 12);
        details.addView(text("备份包含 " + entries + " 笔记账、" + categories + " 个分类、" + budgets + " 个月预算。恢复会替换当前账本。", 15, DARK, false));
        CheckBox backup = new CheckBox(this);
        backup.setText("我已保留当前账本的备份");
        details.addView(backup);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("恢复预览").setView(details)
                .setNegativeButton("保留当前", null).setPositiveButton("恢复", (ignored, action) -> {
                    AlertDialog busy = progress("正在验证并恢复…");
                    new Thread(() -> {
                        Exception failure = null;
                        try { db.importJson(json); } catch (Exception error) { failure = error; }
                        Exception result = failure;
                        runOnUiThread(() -> {
                            busy.dismiss();
                            if (result == null) { hidden = db.hideAmounts(); render(); success("恢复成功"); }
                            else error(result);
                        });
                    }, "ledger-restore").start();
                }).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            backup.setOnCheckedChangeListener((view, checked) -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(checked));
        });
        dialog.show();
    }

    /**
     * 以原生上滑覆盖打开新增或编辑草稿。
     */
    private void openEntry(LedgerDb.Entry source) {
        if (entryCard != null) return;
        draft = new LedgerDb.Entry();
        draft.type = source == null ? LedgerDb.EXPENSE : source.type;
        draft.businessDate = source == null ? LedgerMath.today() : source.businessDate;
        draft.note = source == null ? "" : source.note;
        expression = source == null ? "" : LedgerMath.money(source.amountMinor).replace(",", "");
        if (source != null) {
            draft.id = source.id;
            draft.categoryId = source.categoryId;
            draft.createdAt = source.createdAt;
            draft.sourceText = source.sourceText;
            draft.clues = source.clues;
            draft.location = source.location;
        }
        initialDraft = snapshot();
        entryClosing = false;
        entryCard = new FrameLayout(this);
        entryCard.setBackground(shape(BG, 15));
        entryCard.setClipToOutline(true);
        entryCard.setElevation(dp(8));
        entryCard.setClickable(true);
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.addView(entryCard, new FrameLayout.LayoutParams(-1, -1));
        buildEntry();
        if (ValueAnimator.areAnimatorsEnabled()) {
            entryCard.setTranslationY(root.getHeight());
            entryCard.animate().translationY(0).setDuration(260).setInterpolator(new PathInterpolator(.22f, 1f, .36f, 1f)).start();
        }
    }
    /**
     * 记录用于判断是否有未保存改动的草稿状态。
     */
    private String snapshot() { return draft.type + "|" + draft.categoryId + "|" + draft.businessDate + "|" + draft.note + "|" + expression + "|" + draft.location; }
    /**
     * 绘制分类优先的原生记账页面。
     */
    private void buildEntry() {
        if (entryCard == null) return;
        entryCard.removeAllViews();
        noteView = null; amountView = null; dateView = null; entryKeypad = null;
        LinearLayout shell = column();
        padding(shell, 16, 0);
        entryCard.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        TextView title = text(draft.id == null ? "记一笔" : "编辑记录", 22, DARK, true);
        title.setGravity(Gravity.CENTER);
        shell.addView(title, lp(-1, 58));
        LinearLayout typeSwitch = row(); typeSwitch.setBackground(shape(FIELD, 14)); padding(typeSwitch, 4, 4);
        for (String type : new String[]{LedgerDb.EXPENSE, LedgerDb.INCOME}) {
            TextView option = segment(LedgerDb.EXPENSE.equals(type) ? "支出" : "收入", type.equals(draft.type), () -> {
                if (type.equals(draft.type)) return;
                preserveNote();
                draft.type = type; draft.categoryId = null;
                buildEntry();
            });
            typeSwitch.addView(option, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        shell.addView(typeSwitch);
        LinearLayout categoryHeading = row();
        categoryHeading.addView(text("选择分类", 15, DARK, true), new LinearLayout.LayoutParams(0, -2, 1));
        categoryHeading.addView(ghost("管理分类", this::manageEntryCategories));
        shell.addView(categoryHeading);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        scroll.addView(grid);
        for (LedgerDb.Category category : db.categories(draft.type, true)) {
            boolean selected = category.id.equals(draft.categoryId);
            if (category.hidden && !selected) continue;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setSelected(selected);
            if (selected) item.setBackground(outline(SOFT, 10, ACTION));
            View badge = categoryBadge(category, selected, selected);
            badge.setTag(category.id);
            item.addView(badge, lp(38, 38));
            TextView name = text(category.name, 12, DARK, selected);
            name.setGravity(Gravity.CENTER);
            item.addView(name, lp(-1, 24));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0; params.height = dp(64);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(dp(3), dp(2), dp(3), dp(2));
            grid.addView(item, params);
            item.setOnClickListener(view -> {
                preserveNote();
                boolean reveal = draft.categoryId == null;
                int position = scroll.getScrollY();
                draft.categoryId = category.id;
                buildEntry();
                if (entryCategoryScroll != null) entryCategoryScroll.post(() -> entryCategoryScroll.scrollTo(0, position));
                if (ValueAnimator.areAnimatorsEnabled()) {
                    View activeIcon = entryCategoryScroll.findViewWithTag(category.id);
                    if (activeIcon != null) {
                        activeIcon.setScaleX(.94f);
                        activeIcon.setScaleY(.94f);
                        activeIcon.animate().scaleX(1f).scaleY(1f).setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                    }
                    if (reveal && entryDock != null) animateIn(entryDock, 10, .6f, 180);
                }
            });
        }
        entryCategoryScroll = scroll;
        entryDock = null;
        if (draft.categoryId != null) renderEntryDock(shell);
        shell.setFocusableInTouchMode(true);
        shell.requestFocus();
        root.requestApplyInsets();
    }
    private void preserveNote() { if (noteView != null) draft.note = noteView.getText().toString(); }
    /**
     * 编辑数字键盘中的金额表达式。
     */
    private void onKey(String key) {
        if (key.equals("日期")) { showDatePicker(); return; } if (key.equals("清空")) { expression = ""; amountView.setText("¥ 0.00"); return; }
        if (key.equals("⌫")) { if (!expression.isEmpty()) expression = expression.substring(0, expression.length() - 1); }
        else if (key.equals("+") || key.equals("−")) { if (expression.isEmpty()) return; if (expression.endsWith("+") || expression.endsWith("−")) expression = expression.substring(0, expression.length() - 1) + key; else expression += key; }
        else if (expression.length() < 30) { int lastOperator = Math.max(expression.lastIndexOf('+'), expression.lastIndexOf('−')); String part = expression.substring(lastOperator + 1); if (key.equals(".")) { if (part.contains(".")) return; if (part.isEmpty()) expression += "0"; } else if (part.contains(".") && part.substring(part.indexOf('.') + 1).length() >= 2) return; expression += key; }
        if (amountView != null) amountView.setText(expression.isEmpty() ? "¥ 0.00" : "¥ " + expression);
    }
    /**
     * 从键盘日期键打开可确认的日历。
     */
    private void showDatePicker() {
        preserveNote();
        new EntryCalendar(draft.businessDate, true).show();
    }
    /**
     * 保存草稿并在下滑前刷新对应账期。
     */
    private void saveDraft() {
        if (saving || entryClosing || draft.categoryId == null) return;
        saving = true;
        try {
            preserveNote();
            draft.note = draft.note.trim();
            draft.amountMinor = LedgerMath.expressionMinor(expression);
            db.saveEntry(draft);
            savedEntryId = draft.id;
            if ("home".equals(page.name) && !month.equals(LedgerMath.month(draft.businessDate))) {
                month = LedgerMath.month(draft.businessDate);
                page.firstRow = 0;
                page.rowOffset = 0;
                renderedPage = null;
            }
            closeEntry(true);
        } catch (Exception e) { error(e); } finally { saving = false; }
    }
    /**
     * 平滑下滑关闭记账层。
     */
    private void closeEntry(boolean saved) {
        if (entryCard == null || entryClosing) return; entryClosing = true; FrameLayout card = entryCard;
        if (saved) render();
        Runnable finish = () -> {
            root.removeView(card); entryCard = null; entryKeypad = null; draft = null;
            host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            entryCategoryScroll = null; entryDock = null; locating = false; noteView = null;
            if (saved) {
                if (saveStatus == null) success("已保存");
                String marker = savedEntryId;
                root.postDelayed(() -> clearSaveFeedback(marker), 2200);
            }
            root.requestApplyInsets();
        };
        if (ValueAnimator.areAnimatorsEnabled()) card.animate().translationY(root.getHeight()).setDuration(240)
                .setInterpolator(new PathInterpolator(.4f, 0f, 1f, 1f)).setListener(new AnimatorListenerAdapter() {
            /**
             * 关闭完成后移除记账视图。
             */
            @Override public void onAnimationEnd(Animator a) { finish.run(); }
        }).start(); else finish.run();
    }
    /**
     * 恢复来源页并播放二级页面返回动效。
     */
    private void backPage() {
        if (!history.isEmpty()) page = history.pop();
        else page = new Page("home", null);
        animateNavigation = !managingEntryCategories;
        render();
    }
    @Override public void onBackPressed() { handleBack(); }
    /**
     * 按 Android 返回顺序关闭浮层或返回上一级。
     */
    private void handleBack() {
        if (!ready) { finish(); return; }
        if (managingEntryCategories && "categories".equals(page.name)) {
            managingEntryCategories = false;
            backPage();
            if (entryCard != null) {
                host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                buildEntry();
                entryCard.setVisibility(View.VISIBLE);
            }
            return;
        }
        if (entryCard != null) {
            if (entryClosing) return;
            preserveNote();
            if (!snapshot().equals(initialDraft)) confirmSheet("放弃这笔记录？", "尚未保存的输入将丢失。", "继续填写", "放弃", () -> closeEntry(false));
            else closeEntry(false);
            return;
        }
        if (!history.isEmpty()) { backPage(); return; } if (!page.name.equals("home")) { tab("home"); return; } finish();
    }

    /**
     * 读取与 Web 一致的六套页面配色。
     */
    private void applyPalette(int chosen) {
        int[][] palettes = {
            {0xff795309, 0xfffff3bd, 0xffffda44, 0xff241d0c, 0xfff2f3f6, DARK},
            {0xff206549, 0xffecf5ef, 0xff206549, WHITE, 0xffeaf2ed, DARK},
            {0xff255c85, 0xffe8f1f9, 0xff255c85, WHITE, 0xffe7f0f8, DARK},
            {0xff6b4c7e, 0xfff0eaf4, 0xff6b4c7e, WHITE, 0xfff1ebf5, DARK},
            {0xff206549, 0xffecf5ef, 0xff206549, WHITE, 0xff193f33, 0xfff7fff9},
            {0xff255c85, 0xffe8f1f9, 0xff255c85, WHITE, 0xff1e3a55, 0xfff6fbff}
        };
        themeId = chosen >= 0 && chosen < palettes.length ? chosen : 0;
        int[] values = palettes[themeId];
        GREEN = values[0]; SOFT = values[1]; ACTION = values[2]; ACTION_TEXT = values[3]; TOP = values[4]; TOP_TEXT = values[5];
    }

    /**
     * 展示可即时预览并持久化的主题选择。
     */
    private void renderThemes(LinearLayout body) {
        String[] labels = {"亮黄","浅青绿","浅雾蓝","浅灰紫","深墨绿","深墨蓝"};
        int[] colors = {0xffffda44,0xff206549,0xff255c85,0xff6b4c7e,0xff193f33,0xff1e3a55};
        LinearLayout card = card();
        card.addView(text("外观主题", 15, DARK, true));
        for (int i = 0; i < labels.length; i += 3) {
            LinearLayout choices = row();
            for (int j = i; j < i + 3; j++) {
                int index = j;
                LinearLayout cell = column();
                cell.setGravity(Gravity.CENTER);
                cell.setBackground(outline(j == themeId ? SOFT : WHITE, 9, j == themeId ? GREEN : LINE));
                View swatch = new View(this);
                swatch.setBackground(shape(colors[j], 4));
                cell.addView(swatch, lp(38, 14));
                TextView label = text(labels[j], 12, DARK, themeId == j);
                label.setGravity(Gravity.CENTER);
                cell.addView(label, lp(-1, 28));
                cell.setSelected(j == themeId);
                cell.setContentDescription(labels[j]);
                cell.setOnClickListener(view -> {
                    getPreferences(MODE_PRIVATE).edit().putInt("theme", index).apply();
                    applyPalette(index);
                    render();
                });
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(62), 1);
                size.setMargins(dp(3), dp(4), dp(3), dp(3));
                choices.addView(cell, size);
            }
            card.addView(choices);
        }
        addCard(body, card);
    }

    /**
     * 创建一条细分隔线。
     */
    private void rule(LinearLayout parent, int color) {
        View line = new View(this);
        line.setBackgroundColor(color);
        parent.addView(line, lp(-1, 1));
    }

    /**
     * 保留当前原生滚动容器的位置。
     */
    private void keepScroll() {
        if (recordList != null) {
            page.firstRow = recordList.getFirstVisiblePosition();
            View first = recordList.getChildAt(0);
            page.rowOffset = first == null ? 0 : first.getTop();
        } else if (pageScroll != null) page.scroll = pageScroll.getScrollY();
    }

    /**
     * 生成首页短日期标题。
     */
    private String dayLabel(String iso) {
        LocalDate day = LocalDate.parse(iso), today = LocalDate.parse(LedgerMath.today());
        String shortDate = day.getMonthValue() + "月" + day.getDayOfMonth() + "日";
        if (!"home".equals(page.name)) return dateLabel(iso);
        if (day.equals(today)) return "今天 · " + shortDate;
        if (day.equals(today.minusDays(1))) return "昨天 · " + shortDate;
        return shortDate + " · " + new String[]{"周一","周二","周三","周四","周五","周六","周日"}[day.getDayOfWeek().getValue() - 1];
    }

    /**
     * 生成带年份的完整流水日期。
     */
    private String dateLabel(String iso) {
        LocalDate date = LocalDate.parse(iso);
        return date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    /**
     * 生成页面月份标题。
     */
    private String monthLabel(String iso) {
        YearMonth date = YearMonth.parse(iso);
        return date.getYear() + "年" + date.getMonthValue() + "月";
    }

    /**
     * 分别为字样和金额设置首页摘要样式。
     */
    private LinearLayout coloredAmount(String label, long minor, int color) {
        LinearLayout group = row();
        group.addView(text(label + "  ", 12, MUTED, false));
        group.addView(text(money(minor), 14, color, true));
        return group;
    }

    /**
     * 切换全账本金额显示偏好。
     */
    private void toggleAmounts() {
        hidden = !hidden;
        db.setHideAmounts(hidden);
        render();
    }

    /**
     * 创建金额显示切换入口。
     */
    private View eyeButton(int color) {
        View eye = new View(this) {
            /**
             * 绘制金额可见或隐藏图形。
             */
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.save();
                canvas.translate(getWidth() / 2f, getHeight() / 2f);
                canvas.scale(dp(1), dp(1));
                Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
                pen.setColor(color);
                pen.setStyle(Paint.Style.STROKE);
                pen.setStrokeWidth(1.7f);
                pen.setStrokeCap(Paint.Cap.ROUND);
                Path edge = new Path();
                edge.moveTo(-8, 0);
                edge.quadTo(0, -10, 8, 0);
                edge.quadTo(0, 10, -8, 0);
                canvas.drawPath(edge, pen);
                canvas.drawCircle(0, 0, 2.6f, pen);
                if (hidden) canvas.drawLine(-8, 7, 8, -7, pen);
                canvas.restore();
            }
        };
        eye.setContentDescription(hidden ? "显示金额" : "隐藏金额");
        eye.setOnClickListener(view -> toggleAmounts());
        return eye;
    }

    /**
     * 生成分类的两层矢量图标。
     */
    private FrameLayout categoryBadge(LedgerDb.Category category, boolean colored, boolean selected) {
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(shape(selected ? Color.TRANSPARENT : 0xfff4f4f6, 24));
        String name = LedgerIcons.graphic(category);
        if (colored) addIconLayer(badge, "cat_" + name + "_fill", themeId == 0 ? ACTION : blendAction());
        addIconLayer(badge, "cat_" + name + "_outline", 0xff3e4049);
        return badge;
    }

    /**
     * 将主题填色与白色混合为浅色图标填充。
     */
    private int blendAction() {
        return Color.rgb((int) (Color.red(ACTION) * .53 + 120), (int) (Color.green(ACTION) * .53 + 120),
                (int) (Color.blue(ACTION) * .53 + 120));
    }

    /**
     * 装载与 Web 同路径的原生矢量层。
     */
    private void addIconLayer(FrameLayout target, String name, int color) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id == 0) return;
        ImageView layer = new ImageView(this);
        layer.setImageResource(id);
        layer.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        target.addView(layer, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
    }

    /**
     * 放置首页和统计页右下方的 AI 入口。
     */
    private void addAssistantFab() {
        TextView fab = button("✦  AI", true, this::openAssistant);
        fab.setContentDescription("打开 AI 助手");
        fab.setBackground(shape(ACTION, 28));
        fab.setElevation(dp(3));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(66), dp(48), Gravity.BOTTOM | Gravity.END);
        position.setMargins(0, 0, dp(20), dp(90));
        host.addView(fab, position);
    }

    /**
     * 在来源页上打开最近的助手会话。
     */
    private void openAssistant() {
        assistantFollowBottom = true;
        navigate("assistant", null);
    }

    /**
     * 打开不需要模型的全文流水搜索。
     */
    private void renderSearch(LinearLayout body) {
        LinearLayout bar = row();
        padding(bar, 20, 4);
        EditText field = editField("备注、店名、菜品等", searchText, 120, true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        bar.addView(field, new LinearLayout.LayoutParams(0, dp(46), 1));
        TextView button = button("搜索", true, () -> searchFor(field.getText().toString()));
        LinearLayout.LayoutParams params = lp(68, 46);
        params.leftMargin = dp(8);
        bar.addView(button, params);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            searchFor(field.getText().toString());
            return true;
        });
        body.addView(bar);
        TextView count = text(searchText.isEmpty() ? "搜索所有年份，多个关键词用空格隔开。" : "找到 " + searchRows.size() + " 笔", 13, MUTED, false);
        padding(count, 20, 9);
        body.addView(count);
        if (searchText.isEmpty()) return;
        List<LedgerDb.Entry> shown = new ArrayList<>(searchRows);
        shown.sort(java.util.Comparator.comparing((LedgerDb.Entry entry) -> entry.businessDate).reversed()
                .thenComparing(entry -> entry.createdAt, java.util.Comparator.reverseOrder()));
        entryList(body, shown.subList(0, Math.min(shown.size(), searchLimit)));
        if (shown.size() > searchLimit) body.addView(button("加载更多", false, () -> { searchLimit += 40; render(); }));
    }

    /**
     * 在后台执行跨年关键词查询。
     */
    private void searchFor(String value) {
        String term = value.trim();
        Page source = page;
        hideKeyboard();
        new Thread(() -> {
            try {
                List<LedgerDb.Entry> result = term.isEmpty() ? new ArrayList<>()
                        : db.search(null, null, null, null, Arrays.asList(term.split("\\s+", 12)), null);
                runOnUiThread(() -> {
                    if (page != source) return;
                    searchRows = result; searchText = term; searchLimit = 40;
                    source.scroll = 0; renderedPage = null;
                    render();
                });
            } catch (Exception e) { runOnUiThread(() -> error(e)); }
        }, "ledger-search").start();
    }

    /**
     * 打开按年和月份选择的日期面板。
     */
    private void chooseMonth() {
        new EntryCalendar(month + "-01", false).show();
    }

    /**
     * 在相邻月份上下边缘之间切换。
     */
    private void shiftHome(int direction, boolean gesture) {
        String target = LedgerMath.shiftMonth(month, direction);
        if (target.compareTo("1900-01") < 0 || target.compareTo("9999-12") > 0 ||
                (gesture && direction > 0 && target.compareTo(LedgerMath.month(LedgerMath.today())) > 0)) return;
        month = target;
        page.firstRow = gesture && direction > 0 ? Integer.MAX_VALUE : 0;
        page.rowOffset = 0;
        renderedPage = null;
        render();
        if (gesture && direction > 0) success(monthLabel(month));
    }

    /**
     * 识别首页顶部和底部额外滑动。
     *
     * @author chaoshunh
     * @date 2026/11/25
     */
    private final class MonthScroll extends ListView {
        private float originX, originY, lastY, travel;
        private int direction;
        private boolean tracking;

        /**
         * 创建带月份边缘行为的原生滚动容器。
         */
        MonthScroll() { super(MainActivity.this); }

        /**
         * 累计超出滚动边界的单次触控位移。
         */
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                originX = event.getX(); originY = lastY = event.getY(); travel = 0; direction = 0; tracking = true;
            } else if (action == MotionEvent.ACTION_MOVE && tracking) {
                if (event.getPointerCount() != 1 || Math.abs(event.getX() - originX) > Math.max(dp(12), Math.abs(event.getY() - originY))) tracking = false;
                else {
                    float delta = lastY - event.getY();
                    lastY = event.getY();
                    int edge = delta > 0 && !canScrollVertically(1) ? -1 : delta < 0 && !canScrollVertically(-1) ? 1 : 0;
                    travel = edge == direction ? travel + Math.abs(delta) : Math.abs(delta);
                    direction = edge;
                }
            } else if (action == MotionEvent.ACTION_CANCEL) tracking = false;
            if (action != MotionEvent.ACTION_UP || !tracking || direction == 0 || travel < dp(64)) return super.dispatchTouchEvent(event);
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            int step = direction;
            tracking = false;
            post(() -> { if (recordList == this && "home".equals(page.name)) shiftHome(step, true); });
            return true;
        }
    }

    /**
     * 使用原生输入控件生成适合记账的文本字段。
     */
    private EditText editField(String hint, String value, int max, boolean single) {
        EditText field = new EditText(this);
        field.setTextColor(DARK);
        field.setTextSize(15);
        field.setHintTextColor(MUTED);
        field.setSingleLine(single);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        field.setHint(hint);
        field.setText(value);
        field.setBackground(outline(0xfff7f8fa, 12, LINE));
        padding(field, 10, 6);
        return field;
    }

    /**
     * 关闭输入法以显示完整列表或弹层。
     */
    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    /**
     * 绘制选中分类后出现的固定输入区。
     */
    private void renderEntryDock(LinearLayout shell) {
        LinearLayout dock = column();
        entryDock = dock;
        rule(dock, LINE);
        LinearLayout amountRow = row();
        LedgerDb.Category selected = db.findCategory(draft.categoryId);
        String label = (LedgerDb.EXPENSE.equals(draft.type) ? "支出金额" : "收入金额") + (selected == null ? "" : " · " + selected.name);
        amountRow.addView(text(label, 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        amountView = text(expression.isEmpty() ? "¥ 0.00" : "¥ " + expression, 30, DARK, true);
        amountView.setSingleLine(true);
        amountView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        amountRow.addView(amountView, new LinearLayout.LayoutParams(0, dp(44), 1.35f));
        dock.addView(amountRow);
        noteView = editField("备注  添加一点说明（可选）", draft.note, 200, true);
        dock.addView(noteView, lp(-1, 44));
        LinearLayout location = row();
        TextView locate = ghost(draft.location != null ? "移除定位" : locating ? "正在定位…" : "添加定位（可选）", this::changeLocation);
        locate.setEnabled(!locating);
        locate.setTextSize(13);
        location.addView(locate, lp(-2, 38));
        if (draft.location != null) location.addView(text("  已添加，仅存本机账本", 12, MUTED, false));
        dock.addView(location);
        LinearLayout keypad = column();
        keypad.setBackground(outline(LINE, 10, LINE));
        String[][] keys = {{"7", "8", "9", "⌫"}, {"4", "5", "6", "+"}, {"1", "2", "3", "−"}, {"清空", "0", ".", "日期"}};
        int keyHeight = getResources().getConfiguration().screenHeightDp < 690 ? 40 : 46;
        for (String[] group : keys) {
            LinearLayout line = row();
            for (int i = 0; i < group.length; i++) {
                String key = group[i];
                String labelText = "日期".equals(key) ? (draft.businessDate.equals(LedgerMath.today()) ? "▦ 今天" : draft.businessDate.substring(5)) : key;
                TextView value = text(labelText, "日期".equals(key) || "清空".equals(key) ? 13 : 21, DARK, true);
                value.setGravity(Gravity.CENTER);
                value.setBackgroundColor(i == 3 ? FIELD : WHITE);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(keyHeight), 1);
                params.setMargins(0, 0, dp(1), dp(1));
                line.addView(value, params);
                value.setOnClickListener(view -> onKey(key));
                if ("日期".equals(key)) { dateView = value; value.setContentDescription("选择记账日期 " + draft.businessDate); }
            }
            keypad.addView(line);
        }
        dock.addView(keypad);
        entryKeypad = keypad;
        space(dock, 6);
        dock.addView(button("保存记录", true, this::saveDraft), lp(-1, 49));
        space(dock, 6);
        shell.addView(dock);
    }

    /**
     * 暂离记账层编辑分类并保留草稿。
     */
    private void manageEntryCategories() {
        preserveNote();
        hideKeyboard();
        categoryType = draft.type;
        managingEntryCategories = true;
        entryCard.setVisibility(View.GONE);
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        navigate("categories", null);
    }

    /**
     * 根据备份类型打开系统文件选择器。
     */
    private void chooseExport(boolean legacy) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType(legacy ? "application/json" : "application/zip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "爱记账备份-" + LedgerMath.today() + (legacy ? ".json" : ".zip"));
        startActivityForResult(intent, legacy ? EXPORT_JSON : EXPORT);
    }

    /**
     * 将开放线索显示为可编辑的行。
     */
    private String clueLines(JSONArray clues) {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; clues != null && i < clues.length(); i++) {
            JSONObject clue = clues.optJSONObject(i);
            if (clue == null) continue;
            if (lines.length() > 0) lines.append('\n');
            lines.append(clue.optString("type")).append('：').append(clue.optString("value"));
        }
        return lines.toString();
    }

    /**
     * 显示助手标题、历史、新会话和模型设置入口。
     */
    private void renderAssistantHeader(LinearLayout shell) {
        LinearLayout bar = row();
        padding(bar, 18, 4);
        bar.addView(text("AI 助手", 20, DARK, true), new LinearLayout.LayoutParams(0, dp(52), 1));
        bar.addView(ghost("历史", () -> navigate("assistantHistory", null)), lp(50, 48));
        bar.addView(ghost("新会话", () -> {
            stopAssistant(false);
            assistant.open("");
            assistantFollowBottom = true;
            render();
        }), lp(66, 48));
        TextView settings = ghost("⚙", () -> navigate("assistantSettings", null));
        settings.setContentDescription("模型设置");
        bar.addView(settings, lp(42, 48));
        shell.addView(bar);
        rule(shell, LINE);
    }

    /**
     * 渲染历史消息、结构化卡片和待确认草稿。
     */
    private void renderAssistantMessages(LinearLayout body) {
        padding(body, 16, 12);
        JSONArray messages = assistant.messages();
        if (messages.length() == 0) {
            LinearLayout intro = column();
            padding(intro, 5, 0);
            space(intro, 78);
            TextView mark = text("✦", 25, GREEN, true);
            mark.setGravity(Gravity.CENTER);
            mark.setBackground(shape(SOFT, 15));
            intro.addView(mark, lp(53, 53));
            space(intro, 11);
            intro.addView(text("想记一笔，还是聊聊花销？", 22, DARK, true));
            space(intro, 9);
            if (assistant.configured()) {
                LinearLayout examples = row();
                for (String example : new String[]{"午餐花了 28 元", "这个月主要花在哪？"}) {
                    TextView suggestion = button(example, false, () -> sendAssistant(example));
                    suggestion.setBackground(outline(WHITE, 12, LINE));
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(46), 1);
                    size.rightMargin = dp(6);
                    examples.addView(suggestion, size);
                }
                intro.addView(examples);
            } else {
                intro.addView(text("连接模型后，就可以用一句话记账或查账。", 15, MUTED, false));
                space(intro, 11);
                intro.addView(button("连接模型", true, () -> navigate("assistantSettings", null)), lp(96, 46));
            }
            body.addView(intro);
        } else if (!assistant.configured()) {
            body.addView(ghost("连接模型继续聊天", () -> navigate("assistantSettings", null)));
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null) continue;
            space(body, 10);
            if (item.optBoolean("user")) {
                TextView bubble = text(item.optString("text"), 15, themeId == 0 ? WHITE : ACTION_TEXT, false);
                bubble.setTextIsSelectable(true);
                bubble.setMaxWidth(dp(320));
                bubble.setBackground(shape(themeId == 0 ? 0xff4c3605 : ACTION, 18));
                padding(bubble, 14, 10);
                LinearLayout.LayoutParams position = lp(-2, -2);
                position.gravity = Gravity.END;
                position.leftMargin = dp(28);
                body.addView(bubble, position);
                continue;
            }
            TextView sender = text("✦  爱记账助手", 13, GREEN, true);
            body.addView(sender, lp(-1, 28));
            switch (item.optString("kind")) {
                case "receipt": renderAssistantReceipt(body, item); break;
                case "result": renderAssistantResult(body, item); break;
                default:
                    JSONArray blocks = item.optJSONArray("blocks");
                    if (blocks != null) renderAssistantResult(body, item);
                    else chatParagraph(body, item.optString("text"), false);
                    break;
            }
        }
        JSONObject draft = assistant.draft();
        if (draft != null) renderAssistantDraft(body, draft);
        if (assistantBusy) {
            space(body, 10);
            LinearLayout progress = row();
            progress.addView(text(assistantProgress, 14, MUTED, false), new LinearLayout.LayoutParams(0, dp(44), 1));
            progress.addView(ghost("停止", () -> stopAssistant(true)), lp(58, 44));
            body.addView(progress);
        }
        space(body, 8);
    }

    /**
     * 固定输入区并允许 Android 输入法发送消息。
     */
    private void renderAssistantComposer(LinearLayout shell) {
        rule(shell, LINE);
        LinearLayout bar = row();
        padding(bar, 10, 8);
        assistantComposer = editField("输入一笔账，或聊聊花销", assistant.input, 600, false);
        assistantComposer.setMaxLines(4);
        assistantComposer.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        assistantComposer.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER && !event.isShiftPressed())) {
                if (event == null || event.getAction() == android.view.KeyEvent.ACTION_DOWN) sendAssistant(view.getText().toString());
                return true;
            }
            return false;
        });
        assistantComposer.addTextChangedListener(new android.text.TextWatcher() {
            /**
             * 接收文本变化前的通知。
             */
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            /**
             * 保留尚未发送的输入。
             */
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                assistant.input = value.toString();
            }
            /**
             * 接收文本变化后的通知。
             */
            @Override public void afterTextChanged(android.text.Editable value) {}
        });
        bar.addView(assistantComposer, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams action = lp(62, 48);
        action.leftMargin = dp(8);
        bar.addView(button(assistantBusy ? "停止" : "发送", true, () -> {
            if (assistantBusy) stopAssistant(true);
            else sendAssistant(assistantComposer.getText().toString());
        }), action);
        shell.addView(bar);
    }

    /**
     * 向已配置的模型发送输入并在当前会话追加回复。
     */
    private void sendAssistant(String value) {
        String message = value.trim();
        if (message.isEmpty() || assistantBusy) return;
        if (!assistant.configured()) {
            assistant.input = message;
            navigate("assistantSettings", null);
            return;
        }
        if (assistant.draft() != null) {
            success("请先确认或取消当前记账草稿");
            return;
        }
        if (message.length() > 600) {
            success("单条消息最多 600 字");
            return;
        }
        hideKeyboard();
        assistantBusy = true;
        assistantProgress = "正在处理…";
        int request = ++assistantRequest;
        int token = assistant.begin(message);
        assistantFollowBottom = true;
        render();
        new Thread(() -> {
            Exception failure = null;
            try { assistant.finish(message, token); }
            catch (Exception error) { failure = error; }
            Exception result = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || request != assistantRequest) return;
                assistantBusy = false;
                if (result != null) assistant.notice(result.getMessage() == null ? "模型暂时不可用，请重试。" : result.getMessage());
                if ("assistant".equals(page.name)) {
                    assistantFollowBottom = pageScroll == null || pageScroll.getChildAt(0).getBottom()
                            - pageScroll.getScrollY() - pageScroll.getHeight() < dp(120);
                    render();
                }
            });
        }, "ledger-assistant").start();
    }

    /**
     * 停止请求并刷新仍在显示的会话。
     */
    private void stopAssistant(boolean show) {
        if (!assistantBusy) return;
        ++assistantRequest;
        assistant.cancel();
        assistantBusy = false;
        if (show) {
            assistant.notice("已停止生成。");
            assistantFollowBottom = true;
            render();
        }
    }

    /**
     * 显示模型连接配置及测试操作。
     */
    private void renderAssistantSettings(LinearLayout body) {
        LinearLayout form = card();
        EditText base = assistantSettingField(form, "API 地址", "https://api.example.com/v1", assistant.base);
        EditText model = assistantSettingField(form, "模型 ID", "填写服务商模型 ID", assistant.model);
        EditText key = assistantSettingField(form, "API Key", assistant.key.isEmpty() ? "按服务商要求填写" : "已在本次运行中填写", "");
        for (EditText field : new EditText[]{base, model, key}) field.setBackground(outline(WHITE, 12, LINE));
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        space(form, 10);
        form.addView(text("模型会收到输入、必要的会话上下文和相关汇总。原始流水、备注和定位由本机处理。配置在本次运行中有效。", 13, MUTED, false));
        space(form, 14);
        LinearLayout actions = row();
        TextView status = text("", 13, MUTED, false);
        TextView testButton = button("测试连接", false, () -> {
            try {
                LedgerAssistant test = assistant.testConfiguration(base.getText().toString(), model.getText().toString(), key.getText().toString());
                status.setText("正在连接…");
                new Thread(() -> {
                    String message;
                    try { test.test(); message = "已连接，模型返回了有效结果。"; }
                    catch (Exception error) { message = error.getMessage() == null ? "连接失败" : error.getMessage(); }
                    String result = message;
                    runOnUiThread(() -> status.setText(result));
                }, "ledger-assistant-test").start();
            } catch (Exception error) { status.setText(error.getMessage()); }
        });
        testButton.setBackground(outline(WHITE, 12, LINE));
        actions.addView(button("保存设置", true, () -> {
            try {
                assistant.saveConfiguration(base.getText().toString(), model.getText().toString(), key.getText().toString());
                hideKeyboard();
                status.setText("已保存");
            } catch (Exception error) { status.setText(error.getMessage()); }
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams testSize = new LinearLayout.LayoutParams(0, dp(48), 1);
        testSize.leftMargin = dp(7);
        actions.addView(testButton, testSize);
        actions.addView(ghost("清除配置", () -> {
            stopAssistant(false);
            assistant.clearConfiguration();
            hideKeyboard();
            render();
            success("模型配置已清除");
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        form.addView(actions);
        form.addView(status);
        addCard(body, form);
    }

    /**
     * 添加带说明的模型配置输入框。
     */
    private EditText assistantSettingField(LinearLayout parent, String title, String hint, String value) {
        parent.addView(text(title, 14, DARK, true));
        space(parent, 5);
        EditText field = editField(hint, value, 2048, true);
        parent.addView(field, lp(-1, 48));
        space(parent, 12);
        return field;
    }

    /**
     * 列出可恢复的本机会话。
     */
    private void renderAssistantHistory(LinearLayout body) {
        List<JSONObject> sessions = db.assistantSessions();
        if (sessions.isEmpty()) {
            LinearLayout placeholder = card();
            placeholder.addView(text("暂无历史会话", 17, DARK, true));
            space(placeholder, 6);
            placeholder.addView(text("发出第一条消息后，会话会保存在这里。", 14, MUTED, false));
            addCard(body, placeholder);
            return;
        }
        for (JSONObject session : sessions) {
            menu(body, session.optString("title", "新会话"), session.optInt("count") + " 条消息", () -> {
                stopAssistant(false);
                assistant.open(session.optString("id"));
                assistantFollowBottom = true;
                if (!history.isEmpty() && "assistant".equals(history.peek().name)) backPage();
                else navigate("assistant", null);
            });
        }
    }

    /**
     * 显示可选中复制的正文或小标题。
     */
    private void chatParagraph(LinearLayout body, String value, boolean heading) {
        if (value.isEmpty()) return;
        TextView paragraph = text(value, heading ? 17 : 15, DARK, heading);
        paragraph.setLineSpacing(dp(4), 1f);
        paragraph.setTextIsSelectable(true);
        body.addView(paragraph);
        space(body, 7);
    }

    /**
     * 按模型给出的顺序渲染正文、列表及可选数据卡片。
     */
    private void renderAssistantResult(LinearLayout body, JSONObject item) {
        JSONArray blocks = item.optJSONArray("blocks");
        JSONObject snapshot = item.optJSONObject("snapshot");
        if (blocks == null) return;
        if (hidden && snapshot != null) chatParagraph(body, "账本金额已隐藏。", false);
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type");
            if ("paragraph".equals(type) || "heading".equals(type) || "notice".equals(type)) {
                if (!hidden || snapshot == null) chatParagraph(body, assistantPlaceholders(block.optString("text"), snapshot), "heading".equals(type));
            } else if ("bullets".equals(type)) {
                if (hidden && snapshot != null) continue;
                JSONArray items = block.optJSONArray("items");
                for (int j = 0; items != null && j < items.length(); j++) chatParagraph(body, "• " + assistantPlaceholders(items.optString(j), snapshot), false);
            } else if (snapshot != null) renderAssistantData(body, snapshot, type, block.optString("title"));
        }
    }

    /**
     * 渲染账本本机计算的汇总、明细、分类和对比。
     */
    private void renderAssistantData(LinearLayout body, JSONObject snapshot, String type, String title) {
        JSONObject facts = snapshot.optJSONObject("facts");
        if (facts == null) return;
        LinearLayout result = card();
        result.setBackground(outline(0xfffafafb, 16, LINE));
        String category = facts.optString("category");
        if (title.isEmpty()) {
            switch (type) {
                case "entries": title = (category.isEmpty() ? "流水" : category) + "明细"; break;
                case "categories": title = "分类排行"; break;
                case "comparison": title = "支出对比"; break;
                case "totals": title = category.isEmpty() ? "收支统计" : category + "汇总"; break;
                default: return;
            }
        }
        result.addView(text(title, 17, DARK, true));
        if (LedgerMath.validDate(facts.optString("start")) && LedgerMath.validDate(facts.optString("end"))) {
            space(result, 5);
            result.addView(text(dateLabel(facts.optString("start")) + " — " + dateLabel(facts.optString("end")), 13, MUTED, false));
        }
        space(result, 8);
        switch (type) {
            case "entries": renderAssistantEntries(result, snapshot, facts); break;
            case "categories":
                JSONArray ranking = snapshot.optJSONArray("ranking");
                for (int i = 0; ranking != null && i < ranking.length(); i++) {
                    JSONObject rank = ranking.optJSONObject(i);
                    if (rank != null) chatValue(result, rank.optString("name"),
                            money(rank.optLong("amountMinor")) + (hidden ? "" : String.format(Locale.CHINA, " · %.1f%%", rank.optDouble("percent"))));
                }
                if (ranking == null || ranking.length() == 0) result.addView(text("暂无分类记录", 14, MUTED, false));
                break;
            case "comparison":
                JSONObject baseline = facts.optJSONObject("baseline");
                chatValue(result, "本期支出", money(facts.optLong("expenseMinor")));
                if (baseline != null) {
                    chatValue(result, baseline.optString("start") + " 至 " + baseline.optString("end"), money(baseline.optLong("expenseMinor")));
                    if (!hidden && baseline.optLong("expenseMinor") > 0) chatValue(result, "与对比期相比",
                            String.format(Locale.CHINA, "%+.1f%%", 100d * (facts.optLong("expenseMinor") - baseline.optLong("expenseMinor")) / baseline.optLong("expenseMinor")));
                }
                break;
            default:
                if (!"expense".equals(facts.optString("metric"))) chatValue(result, "收入", money(facts.optLong("incomeMinor")));
                if (!"income".equals(facts.optString("metric"))) chatValue(result, "支出", money(facts.optLong("expenseMinor")));
                chatValue(result, "记录", facts.optInt("count") + " 笔");
                break;
        }
        body.addView(result);
        space(body, 8);
    }

    /**
     * 列出带完整年月日的本机流水，保留排名顺序。
     */
    private void renderAssistantEntries(LinearLayout result, JSONObject snapshot, JSONObject facts) {
        JSONArray entries = snapshot.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            result.addView(text("这段时间没有匹配记录。", 14, MUTED, false));
            return;
        }
        boolean ranked = "amount-desc".equals(facts.optString("order"));
        int initial = ranked ? entries.length() : Math.min(5, entries.length());
        LinearLayout extra = column();
        extra.setVisibility(View.GONE);
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            LinearLayout destination = i < initial ? result : extra;
            rule(destination, LINE);
            LinearLayout listRow = column();
            padding(listRow, 0, 7);
            String note = entry.optString("note");
            String date = entry.optString("date");
            listRow.addView(text((ranked ? (i + 1) + " · " : "") + (LedgerMath.validDate(date) ? dateLabel(date) : date), 12, MUTED, false));
            chatValue(listRow, note.isEmpty() ? entry.optString("category") : note,
                    (hidden ? "" : LedgerDb.EXPENSE.equals(entry.optString("type")) ? "−" : "+") + money(entry.optLong("amountMinor")));
            if (!entry.optString("clues", "").isEmpty()) listRow.addView(text(entry.optString("clues"), 12, MUTED, false));
            String id = entry.optString("id");
            listRow.setOnClickListener(v -> {
                if (db.findEntry(id) == null) success("这笔流水已删除");
                else navigate("detail", id);
            });
            destination.addView(listRow);
        }
        if (initial < entries.length()) {
            result.addView(extra);
            TextView expand = ghost("展开其余 " + (entries.length() - initial) + " 笔", () -> {
                View trigger = result.getChildAt(result.indexOfChild(extra) + 1);
                boolean open = extra.getVisibility() != View.VISIBLE;
                extra.setVisibility(open ? View.VISIBLE : View.GONE);
                ((TextView) trigger).setText(open ? "收起明细" : "展开其余 " + (entries.length() - initial) + " 笔");
            });
            result.addView(expand);
        }
        if (!ranked && entries.length() < facts.optInt("count")) result.addView(text("这里显示其中 " + entries.length() + " 笔。", 12, MUTED, false));
    }

    /**
     * 显示需要对齐的标签和数值。
     */
    private void chatValue(LinearLayout parent, String label, String value) {
        LinearLayout line = row();
        line.setMinimumHeight(dp(29));
        line.addView(text(label, 14, DARK, false), new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text(value, 14, DARK, true), lp(-2, -2));
        parent.addView(line);
    }

    /**
     * 在消息位置显示回执并按原记录版本撤销。
     */
    private void renderAssistantReceipt(LinearLayout body, JSONObject item) {
        JSONObject entry = item.optJSONObject("entry");
        if (entry == null) return;
        LinearLayout receipt = card();
        boolean undone = item.optBoolean("undone");
        receipt.addView(text(undone ? "已撤销" : "已入账", 16, DARK, true));
        chatValue(receipt, entry.optString("note"), money(entry.optLong("amountMinor")));
        if (!undone) receipt.addView(ghost("撤销入账", () -> {
            try { assistant.undo(entry.optString("id")); render(); }
            catch (Exception error) { error(error); }
        }));
        body.addView(receipt);
    }

    /**
     * 提供原生可编辑草稿并在确认后入账。
     */
    private void renderAssistantDraft(LinearLayout body, JSONObject draft) {
        space(body, 12);
        LinearLayout card = card();
        card.addView(text("确认记账", 18, DARK, true));
        space(card, 8);
        EditText amount = assistantSettingField(card, "金额", "0.00", draft.optString("amount"));
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText date = assistantSettingField(card, "日期", "YYYY-MM-DD", draft.optString("date"));
        List<LedgerDb.Category> categories = db.categories(draft.optString("type"), false);
        List<String> choices = new ArrayList<>();
        choices.add("请选择分类");
        int active = 0;
        for (int i = 0; i < categories.size(); i++) {
            LedgerDb.Category category = categories.get(i);
            choices.add(category.name);
            if (category.id.equals(draft.optString("categoryId"))) active = i + 1;
        }
        Spinner category = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        category.setAdapter(adapter);
        category.setSelection(active);
        card.addView(text("分类", 14, DARK, true));
        card.addView(category, lp(-1, 48));
        EditText note = assistantSettingField(card, "备注", "添加备注", draft.optString("note"));
        card.addView(text("检索线索", 14, DARK, true));
        EditText clues = editField("每行一项，如 商家：四季民福", clueLines(draft.optJSONArray("clues")), 1200, false);
        clues.setMinLines(2);
        card.addView(clues, lp(-1, -2));
        space(card, 8);
        LinearLayout actions = row();
        actions.addView(ghost("取消草稿", () -> { assistant.discardDraft(); hideKeyboard(); assistantFollowBottom = true; render(); }),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("确认入账", true, () -> {
            try {
                int selected = category.getSelectedItemPosition();
                if (selected < 1) throw new IllegalArgumentException("请选择分类");
                assistant.saveDraft(amount.getText().toString().trim(), date.getText().toString().trim(), categories.get(selected - 1).id,
                        note.getText().toString(), clues.getText().toString());
                hideKeyboard();
                assistantFollowBottom = true;
                render();
            } catch (Exception error) { error(error); }
        }), new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(actions);
        body.addView(card);
    }

    /**
     * 请求或移除当前这笔记录的位置。
     */
    private void changeLocation() {
        if (draft == null || locating) return;
        preserveNote();
        if (draft.location != null) {
            draft.location = null;
            buildEntry();
            return;
        }
        if (!LedgerMath.today().equals(draft.businessDate)) {
            success("定位仅可添加到今天的记录");
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationConsentPending = true;
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION);
            return;
        }
        locateDraft();
    }

    /**
     * 在用户授权后获取一次定位。
     */
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != LOCATION || !locationConsentPending) return;
        locationConsentPending = false;
        for (int grant : grants) {
            if (grant == PackageManager.PERMISSION_GRANTED) { locateDraft(); return; }
        }
        success("定位权限未开启，仍可正常记账");
    }

    /**
     * 读取系统定位并验证当前草稿仍可接收结果。
     */
    @SuppressWarnings("deprecation")
    private void locateDraft() {
        LedgerDb.Entry target = draft;
        if (target == null || !LedgerMath.today().equals(target.businessDate)) return;
        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null) return;
        String provider = LocationManager.NETWORK_PROVIDER;
        if (!manager.isProviderEnabled(provider) && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) provider = LocationManager.GPS_PROVIDER;
        if (!manager.isProviderEnabled(provider)) {
            success("暂无可用定位，请打开手机定位");
            return;
        }
        int request = ++locationRequest;
        locating = true;
        buildEntry();
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        LocationListener listener = new LocationListener() {
            /**
             * 将定位结果写入仍有效的草稿。
             */
            @Override public void onLocationChanged(Location location) {
                manager.removeUpdates(this);
                if (!locating || draft != target || request != locationRequest) return;
                locating = false;
                preserveNote();
                if (LedgerMath.today().equals(target.businessDate)) target.location = LedgerAssistant.object(
                        "latitude", location.getLatitude(), "longitude", location.getLongitude(),
                        "accuracy", location.getAccuracy(), "capturedAt", Instant.ofEpochMilli(location.getTime()).toString());
                buildEntry();
            }
            /**
             * 接收系统定位源启用通知。
             */
            @Override public void onProviderEnabled(String name) {}
            /**
             * 接收系统定位源停用通知。
             */
            @Override public void onProviderDisabled(String name) {}
            /**
             * 兼容旧版定位源状态通知。
             */
            @Override public void onStatusChanged(String name, int status, Bundle extras) {}
        };
        try {
            manager.requestSingleUpdate(provider, listener, getMainLooper());
            handler.postDelayed(() -> {
                manager.removeUpdates(listener);
                if (!locating || draft != target || request != locationRequest) return;
                locating = false;
                preserveNote();
                buildEntry();
                success("暂未获取定位，可以稍后重试");
            }, 8000);
        } catch (RuntimeException failure) {
            locating = false;
            buildEntry();
            success("定位不可用，仍可正常记账");
        }
    }

    /**
     * 提供原生底部日历和年月选择。
     *
     * @author chaoshunh
     * @date 2026/11/25
     */
    private final class EntryCalendar {
        private final boolean days;
        private final Dialog dialog;
        private final LocalDate today = LocalDate.now();
        private LocalDate selected;
        private YearMonth shown;
        private LinearLayout sheet;

        /**
         * 根据当前日期创建月或日选择器。
         */
        EntryCalendar(String date, boolean days) {
            this.days = days;
            selected = LocalDate.parse(date);
            shown = YearMonth.from(selected);
            dialog = new Dialog(MainActivity.this);
        }

        /**
         * 打开固定在屏幕底部的选择面板。
         */
        void show() {
            hideKeyboard();
            rebuild();
            showBottomSheet(dialog, sheet);
        }

        /**
         * 刷新日历、月份网格和快捷操作。
         */
        private void rebuild() {
            sheet = column();
            padding(sheet, 20, 16);
            sheet.setBackground(shape(WHITE, 22));
            sheet.addView(text(days ? "选择日期" : "选择月份", 20, DARK, true));
            LinearLayout bar = row();
            bar.addView(ghost("‹", () -> shift(-1)), lp(48, 48));
            bar.addView(ghost(days ? monthLabel(shown.toString()) : shown.getYear() + "年", this::jump),
                    new LinearLayout.LayoutParams(0, dp(48), 1));
            TextView next = ghost("›", () -> shift(1));
            next.setEnabled(days ? shown.isBefore(YearMonth.from(today)) : shown.getYear() < today.getYear());
            next.setAlpha(next.isEnabled() ? 1f : .32f);
            bar.addView(next, lp(48, 48));
            sheet.addView(bar);
            if (days) {
                LinearLayout weekdays = row();
                for (String name : new String[]{"一", "二", "三", "四", "五", "六", "日"}) {
                    TextView label = text(name, 12, MUTED, false);
                    label.setGravity(Gravity.CENTER);
                    weekdays.addView(label, new LinearLayout.LayoutParams(0, dp(32), 1));
                }
                sheet.addView(weekdays);
                int offset = shown.atDay(1).getDayOfWeek().getValue() - 1;
                for (int week = 0; week * 7 < offset + shown.lengthOfMonth(); week++) {
                    LinearLayout line = row();
                    for (int col = 0; col < 7; col++) {
                        int day = week * 7 + col - offset + 1;
                        if (day < 1 || day > shown.lengthOfMonth()) {
                            line.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(0, dp(41), 1));
                        } else {
                            LocalDate date = shown.atDay(day);
                            addDate(line, String.valueOf(day), date, selected.equals(date));
                        }
                    }
                    sheet.addView(line);
                }
                LinearLayout quick = row();
                quick.addView(ghost("今天", () -> select(today)), new LinearLayout.LayoutParams(0, -2, 1));
                quick.addView(ghost("昨天", () -> select(today.minusDays(1))), new LinearLayout.LayoutParams(0, -2, 1));
                sheet.addView(quick);
            } else {
                for (int quarter = 0; quarter < 4; quarter++) {
                    LinearLayout line = row();
                    for (int i = 1; i <= 3; i++) {
                        int m = quarter * 3 + i;
                        LocalDate date = LocalDate.of(shown.getYear(), m, 1);
                        addDate(line, m + "月", date, YearMonth.from(selected).equals(YearMonth.from(date)));
                    }
                    sheet.addView(line);
                }
            }
            space(sheet, 10);
            sheet.addView(button("确定", true, this::confirm));
            dialog.setContentView(sheet);
        }

        /**
         * 渲染日期或月份网格中的单个选项。
         */
        private void addDate(LinearLayout line, String title, LocalDate date, boolean active) {
            TextView cell = ghost(title, () -> select(date));
            cell.setEnabled(!date.isAfter(today));
            cell.setTextColor(active ? ACTION_TEXT : cell.isEnabled() ? DARK : MUTED);
            if (active) cell.setBackground(shape(ACTION, 12));
            cell.setAlpha(cell.isEnabled() ? 1 : .38f);
            cell.setSelected(active);
            line.addView(cell, new LinearLayout.LayoutParams(0, dp(days ? 41 : 52), 1));
        }

        /**
         * 浏览相邻月份或年份。
         */
        private void shift(int amount) {
            YearMonth next = days ? shown.plusMonths(amount) : shown.plusYears(amount);
            if (next.getYear() < 1900) return;
            shown = next;
            rebuild();
        }

        /**
         * 选择候选日期并更新高亮。
         */
        private void select(LocalDate date) {
            if (date.isAfter(today)) return;
            selected = date;
            shown = YearMonth.from(date);
            rebuild();
        }

        /**
         * 通过系统日期选择器跳转到较远年份。
         */
        private void jump() {
            DatePickerDialog picker = new DatePickerDialog(MainActivity.this, (view, year, month, day) ->
                    select(LocalDate.of(year, month + 1, days ? day : 1)), shown.getYear(), shown.getMonthValue() - 1,
                    YearMonth.from(selected).equals(shown) ? selected.getDayOfMonth() : 1);
            picker.getDatePicker().setMinDate(LocalDate.of(1900, 1, 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            picker.getDatePicker().setMaxDate(System.currentTimeMillis());
            picker.show();
        }

        /**
         * 将日期应用到草稿或首页账期。
         */
        private void confirm() {
            dialog.dismiss();
            if (days) {
                if (draft == null) return;
                preserveNote();
                if (!selected.toString().equals(draft.businessDate)) draft.location = null;
                draft.businessDate = selected.toString();
                buildEntry();
            } else {
                month = YearMonth.from(selected).toString();
                if ("home".equals(page.name)) {
                    page.firstRow = 0;
                    page.rowOffset = 0;
                } else page.scroll = 0;
                renderedPage = null;
                render();
            }
        }
    }

    /**
     * 响应分类长按拖动与放置。
     */
    private boolean categoryDrag(View item, android.view.DragEvent event, LedgerDb.Category target) {
        Object state = event.getLocalState();
        if (!(state instanceof LedgerDb.Category)) return false;
        LedgerDb.Category source = (LedgerDb.Category) state;
        if (source.id.equals(target.id) || source.hidden != target.hidden) return false;
        switch (event.getAction()) {
            case android.view.DragEvent.ACTION_DRAG_ENTERED: item.setAlpha(.58f); break;
            case android.view.DragEvent.ACTION_DRAG_EXITED:
            case android.view.DragEvent.ACTION_DRAG_ENDED: item.setAlpha(1); break;
            case android.view.DragEvent.ACTION_DROP: db.moveCategoryTo(source.id, target.id); render(); break;
            default: break;
        }
        return true;
    }

    /**
     * 在设备上将匿名排行标记替换为分类名称。
     */
    private String assistantPlaceholders(String message, JSONObject snapshot) {
        if (snapshot == null) return message;
        JSONArray ranks = snapshot.optJSONArray("ranking");
        for (int i = 1; i <= 5; i++) {
            JSONObject rank = ranks == null ? null : ranks.optJSONObject(i - 1);
            message = message.replace("{{category" + i + "}}", rank == null ? "该分类" : rank.optString("name"));
        }
        return message;
    }

    /**
     * 渲染月账单内的单笔支出排行。
     */
    private void renderReportRanking(LinearLayout body, List<LedgerDb.Entry> all) {
        section(body, "单笔支出排行");
        List<LedgerDb.Entry> top = biggestExpenses(all);
        LinearLayout rankings = card();
        if (top.isEmpty()) rankings.addView(text("暂无支出", 14, MUTED, false));
        int count = showAllReportEntries ? top.size() : Math.min(top.size(), 5);
        for (int i = 0; i < count; i++) {
            LedgerDb.Entry entry = top.get(i);
            LedgerDb.Category cat = db.findCategory(entry.categoryId);
            LinearLayout line = column();
            chatValue(line, (i + 1) + ". " + (cat == null ? "分类" : cat.name) + " · " + entry.businessDate, money(entry.amountMinor));
            line.setOnClickListener(view -> navigate("detail", entry.id));
            rankings.addView(line);
        }
        if (top.size() > 5) rankings.addView(ghost(showAllReportEntries ? "收起" : "查看全部", () -> {
            showAllReportEntries = !showAllReportEntries; render();
        }));
        addCard(body, rankings);
    }

    /**
     * 按金额和时间为报告排序支出。
     */
    private List<LedgerDb.Entry> biggestExpenses(List<LedgerDb.Entry> entries) {
        List<LedgerDb.Entry> result = new ArrayList<>();
        for (LedgerDb.Entry entry : entries) if (LedgerDb.EXPENSE.equals(entry.type)) result.add(entry);
        result.sort(java.util.Comparator.comparingLong((LedgerDb.Entry entry) -> entry.amountMinor).reversed()
                .thenComparing(entry -> entry.businessDate, java.util.Comparator.reverseOrder())
                .thenComparing(entry -> entry.createdAt, java.util.Comparator.reverseOrder()));
        return result;
    }

    /**
     * 格式化用户明确选择包含在报告里的金额。
     */
    private String reportAmount(long amount) {
        return (amount < 0 ? "−" : "") + "¥" + LedgerMath.money(Math.abs(amount));
    }

    /**
     * 写出预览图片并打开系统保存位置或分享面板。
     */
    private void writeReportImage(Bitmap image, boolean save) {
        try {
            java.io.File file = new java.io.File(getCacheDir(), "report.png");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("报告图片生成失败");
            }
            if (save) {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.setType("image/png");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_TITLE, "爱记账报告-" + LedgerMath.today() + ".png");
                startActivityForResult(intent, EXPORT_REPORT);
            } else {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, Uri.parse("content://" + getPackageName() + ".report/report.png"));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "分享收支报告"));
            }
        } catch (Exception error) { error(error); }
    }

    /**
     * 复制生成的报告图片到用户选择的位置。
     */
    private void saveReportTo(Uri uri) {
        try (InputStream input = new java.io.FileInputStream(new java.io.File(getCacheDir(), "report.png"));
             OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("无法写入图片");
            byte[] bytes = new byte[8192];
            for (int count; (count = input.read(bytes)) != -1; ) output.write(bytes, 0, count);
            success("报告图片已保存");
        } catch (Exception error) { error(error); }
    }

    /**
     * 添加接受元金额的输入框。
     */
    private EditText amountField(LinearLayout parent, String label, long amount, boolean hasValue) {
        EditText field = assistantSettingField(parent, label, "0.00", hasValue ? LedgerMath.money(amount).replace(",", "") : "");
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return field;
    }

    /**
     * 在独立验收包的空账本首次启动时导入合成记录。
     */
    private void prepareAcceptanceLedger(TextView loading) throws Exception {
        if (!getPackageName().endsWith(".acceptance")) return;
        synchronized (MainActivity.class) {
            android.content.SharedPreferences preferences = getPreferences(MODE_PRIVATE);
            if (preferences.getBoolean("acceptanceFixtureReady", false)) return;
            if (db.entryCount() == 0) {
                runOnUiThread(() -> loading.setText("首次启动正在载入 10 万笔验收流水\n请稍候…"));
                try (InputStream archive = getAssets().open("acceptance-ledger.zip")) {
                    db.importJson(LedgerBackup.read(archive));
                }
            }
            preferences.edit().putBoolean("acceptanceFixtureReady", true).commit();
        }
    }

    /**
     * 按 Web 的主题色和高度绘制固定页面标题栏。
     */
    private void renderSecondaryBar(LinearLayout shell, String title) {
        LinearLayout bar = row();
        padding(bar, 18, 0);
        bar.setBackgroundColor(TOP);
        bar.addView(text(title, 21, TOP_TEXT, true), new LinearLayout.LayoutParams(0, dp(64), 1));
        if (isMain()) {
            View eye = eyeButton(TOP_TEXT);
            eye.setBackground(shape(0x75ffffff, 15));
            bar.addView(eye, lp(46, 46));
        }
        shell.addView(bar);
        rule(shell, LINE);
    }

    /**
     * 绘制余额和左右两列金额的共用摘要卡片。
     */
    private LinearLayout summaryPanel(String label, String total, String firstLabel, String firstValue, int firstColor,
                                     String secondLabel, String secondValue, int secondColor) {
        LinearLayout summary = card();
        summary.setBackground(shape(WHITE, 18));
        summary.setElevation(dp(1));
        padding(summary, 18, 16);
        summary.addView(text(label, 13, MUTED, false));
        space(summary, 14);
        summary.addView(text(total, 33, DARK, true));
        space(summary, 18);
        LinearLayout columns = row();
        padding(columns, 12, 10);
        columns.setBackground(shape(SOFT, 12));
        String[] labels = {firstLabel, secondLabel}, values = {firstValue, secondValue};
        int[] colors = {firstColor, secondColor};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout column = column();
            if (i == 1) column.setPadding(dp(12), 0, 0, 0);
            column.addView(text(labels[i], 13, MUTED, false));
            space(column, 3);
            TextView amount = text(values[i], 17, colors[i], true);
            amount.setSingleLine(true);
            amount.setAutoSizeTextTypeUniformWithConfiguration(10, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            column.addView(amount, lp(-1, 23));
            columns.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
            if (i == 0) {
                View divider = new View(this);
                divider.setBackgroundColor(LINE);
                columns.addView(divider, lp(1, 37));
            }
        }
        summary.addView(columns);
        return summary;
    }

    /**
     * 对齐记录属性与多行文本值。
     */
    private void detailMetric(LinearLayout parent, String label, String value) {
        if (parent.getChildCount() > 0) rule(parent, LINE);
        LinearLayout item = row();
        item.setGravity(Gravity.TOP);
        padding(item, 0, 11);
        TextView name = text(label, 14, DARK, false);
        item.addView(name, lp(68, -2));
        TextView detail = text(value, 14, DARK, true);
        detail.setGravity(Gravity.END);
        detail.setTextIsSelectable(true);
        item.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(item);
    }

    /**
     * 将存储的 UTC 时间转换为设备本地时间。
     */
    private String localTimestamp(String timestamp) {
        try { return DateTimeFormatter.ofPattern("yyyy/M/d HH:mm:ss", Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.parse(timestamp)); }
        catch (java.time.DateTimeException error) { return timestamp; }
    }

    /**
     * 创建一行账单列使用的格式化数值。
     */
    private String[] billValues(String period, LedgerDb.Totals totals) {
        if (hidden) return new String[]{period, "••••", "••••", "••••"};
        return new String[]{period, LedgerMath.money(totals.income), LedgerMath.money(totals.expense), LedgerMath.money(totals.balance())};
    }

    /**
     * 对齐账单表头和可点击的月度或年度明细行。
     */
    private void addBillRow(LinearLayout table, String[] values, Runnable action) {
        if (table.getChildCount() > 0) rule(table, LINE);
        LinearLayout line = row();
        for (int i = 0; i < values.length; i++) {
            TextView cell = text(values[i], 12, DARK, action == null);
            cell.setGravity((i == 0 ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(2), 0, dp(2), 0);
            cell.setSingleLine(true);
            cell.setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            line.addView(cell, new LinearLayout.LayoutParams(0, dp(43), i == 0 ? .82f : 1f));
        }
        if (action != null) {
            line.setOnClickListener(view -> action.run());
            line.setContentDescription(String.join("，", values));
        }
        table.addView(line, lp(-1, 43));
    }

    /**
     * 应用与 Web 页面层级一致的淡入和位移。
     */
    private void animateIn(View target, int offset, float alpha, int duration) {
        target.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            target.setTranslationY(0);
            target.setAlpha(1f);
            return;
        }
        target.setTranslationY(dp(offset));
        target.setAlpha(alpha);
        target.animate().translationY(0).alpha(1f).setDuration(duration).setInterpolator(new DecelerateInterpolator()).start();
    }

    /**
     * 打开带淡入上移的原生底部弹层。
     */
    private void showBottomSheet(Dialog dialog, View sheet) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.BOTTOM);
        window.setDimAmount(.32f);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        window.setLayout(-1, -2);
        animateIn(sheet, 24, .7f, 260);
    }

    /**
     * 用底部弹层确认删除或放弃记录。
     */
    private void confirmSheet(String title, String message, String cancelLabel, String confirmLabel, Runnable confirm) {
        hideKeyboard();
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = column();
        padding(sheet, 20, 18);
        sheet.setBackground(shape(WHITE, 20));
        sheet.addView(text(title, 20, DARK, true));
        space(sheet, 10);
        sheet.addView(text(message, 14, MUTED, false));
        space(sheet, 16);
        LinearLayout actions = row();
        TextView cancel = button(cancelLabel, false, dialog::dismiss);
        cancel.setBackground(outline(WHITE, 14, LINE));
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(50), 1);
        left.rightMargin = dp(10);
        actions.addView(cancel, left);
        TextView accept = button(confirmLabel, true, () -> { dialog.dismiss(); confirm.run(); });
        accept.setTextColor(WHITE);
        accept.setBackground(shape(RED, 14));
        actions.addView(accept, new LinearLayout.LayoutParams(0, dp(50), 1));
        sheet.addView(actions);
        dialog.setContentView(sheet);
        showBottomSheet(dialog, sheet);
    }

    /**
     * 隐藏保存提示和高亮而不重绘流水列表。
     */
    private void clearSaveFeedback(String marker) {
        if (marker == null) return;
        if (!marker.equals(savedEntryId)) return;
        savedEntryId = null;
        if (saveStatus != null) saveStatus.setVisibility(View.GONE);
        if (highlightedEntry != null) highlightedEntry.setBackgroundColor(Color.TRANSPARENT);
    }

    /**
     * 显示月份摘要及可回收复用的首页流水列表。
     */
    private void renderHomePage(LinearLayout shell) {
        List<LedgerDb.Entry> records = db.statsEntries(month + "-01", monthEnd(month), null);
        LinearLayout heading = column();
        renderHome(heading, records.size());
        if (records.isEmpty()) entryList(heading, records);
        attachRecordList(shell, new MonthScroll(), heading, records);
        renderTabs(shell);
        addAssistantFab();
    }

    /**
     * 将标题、日期和账目接入共用的原生可复用列表。
     */
    private void attachRecordList(LinearLayout shell, ListView list, LinearLayout heading, List<LedgerDb.Entry> records) {
        pageScroll = null;
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setSelector(android.R.color.transparent);
        list.setBackgroundColor(WHITE);
        list.addHeaderView(heading, null, false);
        View footer = new View(this);
        footer.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(24)));
        list.addFooterView(footer, null, false);
        list.setAdapter(new HomeRows(records));
        list.setOnItemClickListener((parent, item, position, id) -> {
            Object data = parent.getItemAtPosition(position);
            if (data instanceof LedgerDb.Entry) navigate("detail", ((LedgerDb.Entry) data).id);
        });
        recordList = list;
        shell.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        if (animateNavigation) {
            animateNavigation = false;
            animateIn(list, 12, .5f, 260);
        }
        renderedPage = page;
        int first = page.firstRow, offset = page.rowOffset;
        if (first != 0 || offset != 0) list.post(() -> {
            if (recordList != list) return;
            if (first == Integer.MAX_VALUE) list.setSelection(list.getCount() - 1);
            else list.setSelectionFromTop(Math.min(first, list.getCount() - 1), offset);
        });
    }

    /**
     * 显示与 Web 相同密度的单行工具和账户入口。
     */
    private void compactMenu(LinearLayout parent, String label, String caption, String glyph, Runnable action) {
        if (parent.getChildCount() > 0) rule(parent, 0xffedf1ee);
        LinearLayout line = row();
        padding(line, 16, 8);
        if (!glyph.isEmpty()) {
            TextView icon = text(glyph, 15, GREEN, false);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(shape(SOFT, 13));
            line.addView(icon, lp(42, 42));
        }
        LinearLayout labels = column();
        labels.setPadding(glyph.isEmpty() ? 0 : dp(10), 0, dp(4), 0);
        labels.addView(text(label, 15, DARK, !caption.isEmpty()));
        if (!caption.isEmpty()) labels.addView(text(caption, 12, MUTED, false));
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        line.addView(text("›", 17, MUTED, false));
        line.setOnClickListener(view -> action.run());
        parent.addView(line, lp(-1, glyph.isEmpty() ? 51 : 66));
    }

    /**
     * 在统一的底部弹层展示使用说明或版本信息。
     */
    private void infoSheet(String title, String message) {
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = column();
        padding(sheet, 20, 18);
        sheet.setBackground(shape(WHITE, 20));
        sheet.addView(text(title, 20, DARK, true));
        space(sheet, 12);
        TextView content = text(message, 14, DARK, false);
        content.setLineSpacing(dp(4), 1f);
        sheet.addView(content);
        space(sheet, 14);
        sheet.addView(button("关闭", true, dialog::dismiss), lp(-1, 50));
        dialog.setContentView(sheet);
        showBottomSheet(dialog, sheet);
    }

    /**
     * 将账单支出圆环和前五类图例并排显示。
     */
    private LinearLayout reportDonut(List<Map.Entry<String, Long>> ranking, Map<String, LedgerDb.Category> categories, long total) {
        LinearLayout container = card();
        LinearLayout content = row();
        content.setGravity(Gravity.TOP);
        int[] palette = {0xff246b54, 0xff5c8aa1, 0xffa9875a, 0xff8775a5, 0xff658760, 0xff899690};
        List<Long> amounts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        long remaining = total;
        for (int i = 0; i < Math.min(5, ranking.size()); i++) {
            Map.Entry<String, Long> item = ranking.get(i);
            LedgerDb.Category category = categories.get(item.getKey());
            labels.add(category == null ? "未知分类" : category.name);
            amounts.add(item.getValue());
            remaining -= item.getValue();
        }
        if (remaining > 0) { labels.add("其它分类"); amounts.add(remaining); }
        View ring = new View(this) {
            /**
             * 绘制与图例对应的类别比例。
             */
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
                float middle = getWidth() / 2f, radius = middle - dp(1);
                pen.setStyle(Paint.Style.FILL);
                float angle = -90;
                for (int i = 0; i < amounts.size(); i++) {
                    pen.setColor(hidden ? LINE : palette[i]);
                    float sweep = amounts.get(i) * 360f / total;
                    canvas.drawArc(middle - radius, middle - radius, middle + radius, middle + radius, angle, sweep, true, pen);
                    angle += sweep;
                }
                pen.setColor(WHITE);
                canvas.drawCircle(middle, middle, radius * .64f, pen);
            }
        };
        ring.setContentDescription("分类支出组成");
        content.addView(ring, lp(105, 105));
        LinearLayout legend = column();
        legend.setPadding(dp(10), 0, 0, 0);
        for (int i = 0; i < labels.size(); i++) {
            LinearLayout item = row();
            View dot = new View(this);
            dot.setBackground(shape(hidden ? LINE : palette[i], 8));
            item.addView(dot, lp(8, 8));
            String ratio = hidden ? "" : String.format(Locale.ROOT, " %.1f%%", amounts.get(i) * 100d / total);
            TextView label = text(labels.get(i) + ratio, 12, DARK, false);
            label.setPadding(dp(7), 0, 0, 0);
            item.addView(label, lp(-1, 20));
            legend.addView(item);
        }
        content.addView(legend, new LinearLayout.LayoutParams(0, -2, 1));
        container.addView(content);
        return container;
    }

    /**
     * 将月份流水拆为日期行和可复用的普通账目行。
     *
     * @author chaoshunh
     * @date 2026/11/26
     */
    private final class HomeRows extends BaseAdapter {
        private final List<Object> items = new ArrayList<>();
        private final Map<String, LedgerDb.Category> categories = new HashMap<>();
        private final Map<String, LedgerDb.Totals> totals = new HashMap<>();

        /**
         * 生成按日期的轻量列表条目和每日合计。
         */
        HomeRows(List<LedgerDb.Entry> records) {
            for (LedgerDb.Category category : db.categories(null, true)) categories.put(category.id, category);
            String date = "";
            LedgerDb.Totals day = new LedgerDb.Totals();
            for (LedgerDb.Entry record : records) {
                if (!date.equals(record.businessDate)) {
                    date = record.businessDate;
                    day = new LedgerDb.Totals();
                    totals.put(date, day);
                    items.add(date);
                }
                if (LedgerDb.INCOME.equals(record.type)) day.income += record.amountMinor;
                else day.expense += record.amountMinor;
                items.add(record);
            }
        }

        /**
         * 返回账本列表的总行数。
         */
        @Override public int getCount() { return items.size(); }

        /**
         * 返回指定日期或流水。
         */
        @Override public Object getItem(int position) { return items.get(position); }

        /**
         * 返回列表项的位置标识。
         */
        @Override public long getItemId(int position) { return position; }

        /**
         * 区分日期和流水的回收池。
         */
        @Override public int getViewTypeCount() { return 2; }

        /**
         * 标识当前行的回收类型。
         */
        @Override public int getItemViewType(int position) { return isEnabled(position) ? 1 : 0; }

        /**
         * 告知列表日期行无需点击。
         */
        @Override public boolean areAllItemsEnabled() { return false; }

        /**
         * 仅可进入实际流水的详情。
         */
        @Override public boolean isEnabled(int position) { return getItem(position) instanceof LedgerDb.Entry; }

        /**
         * 复用窗口内的视图并绑定正文数据。
         */
        @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
            Object item = getItem(position);
            if (item instanceof String) return dateRow((String) item, position, (LinearLayout) recycled);
            return recordRow((LedgerDb.Entry) item, (LinearLayout) recycled);
        }

        /**
         * 绑定日期分组和当日收支。
         */
        private View dateRow(String date, int position, LinearLayout container) {
            if (container == null) {
                container = column();
                LinearLayout header = row();
                padding(header, 7, 7);
                header.addView(text("", 13, DARK, true), new LinearLayout.LayoutParams(0, -2, 1));
                header.addView(text("", 11, MUTED, false));
                container.addView(header);
                rule(container, LINE);
            }
            container.setPadding(dp(20), position == 0 ? 0 : dp(12), dp(20), 0);
            LinearLayout line = (LinearLayout) container.getChildAt(0);
            ((TextView) line.getChildAt(0)).setText(dayLabel(date));
            LedgerDb.Totals day = totals.get(date);
            String income = day.income > 0 ? "收入 " + money(day.income) : "";
            String expense = day.expense > 0 ? "支出 " + money(day.expense) : "";
            ((TextView) line.getChildAt(1)).setText(income + (!income.isEmpty() && !expense.isEmpty() ? " · " : "") + expense);
            return container;
        }

        /**
         * 在复用行上更新分类、备注、金额及保存高亮。
         */
        private View recordRow(LedgerDb.Entry entry, LinearLayout container) {
            if (container == null) {
                container = column();
                padding(container, 20, 0);
                LinearLayout line = row();
                padding(line, 8, 6);
                line.setMinimumHeight(dp(58));
                line.addView(new FrameLayout(MainActivity.this), lp(32, 32));
                LinearLayout labels = column();
                labels.setPadding(dp(10), 0, dp(8), 0);
                labels.addView(text("", 15, DARK, true));
                TextView sublabel = text("", 12, MUTED, false);
                sublabel.setPadding(0, dp(3), 0, 0);
                labels.addView(sublabel);
                line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                line.addView(text("", 15, DARK, true));
                container.addView(line);
                rule(container, 0xfff7f8fa);
            }
            LinearLayout line = (LinearLayout) container.getChildAt(0);
            if (entry.id.equals(savedEntryId)) {
                line.setBackground(shape(SOFT, 8));
                highlightedEntry = line;
            } else {
                if (highlightedEntry == line) highlightedEntry = null;
                line.setBackgroundColor(Color.TRANSPARENT);
            }
            LedgerDb.Category category = categories.get(entry.categoryId);
            FrameLayout icon = (FrameLayout) line.getChildAt(0);
            if (!entry.categoryId.equals(icon.getTag())) {
                icon.removeAllViews();
                icon.addView(categoryBadge(category, true, false), new FrameLayout.LayoutParams(-1, -1));
                icon.setTag(entry.categoryId);
            }
            String name = category == null ? "未知分类" : category.name;
            LinearLayout labels = (LinearLayout) line.getChildAt(1);
            boolean hasNote = !entry.note.isEmpty();
            ((TextView) labels.getChildAt(0)).setText(hasNote ? entry.note : name);
            TextView sublabel = (TextView) labels.getChildAt(1);
            sublabel.setText(name);
            sublabel.setVisibility(hasNote ? View.VISIBLE : View.GONE);
            TextView amount = (TextView) line.getChildAt(2);
            boolean income = LedgerDb.INCOME.equals(entry.type);
            amount.setText((income ? "+" : "−") + (hidden ? "••••" : LedgerMath.money(entry.amountMinor)));
            amount.setTextColor(income ? GREEN : DARK);
            return container;
        }
    }
}
