package com.crn.bossbot;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.net.Uri;
import android.provider.Settings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.util.*;
import java.util.regex.*;

public class MainActivity extends Activity {

    // ── colour tokens ──────────────────────────────────────────────────────────
    private static final int C_BG      = Color.parseColor("#03090f");
    private static final int C_SURFACE = Color.parseColor("#07101e");
    private static final int C_CARD    = Color.parseColor("#0a1528");
    private static final int C_BORDER  = Color.parseColor("#111e33");
    private static final int C_BORDER2 = Color.parseColor("#1a2f4e");
    private static final int C_ACCENT  = Color.parseColor("#00e5c8");
    private static final int C_BLUE    = Color.parseColor("#3b9eff");
    private static final int C_DANGER  = Color.parseColor("#ff3d5a");
    private static final int C_AMBER   = Color.parseColor("#ffb020");
    private static final int C_OK      = Color.parseColor("#1ef086");
    private static final int C_MUTED   = Color.parseColor("#3d5870");
    private static final int C_TEXT    = Color.parseColor("#cce4f8");
    private static final int C_TEXT2   = Color.parseColor("#6b8faa");

    // ── state ──────────────────────────────────────────────────────────────────
    private FrameLayout root;
    private SharedPreferences sp;
    private WebView loginWeb;
    private String currentScreen = "main";
    private int activeTab = 0;           // 0 = ALL, 1..N = wave tabs, last = LOGS

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if ("main".equals(currentScreen)) refreshMainUi();
        }
    };

    // ── tab data mirrors categories in BotForegroundService ────────────────────
    // { prefKey, displayLabel, url-suffix (informational only) }
    private static final String[][] WAVE_TABS = {
        { "grakthar", "Grakthar",  "gate=3&wave=8"  },
        { "olympus",  "Oly W9",    "gate=5&wave=9"  },
        { "olympus2", "Oly W10",   "gate=5&wave=10" },
        { "olympus3", "Oly W11",   "gate=5&wave=11" },
    };

    // ── live tab-panel views so we can refresh without rebuilding ───────────────
    private LinearLayout tabAllPanel;
    private final LinearLayout[] wavePanels = new LinearLayout[WAVE_TABS.length];
    private LinearLayout tabLogsPanel;
    private HorizontalScrollView tabBarScroll;
    private LinearLayout tabBarInner;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("crn_java_bot", MODE_PRIVATE);
        if (!sp.contains("global_enabled")) {
            sp.edit()
              .putBoolean("global_enabled", false)
              .putBoolean("enable_grakthar", true)
              .putBoolean("enable_olympus",  true)
              .putBoolean("enable_olympus2", true)
              .putBoolean("enable_olympus3", true)
              .putBoolean("smart_delay", true)
              .putBoolean("alerts", true)
              .putBoolean("auto_potion", true)
              .putString("skill_id", "-4")
              .putString("scan_interval", "60")
              .putString("cap_default", "2m")
              .apply();
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 99);
        }
        requestIgnoreBatteryOptimizationsOnce();
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(C_BG);
            getWindow().setNavigationBarColor(C_BG);
        }
        root = new FrameLayout(this);
        setContentView(root);
        showMain();
        requestInitialScanOnce();
    }

    @Override protected void onResume()  { super.onResume();  registerReceiver(receiver, new IntentFilter(BotForegroundService.ACTION_STATUS), RECEIVER_NOT_EXPORTED); }
    @Override protected void onPause()   { try { unregisterReceiver(receiver); } catch (Exception ignored) {} super.onPause(); }
    @Override public void onBackPressed(){ if (!"main".equals(currentScreen)) showMain(); else super.onBackPressed(); }

    // ═══════════════════════════════════════════════════════════════════════════
    //  MAIN SCREEN  (header + stat strip + tab bar + panels + bottom nav)
    // ═══════════════════════════════════════════════════════════════════════════

    private void showMain() {
        currentScreen = "main";
        destroyLogin();
        root.removeAllViews();
        root.setBackgroundColor(C_BG);

        // outer vertical box fills the screen
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        buildHeader(shell);
        buildStatStrip(shell);
        buildTabBar(shell);
        buildPanelHost(shell);   // panels + bottom nav sit inside a FrameLayout
    }

    /** Lightweight refresh called by the broadcast receiver — rebuilds content only */
    private void refreshMainUi() {
        showMain();     // cheapest correct approach; all panels recreated in <1ms
    }

    // ── Header ─────────────────────────────────────────────────────────────────
    private void buildHeader(LinearLayout shell) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackgroundColor(C_SURFACE);
        h.setPadding(dp(14), statusBarHeight() + dp(10), dp(14), dp(10));

        // row 1: logo + title + run badge
        LinearLayout r1 = row(Gravity.CENTER_VERTICAL);
        TextView title = txt("CRN Boss Bot", 18, true, Color.WHITE);
        if (Build.VERSION.SDK_INT >= 21) title.setLetterSpacing(0.04f);
        r1.addView(title, lp0(1));

        boolean running = sp.getBoolean("global_enabled", false);
        TextView badge = chip(running ? "● RUNNING" : "■ STOPPED",
                              running ? Color.argb(40, 0, 229, 200) : Color.argb(40, 255, 61, 90),
                              running ? C_ACCENT : C_DANGER,
                              running ? Color.argb(160, 0, 229, 200) : Color.argb(160, 255, 61, 90));
        r1.addView(badge, lpWH(dp(96), dp(26)));
        h.addView(r1);

        // row 2: connected status + last scan
        LinearLayout r2 = row(Gravity.CENTER_VERTICAL);
        r2.setPadding(0, dp(6), 0, 0);
        boolean conn = isConnected();
        TextView connTxt = txt(conn ? "● Connected" : "● Disconnected", 12, true, conn ? C_OK : C_DANGER);
        r2.addView(connTxt, lp0(1));
        TextView scanTxt = txt("Last scan: " + lastScanLabel(), 11, false, C_MUTED);
        scanTxt.setGravity(Gravity.END);
        r2.addView(scanTxt, lp0(1));
        h.addView(r2);

        // status message
        String msg = sp.getString("ui_message", "");
        if (msg.length() > 0) {
            TextView m = txt("› " + msg, 11, false, C_MUTED);
            m.setPadding(0, dp(4), 0, 0);
            h.addView(m);
        }

        // 1px bottom divider
        h.addView(divider());
        shell.addView(h, lpW(-1));
    }

    // ── Stat strip ─────────────────────────────────────────────────────────────
    private void buildStatStrip(LinearLayout shell) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBackgroundColor(C_SURFACE);

        String[][] stats = {
            {"⚡","STA", latestStamina()},
            {"🧪","LSP", potionCount("used_lsp","lsp_limit")},
            {"🔥","FSP", potionCount("used_fsp","fsp_limit")},
            {"💙","HP",  potionCount("used_hp","hp_limit")},
        };
        for (int i = 0; i < stats.length; i++) {
            View cell = buildStatCell(stats[i][0], stats[i][1], stats[i][2]);
            LinearLayout.LayoutParams lp = lp0(1);
            if (i < stats.length - 1) {
                // right border
                cell.setBackground(rightBorder(C_BORDER));
            }
            strip.addView(cell, lp);
        }

        strip.addView(divider());  // bottom line
        View wrap = new LinearLayout(this);
        ((LinearLayout)wrap).setOrientation(LinearLayout.VERTICAL);
        ((LinearLayout)wrap).addView(strip, lpW(-1));
        ((LinearLayout)wrap).addView(divider());
        shell.addView(wrap, lpW(-1));
    }

    private View buildStatCell(String icon, String label, String value) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(4), dp(8), dp(4), dp(8));
        c.addView(txt(icon, 14, false, Color.WHITE));
        c.addView(txt(label, 8, true, C_MUTED));
        TextView v = txt(value, 11, true, C_TEXT);
        c.addView(v);
        return c;
    }

    // ── Tab bar ────────────────────────────────────────────────────────────────
    private void buildTabBar(LinearLayout shell) {
        tabBarScroll = new HorizontalScrollView(this);
        tabBarScroll.setHorizontalScrollBarEnabled(false);
        tabBarScroll.setBackgroundColor(C_SURFACE);

        tabBarInner = new LinearLayout(this);
        tabBarInner.setOrientation(LinearLayout.HORIZONTAL);
        tabBarInner.setPadding(dp(6), 0, dp(6), 0);

        // Tab 0 = ALL
        tabBarInner.addView(buildTab(0, "🗺️", "All", aliveCountAll()));
        // Wave tabs 1..N
        for (int i = 0; i < WAVE_TABS.length; i++) {
            String[] wt = WAVE_TABS[i];
            tabBarInner.addView(buildTab(i + 1, waveIcon(wt[0]), wt[1], aliveCountForKey(wt[0])));
        }
        // Logs tab = last
        int logsIdx = WAVE_TABS.length + 1;
        tabBarInner.addView(buildTab(logsIdx, "📋", "Logs", logCount() + ""));

        tabBarScroll.addView(tabBarInner, new LinearLayout.LayoutParams(-2, -1));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackgroundColor(C_SURFACE);
        wrap.addView(tabBarScroll, new LinearLayout.LayoutParams(-1, dp(58)));
        wrap.addView(divider());
        shell.addView(wrap, lpW(-1));

        highlightTab(activeTab);
    }

    private View buildTab(final int idx, String icon, String name, String pip) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(12), dp(8), dp(12), dp(6));
        tab.setMinimumWidth(dp(70));

        tab.addView(txt(icon, 16, false, Color.WHITE));

        TextView nameTv = txt(name, 8, true, C_TEXT2);
        if (Build.VERSION.SDK_INT >= 21) nameTv.setLetterSpacing(0.06f);
        tab.addView(nameTv);

        boolean hasAlive = hasAlive(pip);
        int pipBg  = hasAlive ? Color.argb(40, 0, 229, 200) : Color.argb(20, 30, 47, 78);
        int pipTxt = hasAlive ? C_ACCENT : C_MUTED;
        TextView pipTv = chip(pip, pipBg, pipTxt, hasAlive ? Color.argb(120, 0, 229, 200) : C_BORDER2);
        pipTv.setTextSize(8);
        pipTv.setPadding(dp(5), dp(1), dp(5), dp(1));
        tab.addView(pipTv);

        tab.setOnClickListener(v -> switchTab(idx));
        return tab;
    }

    private void highlightTab(int idx) {
        if (tabBarInner == null) return;
        for (int i = 0; i < tabBarInner.getChildCount(); i++) {
            View child = tabBarInner.getChildAt(i);
            if (i == idx) {
                child.setBackgroundColor(Color.argb(30, 0, 229, 200));
                // bottom accent line via tag-based bottom border
                child.setTag("active");
            } else {
                child.setBackgroundColor(Color.TRANSPARENT);
                child.setTag(null);
            }
        }
    }

    private void switchTab(int idx) {
        activeTab = idx;
        highlightTab(idx);
        int total = WAVE_TABS.length + 2; // ALL + waves + LOGS
        for (int i = 0; i < total; i++) {
            View panel = getPanelAt(i);
            if (panel != null) panel.setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        // scroll tab into view
        tabBarScroll.post(() -> {
            View tab = tabBarInner.getChildAt(idx);
            if (tab != null) tabBarScroll.smoothScrollTo(tab.getLeft(), 0);
        });
    }

    private View getPanelAt(int i) {
        int logsIdx = WAVE_TABS.length + 1;
        if (i == 0) return tabAllPanel;
        if (i == logsIdx) return tabLogsPanel;
        int waveIdx = i - 1;
        if (waveIdx >= 0 && waveIdx < wavePanels.length) return wavePanels[waveIdx];
        return null;
    }

    // ── Panel host ─────────────────────────────────────────────────────────────
    private void buildPanelHost(LinearLayout shell) {
        // panels fill remaining space; bottom nav floats over them
        FrameLayout host = new FrameLayout(this);

        // scrollable content area
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(8), dp(10), dp(96)); // bottom padding clears nav

        // build ALL panel
        tabAllPanel = buildAllPanel();
        content.addView(tabAllPanel);

        // build wave panels
        List<String[]> allBossLines = parseBossLines(sp.getString("last_bosses", ""));
        for (int i = 0; i < WAVE_TABS.length; i++) {
            wavePanels[i] = buildWavePanel(WAVE_TABS[i], allBossLines);
            content.addView(wavePanels[i]);
        }

        // build logs panel
        tabLogsPanel = buildLogsPanel();
        content.addView(tabLogsPanel);

        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        host.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // bottom nav floats
        host.addView(buildBottomNav(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        shell.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));

        // apply initial visibility
        switchTab(activeTab);
    }

    // ── ALL overview panel ─────────────────────────────────────────────────────
    private LinearLayout buildAllPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        List<String[]> lines = parseBossLines(sp.getString("last_bosses", ""));

        if (lines.isEmpty()) {
            panel.addView(emptyCard("No scan yet — start the bot after login."));
            return panel;
        }

        // group by category label
        LinkedHashMap<String, List<String[]>> byCategory = new LinkedHashMap<>();
        for (String[] p : lines) {
            byCategory.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p);
        }

        for (Map.Entry<String, List<String[]>> entry : byCategory.entrySet()) {
            String catLabel = entry.getKey();
            List<String[]> bosses = entry.getValue();
            long alive = 0;
            for (String[] b : bosses) if ("ALIVE".equalsIgnoreCase(b[2])) alive++;

            // section header
            panel.addView(buildSectionHeader(waveIconForLabel(catLabel) + " " + catLabel.toUpperCase(Locale.US), alive + " alive", alive > 0));

            // compact rows
            for (String[] p : bosses) {
                panel.addView(buildOverviewRow(p));
            }
        }
        return panel;
    }

    private View buildSectionHeader(String label, String pip, boolean hasAlive) {
        LinearLayout h = row(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(4), dp(10), dp(4), dp(4));

        TextView lbl = txt(label, 10, true, C_TEXT2);
        if (Build.VERSION.SDK_INT >= 21) lbl.setLetterSpacing(0.08f);
        h.addView(lbl);

        View line = new View(this);
        line.setBackgroundColor(C_BORDER);
        LinearLayout.LayoutParams llp = lp0(1);
        llp.setMargins(dp(8), 0, dp(8), 0);
        llp.gravity = Gravity.CENTER_VERTICAL;
        llp.height = dp(1);
        h.addView(line, llp);

        int pipBg  = hasAlive ? Color.argb(40, 0, 229, 200) : Color.TRANSPARENT;
        int pipTxt = hasAlive ? C_ACCENT : C_MUTED;
        int pipStr = hasAlive ? Color.argb(120, 0, 229, 200) : C_BORDER2;
        h.addView(chip(pip, pipBg, pipTxt, pipStr));
        return h;
    }

    private View buildOverviewRow(String[] p) {
        // p: [category, name, status, damage, cap, enabled, image?]
        String name   = p.length > 1 ? p[1] : "—";
        String status = p.length > 2 ? p[2] : "";
        String damage = p.length > 3 ? p[3] : "0";
        String cap    = p.length > 4 ? p[4] : "0";

        boolean alive = "ALIVE".equalsIgnoreCase(status);

        LinearLayout row = row(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        LinearLayout.LayoutParams rlp = lpW(-1);
        rlp.setMargins(0, dp(3), 0, 0);
        row.setLayoutParams(rlp);

        // left accent bar
        GradientDrawable bg = roundRect(C_CARD, dp(9), C_BORDER2);
        bg.setStroke(dp(3), alive ? C_ACCENT : C_BORDER);
        row.setBackground(bg);

        // name
        TextView nameTv = txt(name, 12, true, C_TEXT);
        nameTv.setSingleLine(false);
        nameTv.setMaxLines(2);
        row.addView(nameTv, lp0(1));

        // damage + mini bar
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setPadding(dp(10), 0, 0, 0);

        long dmgLong = parseLong(damage);
        long capLong = parseLong(cap);
        float pct = capLong > 0 ? Math.min(1f, (float)dmgLong / capLong) : 0f;

        TextView dmgTv = txt(fmt(damage), 11, true, alive ? C_TEXT : C_MUTED);
        right.addView(dmgTv);

        right.addView(buildProgressBar(pct, dp(60), dp(3)));

        row.addView(right, lpWH(-2, -2));
        return row;
    }

    // ── Per-wave panel ─────────────────────────────────────────────────────────
    private LinearLayout buildWavePanel(String[] waveDef, List<String[]> allLines) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        String key   = waveDef[0];
        String label = waveDef[1];

        List<String[]> bosses = new ArrayList<>();
        for (String[] p : allLines) {
            if (p.length > 0 && norm(p[0]).equals(norm(label))) bosses.add(p);
            else if (p.length > 0 && norm(p[0]).startsWith(norm(key)))  bosses.add(p);
        }

        // sub-header: gate/wave info + toggle-all button
        LinearLayout subHead = row(Gravity.CENTER_VERTICAL);
        subHead.setPadding(dp(2), dp(6), dp(2), dp(8));

        TextView info = txt(key + " • " + bosses.size() + " boss" + (bosses.size() != 1 ? "es" : ""), 11, false, C_MUTED);
        subHead.addView(info, lp0(1));

        TextView toggleAll = txt("Toggle All", 10, true, C_TEXT2);
        toggleAll.setPadding(dp(10), dp(4), dp(10), dp(4));
        toggleAll.setBackground(roundRect(Color.TRANSPARENT, dp(6), C_BORDER2));
        toggleAll.setOnClickListener(v -> toggleAllBosses(key, bosses));
        subHead.addView(toggleAll, lpWH(-2, -2));
        panel.addView(subHead);

        if (bosses.isEmpty()) {
            panel.addView(emptyCard("No bosses found for " + label + " yet."));
            return panel;
        }

        // 2-column grid
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        for (String[] p : bosses) {
            grid.addView(buildBossCard(p), bossCardLp());
        }
        panel.addView(grid, lpW(-1));
        return panel;
    }

    private void toggleAllBosses(String key, List<String[]> bosses) {
        // if all enabled → disable all; otherwise enable all
        boolean anyOff = false;
        for (String[] p : bosses) {
            String bKey = norm(p[0]) + ":" + norm(p[1]);
            if (!sp.getBoolean("boss_enabled_" + bKey, true)) { anyOff = true; break; }
        }
        boolean target = anyOff;
        SharedPreferences.Editor ed = sp.edit();
        for (String[] p : bosses) {
            String bKey = norm(p[0]) + ":" + bossRootKey(p[1]);
            ed.putBoolean("boss_enabled_" + bKey, target);
        }
        ed.apply();
        showMain();
    }

    // ── Boss card (full-detail, used in wave panels) ───────────────────────────
    private View buildBossCard(String[] p) {
        String catLabel = p.length > 0 ? p[0] : "";
        String name     = p.length > 1 ? p[1] : "—";
        String status   = p.length > 2 ? p[2] : "";
        String damage   = p.length > 3 ? p[3] : "0";
        String cap      = p.length > 4 ? p[4] : "0";
        String image    = p.length > 6 ? p[6] : "";

        final String catKey  = norm(catLabel);
        final String rootKey = bossRootKey(name);                    // ← phase-safe key
        final String prefKey = "boss_enabled_" + catKey + ":" + rootKey;
        final boolean enabled = sp.getBoolean(prefKey, true);
        boolean alive = "ALIVE".equalsIgnoreCase(status);
        long dmgLong  = parseLong(damage);
        long capLong  = parseLong(cap);
        float pct     = capLong > 0 ? Math.min(1f, (float)dmgLong / capLong) : 0f;
        boolean capped = capLong > 0 && dmgLong >= capLong;

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(10), dp(10), dp(9));
        c.setMinimumHeight(dp(148));

        GradientDrawable cardBg = roundRect(
            alive ? Color.parseColor("#0a1e1c") : C_CARD,
            dp(12),
            alive ? Color.argb(180, 0, 229, 200) : C_BORDER2
        );
        if (!enabled) cardBg.setAlpha(120);
        c.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= 21) c.setElevation(dp(1));

        // row 1: full boss name + toggle
        LinearLayout r1 = row(Gravity.TOP);
        TextView nameTv = txt(name, 12, true, Color.WHITE);
        nameTv.setSingleLine(false);
        nameTv.setMaxLines(3);
        r1.addView(nameTv, lp0(1));

        Switch sw = new Switch(this);
        sw.setChecked(enabled);
        sw.setScaleX(0.72f);
        sw.setScaleY(0.72f);
        sw.setPadding(0, 0, 0, 0);
        sw.setOnCheckedChangeListener((btn, val) -> {
            sp.edit().putBoolean(prefKey, val).apply();
            cardBg.setAlpha(val ? 255 : 120);
        });
        r1.addView(sw, lpWH(dp(46), dp(28)));
        c.addView(r1);

        // row 2: status pill + damage value
        LinearLayout r2 = row(Gravity.CENTER_VERTICAL);
        r2.setPadding(0, dp(7), 0, dp(7));

        String displayStatus = ("WAITING".equalsIgnoreCase(status) || status.isEmpty()) ? "WAIT" : status.toUpperCase(Locale.US);
        int pillBg  = alive ? Color.argb(50, 0, 229, 200) : Color.argb(40, 60, 80, 110);
        int pillTxt = alive ? C_ACCENT : C_MUTED;
        int pillStr = alive ? Color.argb(150, 0, 229, 200) : C_BORDER2;
        r2.addView(chip(displayStatus, pillBg, pillTxt, pillStr));

        TextView dmgTv = txt("  " + fmt(damage), 13, true, alive ? Color.WHITE : C_MUTED);
        dmgTv.setSingleLine(true);
        r2.addView(dmgTv);
        c.addView(r2);

        // row 3: cap label + cap chip (tappable to edit)
        LinearLayout r3 = row(Gravity.CENTER_VERTICAL);
        TextView capLbl = txt("Cap", 9, true, C_MUTED);
        if (Build.VERSION.SDK_INT >= 21) capLbl.setLetterSpacing(0.04f);
        r3.addView(capLbl, lp0(1));

        final String capKey = "cap_" + catKey + "_" + rootKey;
        final String capVal = sp.getString(capKey, fmt(cap));
        TextView capChip = chip(capVal,
            Color.argb(40, 59, 158, 255),
            C_BLUE,
            Color.argb(100, 59, 158, 255));
        capChip.setPadding(dp(10), dp(3), dp(10), dp(3));
        capChip.setOnClickListener(v -> editBossCap(catLabel, name, capVal));
        r3.addView(capChip, lpWH(-2, -2));
        c.addView(r3);

        // progress bar + fraction
        c.addView(buildProgressBar(pct, -1, dp(3)));

        TextView frac = txt(fmt(damage) + " / " + fmt(cap) + (capped ? " ✓" : ""), 9, false, C_MUTED);
        frac.setPadding(0, dp(3), 0, 0);
        c.addView(frac);

        return c;
    }

    // ── Logs panel ─────────────────────────────────────────────────────────────
    private LinearLayout buildLogsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        // toolbar
        LinearLayout head = row(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(6), dp(4), dp(10));

        TextView title = txt("Activity Log", 12, true, C_TEXT2);
        if (Build.VERSION.SDK_INT >= 21) title.setLetterSpacing(0.06f);
        head.addView(title, lp0(1));

        head.addView(smallBtn("Copy", C_BLUE, v -> copyLogs()));
        head.addView(smallBtn("Clear", C_DANGER, v -> clearLogs()));
        panel.addView(head);

        // log rows
        String raw = sp.getString("logs", "");
        if (raw.isEmpty()) {
            panel.addView(emptyCard("No logs yet."));
        } else {
            String[] lines = raw.split("\n");
            int start = Math.max(0, lines.length - 80);
            for (int i = start; i < lines.length; i++) {
                panel.addView(buildLogRow(levelOf(lines[i]), msgOf(lines[i])));
            }
        }
        return panel;
    }

    private View buildLogRow(String level, String msg) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams rlp = lpW(-1);
        rlp.setMargins(0, dp(3), 0, 0);
        row.setLayoutParams(rlp);
        row.setBackground(roundRect(Color.parseColor("#050e1d"), dp(8), C_BORDER));

        boolean isErr = "ERROR".equalsIgnoreCase(level) || "ERR".equalsIgnoreCase(level);
        TextView tag = chip(isErr ? "ERR" : "INFO",
            isErr ? Color.argb(40, 255, 61, 90) : Color.argb(30, 59, 158, 255),
            isErr ? C_DANGER : C_BLUE,
            isErr ? Color.argb(100, 255, 61, 90) : Color.argb(80, 59, 158, 255));
        tag.setTextSize(8);
        tag.setPadding(dp(5), dp(2), dp(5), dp(2));
        LinearLayout.LayoutParams tlp = lpWH(dp(36), -2);
        tlp.setMargins(0, 0, dp(8), 0);
        tlp.gravity = Gravity.TOP;
        row.addView(tag, tlp);

        TextView msgTv = txt(msg, 11, false, C_TEXT);
        msgTv.setMaxLines(3);
        row.addView(msgTv, lp0(1));
        return row;
    }

    // ── Bottom nav ─────────────────────────────────────────────────────────────
    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);

        // gradient fade
        GradientDrawable fade = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{ Color.TRANSPARENT, C_BG, C_BG }
        );
        nav.setBackground(fade);
        nav.setPadding(dp(12), dp(8), dp(12), dp(20));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        boolean running = sp.getBoolean("global_enabled", false);
        boolean conn    = isConnected();

        View logoutBtn = navBtn(
            conn ? "Logout" : "Login",
            Color.parseColor("#0a1422"), C_MUTED, C_BORDER2,
            v -> { if (conn) logout(); else showLogin(); }
        );
        View configBtn = navBtn(
            "⚙ Config",
            Color.parseColor("#0a2828"), C_ACCENT, Color.argb(120, 0, 229, 200),
            v -> showConfig()
        );
        View actionBtn = navBtn(
            running ? "■ Stop" : "▶ Start",
            running ? Color.parseColor("#2e0c18") : Color.parseColor("#082814"),
            running ? C_DANGER : C_OK,
            running ? Color.argb(120, 255, 61, 90) : Color.argb(120, 30, 240, 134),
            v -> toggleBot()
        );

        LinearLayout.LayoutParams bp = lp0(1);
        bp.setMargins(dp(4), 0, dp(4), 0);
        buttons.addView(logoutBtn, bp);
        buttons.addView(configBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        buttons.addView(actionBtn, new LinearLayout.LayoutParams(0, dp(48), 1));

        // fix layout param for logout
        ((LinearLayout.LayoutParams) logoutBtn.getLayoutParams()).height = dp(48);

        nav.addView(buttons, lpW(-1));
        return nav;
    }

    private View navBtn(String label, int bg, int textColor, int stroke, View.OnClickListener l) {
        TextView b = txt(label, 11, true, textColor);
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundRect(bg, dp(10), stroke));
        b.setOnClickListener(l);
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) v.setAlpha(0.75f);
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
            return false;
        });
        return b;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  BOT TOGGLE
    // ═══════════════════════════════════════════════════════════════════════════
    private void toggleBot() {
        if (sp.getBoolean("global_enabled", false)) {
            sp.edit().putBoolean("global_enabled", false).apply();
            startService(new Intent(this, BotForegroundService.class).setAction(BotForegroundService.ACTION_STOP));
            toast("Bot stopped");
        } else {
            requestIgnoreBatteryOptimizationsOnce();
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 99);
            }
            sp.edit().putBoolean("global_enabled", true).apply();
            Intent in = new Intent(this, BotForegroundService.class).setAction(BotForegroundService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(in); else startService(in);
            toast("Bot started");
        }
        showMain();
    }

    private void logout() {
        sp.edit().putBoolean("connected", false).putBoolean("global_enabled", false).apply();
        startService(new Intent(this, BotForegroundService.class).setAction(BotForegroundService.ACTION_STOP));
        try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        toast("Logged out");
        showMain();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  CAP EDITOR
    // ═══════════════════════════════════════════════════════════════════════════
    private void editBossCap(String catLabel, String name, String current) {
        String key = "cap_" + norm(catLabel) + "_" + bossRootKey(name);
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(sp.getString(key, current));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
            .setTitle("Damage cap for")
            .setMessage(name)
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String v = input.getText().toString().trim();
                if (v.isEmpty()) v = sp.getString("cap_default", "2m");
                sp.edit().putString(key, v).apply();
                toast("Cap updated");
                showMain();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  LOGIN SCREEN
    // ═══════════════════════════════════════════════════════════════════════════
    private void showLogin() {
        currentScreen = "login";
        root.removeAllViews();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_BG);
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));

        // toolbar
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(8), statusBarHeight() + dp(4), dp(8), dp(6));
        top.setBackgroundColor(C_SURFACE);
        top.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(top, lpWH(-1, -2));

        top.addView(navBtn("← Back", C_SURFACE, C_MUTED, C_BORDER2, v -> showMain()),  lpWH(0, dp(42)));
        ((LinearLayout.LayoutParams) top.getChildAt(0).getLayoutParams()).weight = 1;

        top.addView(navBtn("Reload", C_SURFACE, C_BLUE, C_BORDER2, v -> { if (loginWeb != null) loginWeb.reload(); }), lpWH(0, dp(42)));
        ((LinearLayout.LayoutParams) top.getChildAt(1).getLayoutParams()).weight = 1;

        top.addView(navBtn(isConnected() ? "✓ Connected" : "Save & Connect", C_SURFACE, isConnected() ? C_OK : C_ACCENT, C_BORDER2, v -> {
            CookieManager.getInstance().flush();
            sp.edit().putBoolean("connected", isConnected()).apply();
            toast(isConnected() ? "Connected cookies saved" : "No login cookie detected yet");
            showLogin();
        }), lpWH(0, dp(42)));
        ((LinearLayout.LayoutParams) top.getChildAt(2).getLayoutParams()).weight = 1;

        loginWeb = new WebView(this);
        WebSettings s = loginWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(loginWeb, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        loginWeb.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String u) { CookieManager.getInstance().flush(); }
        });
        box.addView(loginWeb, new LinearLayout.LayoutParams(-1, 0, 1));
        loginWeb.loadUrl("https://demonicscans.org/");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  CONFIG SCREEN
    // ═══════════════════════════════════════════════════════════════════════════
    private void showConfig() {
        currentScreen = "config";
        destroyLogin();
        root.removeAllViews();
        root.setBackgroundColor(C_BG);

        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), statusBarHeight() + dp(12), dp(12), dp(24));

        TextView title = txt("Configuration", 22, true, C_TEXT);
        box.addView(title);
        TextView sub = txt("All settings saved instantly", 12, false, C_MUTED);
        sub.setPadding(0, 0, 0, dp(14));
        box.addView(sub);

        box.addView(navBtn("← Back to Main", C_SURFACE, C_MUTED, C_BORDER2, v -> showMain()), lpWH(-1, dp(46)));

        addSwitch(box, "Global ON/OFF",          "global_enabled");
        addSwitch(box, "Enable Grakthar",         "enable_grakthar");
        addSwitch(box, "Enable Olympus W9",       "enable_olympus");
        addSwitch(box, "Enable Olympus W10",      "enable_olympus2");
        addSwitch(box, "Enable Olympus W11",      "enable_olympus3");
        addSwitch(box, "Smart delay / jitter",    "smart_delay");
        addSwitch(box, "Vibrate + sound alerts",  "alerts");
        addSwitch(box, "Auto stamina potion",     "auto_potion");
        addSwitch(box, "Asterion computation",    "enable_asterion");
        addSwitch(box, "Dark theme",              "dark");

        addSkillPicker(box);
        addEdit(box, "Scan interval (seconds)",                   "scan_interval");
        addEdit(box, "Default damage cap  (1m / 500m / 1b)",     "cap_default");
        addEdit(box, "Asterion stamina threshold",                "asterion_stamina_threshold");
        addEdit(box, "LSP limit",                                 "lsp_limit");
        addEdit(box, "FSP limit",                                 "fsp_limit");
        addEdit(box, "HP potion limit",                           "hp_limit");

        box.addView(navBtn("Open Battery Settings", C_SURFACE, C_AMBER, C_BORDER2, v -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        }), lpWH(-1, dp(46)));

        sv.addView(box, new ScrollView.LayoutParams(-1, -2));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addSkillPicker(LinearLayout box) {
        LinearLayout s = configSection("Skill selection");
        final String[][] skills = {
            {"Worldbreaker Slash","-5","1000"},
            {"Ultimate Slash",    "-4","200"},
            {"Heroic Slash",      "-2","50"},
            {"Power Slash",       "-1","10"},
            {"Slash",             "0", "1"},
        };
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        String current = sp.getString("skill_id", "0");
        for (String[] sk : skills) {
            RadioButton rb = new RadioButton(this);
            rb.setText(sk[0] + "  •  id=" + sk[1] + "  •  sta=" + sk[2]);
            rb.setTextColor(C_TEXT);
            rb.setTextSize(13);
            rb.setId(sk[1].hashCode());
            rb.setChecked(sk[1].equals(current));
            group.addView(rb);
        }
        group.setOnCheckedChangeListener((g, id) -> {
            for (String[] sk : skills) if (sk[1].hashCode() == id) sp.edit().putString("skill_id", sk[1]).apply();
        });
        s.addView(group);
        box.addView(s);
    }

    private void addSwitch(LinearLayout box, String label, String key) {
        LinearLayout s = configSection(null);
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextSize(15);
        sw.setTextColor(C_TEXT);
        sw.setChecked(sp.getBoolean(key, false));
        sw.setOnCheckedChangeListener((b, v) -> sp.edit().putBoolean(key, v).apply());
        s.addView(sw);
        box.addView(s);
    }

    private void addEdit(LinearLayout box, String label, String key) {
        LinearLayout s = configSection(null);
        s.addView(txt(label, 12, true, C_MUTED));
        EditText e = new EditText(this);
        e.setText(sp.getString(key, ""));
        e.setTextColor(C_TEXT);
        e.setHintTextColor(C_MUTED);
        e.setSingleLine(true);
        e.setOnFocusChangeListener((v, has) -> { if (!has) sp.edit().putString(key, ((EditText) v).getText().toString()).apply(); });
        s.addView(e);
        box.addView(s);
    }

    private LinearLayout configSection(String title) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(dp(12), dp(10), dp(12), dp(10));
        s.setBackground(roundRect(C_CARD, dp(10), C_BORDER2));
        LinearLayout.LayoutParams lp = lpW(-1);
        lp.setMargins(0, dp(6), 0, 0);
        s.setLayoutParams(lp);
        if (title != null) s.addView(txt(title, 14, true, C_TEXT));
        return s;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS — data
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Boss root key: strips everything after the first ',' or '-'
     * so phase names like "Hermes, Ascended Herald" and "Hermes, Duvube Herald"
     * both collapse to "hermes" for pref keys and cap keys.
     */
    private static String bossRootKey(String name) {
        if (name == null || name.isEmpty()) return "";
        // split on comma or hyphen, take first segment
        String[] parts = name.split("[,\\-]", 2);
        return norm(parts[0].trim());
    }

    private static List<String[]> parseBossLines(String data) {
        List<String[]> out = new ArrayList<>();
        if (data == null || data.isEmpty()) return out;
        for (String line : data.split("\n")) {
            String[] p = line.split("\\|", -1);
            if (p.length >= 6) out.add(p);
        }
        return out;
    }

    private String aliveCountAll() {
        List<String[]> lines = parseBossLines(sp.getString("last_bosses", ""));
        long c = 0;
        for (String[] p : lines) if (p.length > 2 && "ALIVE".equalsIgnoreCase(p[2])) c++;
        return c > 0 ? c + " ⚡" : "0";
    }

    private String aliveCountForKey(String key) {
        List<String[]> lines = parseBossLines(sp.getString("last_bosses", ""));
        long total = 0, alive = 0;
        for (String[] p : lines) {
            String cat = p.length > 0 ? norm(p[0]) : "";
            if (cat.equals(norm(key)) || cat.startsWith(norm(key))) {
                total++;
                if (p.length > 2 && "ALIVE".equalsIgnoreCase(p[2])) alive++;
            }
        }
        return alive > 0 ? alive + " ⚡" : total + " dead";
    }

    private boolean hasAlive(String pip) {
        return pip != null && pip.contains("⚡");
    }

    private String latestStamina()   { int v = sp.getInt("live_stamina", -1); return v >= 0 ? String.valueOf(v) : "—"; }
    private String potionCount(String usedKey, String limitKey) {
        int used = sp.getInt(usedKey, 0);
        String limit = sp.getString(limitKey, "10");
        return used + "/" + limit;
    }
    private boolean isConnected() {
        String c = CookieManager.getInstance().getCookie("https://demonicscans.org");
        return c != null && c.length() > 12 && !c.toLowerCase(Locale.US).contains("deleted");
    }
    private String lastScanLabel() {
        long t = sp.getLong("last_scan_ms", 0);
        if (t <= 0) return "never";
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        return (s / 3600) + "h ago";
    }
    private int logCount() {
        String l = sp.getString("logs", "");
        return l.isEmpty() ? 0 : l.split("\n").length;
    }
    private String levelOf(String line) {
        int i = line.indexOf(']');
        if (line.startsWith("[") && i > 0) return line.substring(1, i);
        return "INFO";
    }
    private String msgOf(String line) {
        int i = line.indexOf(']');
        return i >= 0 ? line.substring(i + 1).trim() : line;
    }
    private void copyLogs() {
        String v = sp.getString("logs", "");
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
            .setPrimaryClip(ClipData.newPlainText("CRN Boss Bot logs", v));
        toast("Logs copied");
    }
    private void clearLogs() {
        sp.edit().putString("logs", "").apply();
        startService(new Intent(this, BotForegroundService.class).setAction(BotForegroundService.ACTION_CLEAR_LOGS));
        toast("Logs cleared");
        showMain();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS — views
    // ═══════════════════════════════════════════════════════════════════════════

    private View buildProgressBar(float pct, int width, int height) {
        FrameLayout wrap = new FrameLayout(this);
        LinearLayout.LayoutParams wlp = lpW(width < 0 ? -1 : width);
        wlp.setMargins(0, dp(4), 0, 0);
        wrap.setLayoutParams(wlp);

        View track = new View(this);
        track.setBackgroundColor(Color.argb(40, 255, 255, 255));
        GradientDrawable td = new GradientDrawable();
        td.setColor(Color.argb(40, 255, 255, 255));
        td.setCornerRadius(dp(2));
        track.setBackground(td);
        wrap.addView(track, new FrameLayout.LayoutParams(-1, height));

        if (pct > 0) {
            View fill = new View(this);
            GradientDrawable fd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                pct >= 1f
                    ? new int[]{ C_OK, Color.parseColor("#00ffaa") }
                    : new int[]{ C_BLUE, C_ACCENT }
            );
            fd.setCornerRadius(dp(2));
            fill.setBackground(fd);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams((int)(pct * (width < 0 ? 9999 : width)), height);
            if (width < 0) {
                // use weight trick: post to measure
                fill.setTag(pct);
                wrap.addView(fill, new FrameLayout.LayoutParams(-1, height));
                wrap.post(() -> {
                    int w = wrap.getWidth();
                    fill.getLayoutParams().width = (int)(((Float) fill.getTag()) * w);
                    fill.requestLayout();
                });
            } else {
                wrap.addView(fill, flp);
            }
        }
        return wrap;
    }

    private View emptyCard(String msg) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(16), dp(14), dp(16));
        c.setBackground(roundRect(C_CARD, dp(10), C_BORDER2));
        LinearLayout.LayoutParams lp = lpW(-1);
        lp.setMargins(0, dp(6), 0, 0);
        c.setLayoutParams(lp);
        TextView t = txt(msg, 13, false, C_MUTED);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        return c;
    }

    private TextView smallBtn(String label, int color, View.OnClickListener l) {
        TextView b = txt(label, 10, true, color);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setBackground(roundRect(Color.TRANSPARENT, dp(6), color));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = lpWH(-2, -2);
        lp.setMargins(dp(6), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView chip(String text, int bg, int textColor, int stroke) {
        TextView t = txt(text, 9, true, textColor);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(7), dp(2), dp(7), dp(2));
        t.setBackground(roundRect(bg, dp(5), stroke));
        return t;
    }

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(C_BORDER);
        return d;
    }

    private GradientDrawable rightBorder(int color) {
        // Simulate right border via a layer — simplest approach is just card bg
        return roundRect(C_SURFACE, 0, color);
    }

    private LinearLayout row(int gravity) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(gravity);
        return r;
    }

    private TextView txt(String v, int spSize, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(v);
        t.setTextSize(spSize);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeColor != Color.TRANSPARENT) g.setStroke(dp(1), strokeColor);
        return g;
    }

    // layout param helpers
    private LinearLayout.LayoutParams lp0(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2);
        lp.weight = weight;
        return lp;
    }
    private LinearLayout.LayoutParams lpW(int width) {
        return new LinearLayout.LayoutParams(width, -2);
    }
    private LinearLayout.LayoutParams lpWH(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private GridLayout.LayoutParams bossCardLp() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = (getResources().getDisplayMetrics().widthPixels - dp(36)) / 2;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS — misc
    // ═══════════════════════════════════════════════════════════════════════════

    private String waveIcon(String key) {
        if (key.contains("grakthar")) return "⚔️";
        if (key.contains("olympus"))  return "🌊";
        return "⚡";
    }
    private String waveIconForLabel(String label) {
        String l = label.toLowerCase(Locale.US);
        if (l.contains("grakthar")) return "⚔️";
        if (l.contains("olympus"))  return "🌊";
        return "⚡";
    }

    private static String norm(String s) {
        return (s == null ? "" : s).toLowerCase(Locale.US)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    }

    private static long parseLong(String s) {
        try { return Long.parseLong((s == null ? "0" : s).replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private String fmt(String s) {
        try {
            long n = Long.parseLong(s);
            if (n >= 1_000_000_000) return String.format(Locale.US, "%.1fb", n / 1_000_000_000d).replace(".0", "");
            if (n >= 1_000_000)     return String.format(Locale.US, "%.1fm", n / 1_000_000d).replace(".0", "");
            if (n >= 1_000)         return (n / 1_000) + "k";
            return String.valueOf(n);
        } catch (Exception e) {
            return (s == null || s.isEmpty()) ? "—" : s;
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void destroyLogin() {
        if (loginWeb != null) {
            try { loginWeb.stopLoading(); loginWeb.destroy(); } catch (Exception ignored) {}
            loginWeb = null;
        }
    }

    private void requestIgnoreBatteryOptimizationsOnce() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (sp.getBoolean("asked_battery_optimization", false)) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                sp.edit().putBoolean("asked_battery_optimization", true).apply();
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }

    private void requestInitialScanOnce() {
        if (!isConnected()) return;
        if (sp.getBoolean("global_enabled", false)) return;
        long last = sp.getLong("initial_scan_request_ms", 0);
        if (System.currentTimeMillis() - last < 30000) return;
        sp.edit().putLong("initial_scan_request_ms", System.currentTimeMillis())
          .putString("ui_state", "SCAN").apply();
        Intent in = new Intent(this, BotForegroundService.class).setAction(BotForegroundService.ACTION_SCAN_ONCE);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(in); else startService(in);
    }
}
