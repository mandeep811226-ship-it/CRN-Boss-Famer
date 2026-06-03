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
    private int activeTab = 0;
    private ScrollView mainScrollView;

    private final android.os.Handler refreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshRunnable = this::doRefreshMain;
    private boolean bgRefresh = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if ("main".equals(currentScreen)) {
                refreshHandler.removeCallbacks(refreshRunnable);
                refreshHandler.postDelayed(refreshRunnable, 300);
            }
        }
    };

    private static final String[][] BUILTIN_WAVES = {
        { "grakthar", "Grakthar", "https://demonicscans.org/active_wave.php?gate=3&wave=8" },
        { "olympus",  "Oly W9",   "https://demonicscans.org/active_wave.php?gate=5&wave=9"  },
        { "hermes",   "Hermes",   "https://demonicscans.org/active_wave.php?gate=5&wave=10" },
    };

    private static final String PREF_CUSTOM_WAVES    = "custom_waves_json";
    private static final String PREF_DIRECT_MONSTERS = "direct_monsters_json";
    private List<String[]> allWaveTabs = new ArrayList<>();
    private LinearLayout tabAllPanel;
    private LinearLayout tabMonstersPanel;   // ⚔️ Monsters tab panel
    private List<LinearLayout> wavePanelList = new ArrayList<>();
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
              .putBoolean("enable_hermes",   true)
              .putBoolean("smart_delay", true)
              .putBoolean("alerts", true)
              .putBoolean("auto_potion", true)
              .putString("skill_id", "-4")
              .putString("scan_interval", "60")
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
    //  MAIN SCREEN
    // ═══════════════════════════════════════════════════════════════════════════
    private void showMain() {
        currentScreen = "main";
        destroyLogin();
        allWaveTabs = buildAllWaveTabs();
        wavePanelList = new ArrayList<>();
        root.removeAllViews();
        root.setBackgroundColor(C_BG);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        buildHeader(shell);
        buildStatStrip(shell);
        buildTabBar(shell);
        buildPanelHost(shell);
    }

    private void doRefreshMain() {
        final int savedScroll = mainScrollView != null ? mainScrollView.getScrollY() : 0;
        bgRefresh = true;
        showMain();
        bgRefresh = false;
        if (mainScrollView != null && savedScroll > 0)
            mainScrollView.post(() -> mainScrollView.scrollTo(0, savedScroll));
    }

    // ── Header ─────────────────────────────────────────────────────────────────
    private void buildHeader(LinearLayout shell) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setBackgroundColor(C_SURFACE);
        h.setPadding(dp(14), statusBarHeight() + dp(10), dp(14), dp(10));
        LinearLayout r1 = row(Gravity.CENTER_VERTICAL);
        TextView title = txt("CRN Boss Bot", 18, true, Color.WHITE);
        if (Build.VERSION.SDK_INT >= 21) title.setLetterSpacing(0.04f);
        r1.addView(title, lp0(1));
        boolean running = sp.getBoolean("global_enabled", false);
        TextView badge = chip(running ? "● RUNNING" : "■ STOPPED",
            running ? Color.argb(40,0,229,200) : Color.argb(40,255,61,90),
            running ? C_ACCENT : C_DANGER,
            running ? Color.argb(160,0,229,200) : Color.argb(160,255,61,90));
        r1.addView(badge, lpWH(dp(96), dp(26)));
        h.addView(r1);
        LinearLayout r2 = row(Gravity.CENTER_VERTICAL);
        r2.setPadding(0, dp(6), 0, 0);
        boolean conn = isConnected();
        r2.addView(txt(conn ? "● Connected" : "● Disconnected", 12, true, conn ? C_OK : C_DANGER), lp0(1));
        TextView scanTxt = txt("Last scan: " + lastScanLabel(), 11, false, C_MUTED);
        scanTxt.setGravity(Gravity.END);
        r2.addView(scanTxt, lp0(1));
        h.addView(r2);
        String msg = sp.getString("ui_message", "");
        if (msg.length() > 0) { TextView m = txt("› " + msg, 11, false, C_MUTED); m.setPadding(0, dp(4), 0, 0); h.addView(m); }
        h.addView(divider());
        shell.addView(h, lpW(-1));
    }

    // ── Stat strip ─────────────────────────────────────────────────────────────
    private void buildStatStrip(LinearLayout shell) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBackgroundColor(C_SURFACE);
        String[][] stats = {{"⚡","STA",latestStamina()},{"🧪","LSP",potionCount("used_lsp","lsp_limit")},{"🔥","FSP",potionCount("used_fsp","fsp_limit")},{"💙","HP",potionCount("used_hp","hp_limit")}};
        for (int i=0;i<stats.length;i++){View cell=buildStatCell(stats[i][0],stats[i][1],stats[i][2]);LinearLayout.LayoutParams lp=lp0(1);if(i<stats.length-1)cell.setBackground(rightBorder(C_BORDER));strip.addView(cell,lp);}
        LinearLayout wrap=new LinearLayout(this);((LinearLayout)wrap).setOrientation(LinearLayout.VERTICAL);((LinearLayout)wrap).addView(strip,lpW(-1));((LinearLayout)wrap).addView(divider());shell.addView(wrap,lpW(-1));
    }
    private View buildStatCell(String icon,String label,String value){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setGravity(Gravity.CENTER);c.setPadding(dp(4),dp(8),dp(4),dp(8));c.addView(txt(icon,14,false,Color.WHITE));c.addView(txt(label,8,true,C_MUTED));c.addView(txt(value,11,true,C_TEXT));return c;}

    // ── Tab bar ────────────────────────────────────────────────────────────────
    private void buildTabBar(LinearLayout shell) {
        tabBarScroll=new HorizontalScrollView(this);tabBarScroll.setHorizontalScrollBarEnabled(false);tabBarScroll.setBackgroundColor(C_SURFACE);
        tabBarInner=new LinearLayout(this);tabBarInner.setOrientation(LinearLayout.HORIZONTAL);tabBarInner.setPadding(dp(6),0,dp(6),0);
        tabBarInner.addView(buildTab(0,"🗺️","All",aliveCountAll()));
        for(int i=0;i<allWaveTabs.size();i++){String[] wt=allWaveTabs.get(i);String emoji=wt.length>3?wt[3]:waveIcon(wt[0]);tabBarInner.addView(buildTab(i+1,emoji,wt[1],aliveCountForKey(wt[0])));}
        // ── Monsters tab ──────────────────────────────────────────────────────────
        int monstersIdx = allWaveTabs.size() + 1;
        tabBarInner.addView(buildTab(monstersIdx, "⚔️", "Monsters", monsterAliveCount()));
        int logsIdx = allWaveTabs.size() + 2;
        // ─────────────────────────────────────────────────────────────────────────
        tabBarInner.addView(buildTab(logsIdx,"📋","Logs",logCount()+""));
        tabBarInner.addView(buildAddTab());
        tabBarScroll.addView(tabBarInner,new LinearLayout.LayoutParams(-2,-1));
        LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);wrap.setBackgroundColor(C_SURFACE);wrap.addView(tabBarScroll,new LinearLayout.LayoutParams(-1,dp(58)));wrap.addView(divider());shell.addView(wrap,lpW(-1));
        highlightTab(activeTab);
    }
    private View buildAddTab(){LinearLayout tab=new LinearLayout(this);tab.setOrientation(LinearLayout.VERTICAL);tab.setGravity(Gravity.CENTER);tab.setPadding(dp(14),dp(8),dp(14),dp(6));tab.setMinimumWidth(dp(54));tab.addView(txt("➕",18,false,C_ACCENT));TextView lbl=txt("Add",8,true,C_MUTED);lbl.setGravity(Gravity.CENTER);tab.addView(lbl);tab.setOnClickListener(v->showAddWaveDialog());return tab;}
    private View buildTab(final int idx,String icon,String name,String pip){
        LinearLayout tab=new LinearLayout(this);tab.setOrientation(LinearLayout.VERTICAL);tab.setGravity(Gravity.CENTER);tab.setPadding(dp(12),dp(8),dp(12),dp(6));tab.setMinimumWidth(dp(70));
        tab.addView(txt(icon,16,false,Color.WHITE));TextView nameTv=txt(name,8,true,C_TEXT2);if(Build.VERSION.SDK_INT>=21)nameTv.setLetterSpacing(0.06f);tab.addView(nameTv);
        boolean hasAlive=hasAlive(pip);TextView pipTv=chip(pip,hasAlive?Color.argb(40,0,229,200):Color.argb(20,30,47,78),hasAlive?C_ACCENT:C_MUTED,hasAlive?Color.argb(120,0,229,200):C_BORDER2);pipTv.setTextSize(8);pipTv.setPadding(dp(5),dp(1),dp(5),dp(1));tab.addView(pipTv);
        tab.setOnClickListener(v->switchTab(idx));
        int waveIdx=idx-1;if(waveIdx>=0&&waveIdx<allWaveTabs.size()){String[] wave=allWaveTabs.get(waveIdx);boolean isBuiltin=waveIdx<BUILTIN_WAVES.length;tab.setOnLongClickListener(v->{showDeleteWaveDialog(wave,isBuiltin);return true;});}
        return tab;
    }
    private void highlightTab(int idx){if(tabBarInner==null)return;for(int i=0;i<tabBarInner.getChildCount();i++){View child=tabBarInner.getChildAt(i);if(i==idx){child.setBackgroundColor(Color.argb(30,0,229,200));child.setTag("active");}else{child.setBackgroundColor(Color.TRANSPARENT);child.setTag(null);}}}
    private void switchTab(int idx){activeTab=idx;highlightTab(idx);int total=allWaveTabs.size()+3;for(int i=0;i<total;i++){View panel=getPanelAt(i);if(panel!=null)panel.setVisibility(i==idx?View.VISIBLE:View.GONE);}tabBarScroll.post(()->{View tab=tabBarInner.getChildAt(idx);if(tab!=null){if(bgRefresh)tabBarScroll.scrollTo(tab.getLeft(),0);else tabBarScroll.smoothScrollTo(tab.getLeft(),0);}});}
    private View getPanelAt(int i){
        int monstersIdx = allWaveTabs.size()+1;
        int logsIdx     = allWaveTabs.size()+2;
        if(i==0) return tabAllPanel;
        if(i==monstersIdx) return tabMonstersPanel;
        if(i==logsIdx) return tabLogsPanel;
        int waveIdx=i-1;if(waveIdx>=0&&waveIdx<wavePanelList.size())return wavePanelList.get(waveIdx);
        return null;
    }

    // ── Panel host ─────────────────────────────────────────────────────────────
    private void buildPanelHost(LinearLayout shell) {
        LinearLayout host=new LinearLayout(this);host.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(C_BG);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(10),dp(8),dp(10),dp(8));
        tabAllPanel=buildAllPanel();content.addView(tabAllPanel);
        List<String[]> allBossLines=parseBossLines(sp.getString("last_bosses",""));
        wavePanelList.clear();
        for(String[] wt:allWaveTabs){LinearLayout p=buildWavePanel(wt,allBossLines);wavePanelList.add(p);content.addView(p);}
        // ── Monsters tab panel ────────────────────────────────────────────────────
        tabMonstersPanel = buildMonstersPanel(allBossLines);
        content.addView(tabMonstersPanel);
        // ─────────────────────────────────────────────────────────────────────────
        tabLogsPanel=buildLogsPanel();content.addView(tabLogsPanel);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        host.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        host.addView(buildBottomNav(),new LinearLayout.LayoutParams(-1,-2));
        mainScrollView=scroll;
        shell.addView(host,new LinearLayout.LayoutParams(-1,0,1));
        if(bgRefresh){int total=allWaveTabs.size()+3;for(int i=0;i<total;i++){View panel=getPanelAt(i);if(panel!=null)panel.setVisibility(i==activeTab?View.VISIBLE:View.GONE);}highlightTab(activeTab);}else{switchTab(activeTab);}
    }

    // ── ALL panel ──────────────────────────────────────────────────────────────
    private LinearLayout buildAllPanel() {
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        List<String[]> lines=parseBossLines(sp.getString("last_bosses",""));
        if(!isConnected()){panel.addView(buildConnectBanner());return panel;}
        if(lines.isEmpty()){panel.addView(emptyCard("No scan data yet.\nTap ▶ Start below to begin scanning bosses."));return panel;}
        LinkedHashMap<String,List<String[]>> byCategory=new LinkedHashMap<>();
        for(String[] p:lines) byCategory.computeIfAbsent(p[0],k->new ArrayList<>()).add(p);
        for(Map.Entry<String,List<String[]>> entry:byCategory.entrySet()){
            String catLabel=entry.getKey();List<String[]> bosses=entry.getValue();
            long alive=0;for(String[] b:bosses)if("ALIVE".equalsIgnoreCase(b[2]))alive++;
            panel.addView(buildSectionHeader(waveIconForLabel(catLabel)+" "+catLabel.toUpperCase(Locale.US),alive+" alive",alive>0));
            for(String[] p:bosses) panel.addView(buildOverviewRow(p));
        }
        return panel;
    }

    private View buildConnectBanner() {
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);card.setPadding(dp(20),dp(28),dp(20),dp(24));card.setBackground(roundRect(Color.parseColor("#0d1e36"),dp(14),Color.argb(180,0,229,200)));LinearLayout.LayoutParams lp=lpW(-1);lp.setMargins(0,dp(12),0,0);card.setLayoutParams(lp);
        TextView icon=txt("🔐",34,false,Color.WHITE);icon.setGravity(Gravity.CENTER);card.addView(icon);
        TextView title=txt("Not Connected",16,true,Color.WHITE);title.setGravity(Gravity.CENTER);LinearLayout.LayoutParams tlp=lpW(-1);tlp.setMargins(0,dp(10),0,dp(6));title.setLayoutParams(tlp);card.addView(title);
        TextView body=txt("Log in to demonicscans.org to start automating boss battles.",13,false,C_TEXT2);body.setGravity(Gravity.CENTER);body.setSingleLine(false);card.addView(body);
        TextView loginBtn=txt("🔑  Log In Now",13,true,Color.parseColor("#03090f"));loginBtn.setGravity(Gravity.CENTER);loginBtn.setPadding(dp(28),dp(12),dp(28),dp(12));GradientDrawable btnBg=new GradientDrawable();btnBg.setColor(C_ACCENT);btnBg.setCornerRadius(dp(10));loginBtn.setBackground(btnBg);loginBtn.setOnClickListener(v->showLogin());LinearLayout.LayoutParams blp=lpWH(-2,-2);blp.setMargins(0,dp(18),0,0);blp.gravity=Gravity.CENTER_HORIZONTAL;loginBtn.setLayoutParams(blp);card.addView(loginBtn);
        return card;
    }
    private View buildSectionHeader(String label,String pip,boolean hasAlive){LinearLayout h=row(Gravity.CENTER_VERTICAL);h.setPadding(dp(4),dp(10),dp(4),dp(4));TextView lbl=txt(label,10,true,C_TEXT2);if(Build.VERSION.SDK_INT>=21)lbl.setLetterSpacing(0.08f);h.addView(lbl);View line=new View(this);line.setBackgroundColor(C_BORDER);LinearLayout.LayoutParams llp=lp0(1);llp.setMargins(dp(8),0,dp(8),0);llp.gravity=Gravity.CENTER_VERTICAL;llp.height=dp(1);h.addView(line,llp);int pipBg=hasAlive?Color.argb(40,0,229,200):Color.TRANSPARENT;int pipTxt=hasAlive?C_ACCENT:C_MUTED;int pipStr=hasAlive?Color.argb(120,0,229,200):C_BORDER2;h.addView(chip(pip,pipBg,pipTxt,pipStr));return h;}
    private View buildOverviewRow(String[] p) {
        String name=p.length>1?p[1]:"—";String status=p.length>2?p[2]:"";String damage=p.length>3?p[3]:"0";String cap=p.length>4?p[4]:"0";
        boolean alive="ALIVE".equalsIgnoreCase(status);
        LinearLayout row=row(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(9),dp(12),dp(9));LinearLayout.LayoutParams rlp=lpW(-1);rlp.setMargins(0,dp(3),0,0);row.setLayoutParams(rlp);
        GradientDrawable bg=roundRect(C_CARD,dp(9),C_BORDER2);bg.setStroke(dp(3),alive?C_ACCENT:C_BORDER);row.setBackground(bg);
        TextView nameTv=txt(name,12,true,C_TEXT);nameTv.setSingleLine(false);nameTv.setMaxLines(2);nameTv.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(nameTv,lp0(1));
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setGravity(Gravity.END);right.setPadding(dp(10),0,0,0);
        long dmgLong=parseLong(damage);long capLong=parseLong(cap);float pct=capLong>0?Math.min(1f,(float)dmgLong/capLong):0f;
        right.addView(txt(fmt(damage),11,true,alive?C_TEXT:C_MUTED));right.addView(buildProgressBar(pct,dp(60),dp(3)));row.addView(right,lpWH(-2,-2));
        return row;
    }

    // ── Per-wave panel ─────────────────────────────────────────────────────────
    private LinearLayout buildWavePanel(String[] waveDef, List<String[]> allLines) {
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        String key=waveDef[0];String label=waveDef[1];
        List<String[]> bosses=new ArrayList<>();
        for(String[] p:allLines){if(p.length>0&&norm(p[0]).equals(norm(label)))bosses.add(p);else if(p.length>0&&norm(p[0]).startsWith(norm(key)))bosses.add(p);}
        LinearLayout subHead=row(Gravity.CENTER_VERTICAL);subHead.setPadding(dp(2),dp(6),dp(2),dp(8));
        subHead.addView(txt(key+" • "+bosses.size()+" boss"+(bosses.size()!=1?"es":""),11,false,C_MUTED),lp0(1));
        TextView toggleAll=txt("Toggle All",10,true,C_TEXT2);toggleAll.setPadding(dp(10),dp(4),dp(10),dp(4));toggleAll.setBackground(roundRect(Color.TRANSPARENT,dp(6),C_BORDER2));toggleAll.setOnClickListener(v->toggleAllBosses(key,bosses));subHead.addView(toggleAll,lpWH(-2,-2));
        panel.addView(subHead);
        if(bosses.isEmpty()){panel.addView(emptyCard("No bosses found for "+label+" yet."));return panel;}
        for(int i=0;i<bosses.size();i+=2){
            LinearLayout pair=row(0);
            LinearLayout.LayoutParams cardLp1=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);cardLp1.setMargins(dp(4),dp(4),dp(2),dp(4));pair.addView(buildBossCard(bosses.get(i)),cardLp1);
            if(i+1<bosses.size()){LinearLayout.LayoutParams cardLp2=new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f);cardLp2.setMargins(dp(2),dp(4),dp(4),dp(4));pair.addView(buildBossCard(bosses.get(i+1)),cardLp2);}
            else{View filler=new View(this);pair.addView(filler,new LinearLayout.LayoutParams(0,0,1f));}
            panel.addView(pair,lpW(-1));
        }
        return panel;
    }
    private void toggleAllBosses(String key,List<String[]> bosses){boolean anyOff=false;for(String[] p:bosses){String bKey=norm(p[0])+":"+bossRootKey(p[1]);if(!sp.getBoolean("boss_enabled_"+bKey,false)){anyOff=true;break;}}boolean target=anyOff;SharedPreferences.Editor ed=sp.edit();for(String[] p:bosses){String bKey=norm(p[0])+":"+bossRootKey(p[1]);ed.putBoolean("boss_enabled_"+bKey,target);}ed.apply();showMain();}

    // ═══════════════════════════════════════════════════════════════════════════
    //  BOSS CARD  ← Phase system fully integrated here
    // ═══════════════════════════════════════════════════════════════════════════
    private View buildBossCard(String[] p) {
        String catLabel     = p.length > 0 ? p[0] : "";
        String name         = p.length > 1 ? p[1] : "—";
        String status       = p.length > 2 ? p[2] : "";
        String damage       = p.length > 3 ? p[3] : "0";
        String cap          = p.length > 4 ? p[4] : "0";
        String image        = p.length > 6 ? p[6] : "";
        String timer        = p.length > 8 ? p[8] : "";
        // slot 9: currentPhaseName — set by service when boss transitions live
        String currentPhase = p.length > 9 ? p[9] : "";

        final String catKey  = (p.length > 7 && p[7] != null && !p[7].isEmpty()) ? p[7] : norm(catLabel);
        final String rootKey = bossRootKey(name);
        final String prefKey = "boss_enabled_" + catKey + ":" + rootKey;
        final boolean enabled = sp.getBoolean(prefKey, false);
        boolean alive  = "ALIVE".equalsIgnoreCase(status);
        long dmgLong   = parseLong(damage);
        long capLong   = parseLong(cap);
        float pct      = capLong > 0 ? Math.min(1f, (float)dmgLong / capLong) : 0f;
        boolean capped = capLong > 0 && dmgLong >= capLong;

        // ── Phase state ────────────────────────────────────────────────────────
        boolean isPhaseBoss        = sp.getBoolean("phase_boss_" + rootKey, false);
        int     phaseCount         = sp.getInt("phase_count_" + rootKey, 0);
        boolean inDifferentPhase   = !currentPhase.isEmpty()
                                     && !currentPhase.equalsIgnoreCase(name);
        // Effective cap for progress display — phase-aware
        String  effectiveCapRaw    = resolveEffectiveCapRaw(rootKey, name, catKey);
        boolean noCap              = effectiveCapRaw.isEmpty() || parseLong(effectiveCapRaw) == 0;
        // Generic cap key (unchanged — still works as fallback)
        final String capKey        = "cap_" + catKey + "_" + rootKey;
        final String rawCapPref    = sp.getString(capKey, "");
        // ──────────────────────────────────────────────────────────────────────

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(10), dp(10), dp(9));
        GradientDrawable cardBg = roundRect(
            alive ? Color.parseColor("#0a1e1c") : C_CARD, dp(12),
            alive ? Color.argb(180, 0, 229, 200) : C_BORDER2);
        if (!enabled) cardBg.setAlpha(120);
        c.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= 21) c.setElevation(dp(1));

        // ── row 1: boss name + enable toggle ──────────────────────────────────
        FrameLayout nameFrame = new FrameLayout(this);
        TextView nameTv = txt(name, 12, true, Color.WHITE);
        nameTv.setSingleLine(false);
        FrameLayout.LayoutParams nameFLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        nameFLp.rightMargin = dp(50);
        nameTv.setLayoutParams(nameFLp);
        nameFrame.addView(nameTv);
        Switch sw = new Switch(this);
        sw.setChecked(enabled); sw.setScaleX(0.72f); sw.setScaleY(0.72f); sw.setPadding(0,0,0,0);
        sw.setOnCheckedChangeListener((btn, val) -> { sp.edit().putBoolean(prefKey, val).apply(); cardBg.setAlpha(val ? 255 : 120); });
        nameFrame.addView(sw, new FrameLayout.LayoutParams(dp(46), dp(28), Gravity.TOP | Gravity.END));
        c.addView(nameFrame);

        // ── Phase Boss toggle row ──────────────────────────────────────────────
        // Visible on every boss card. Lets user manually declare any boss as phase-capable.
        // Auto-detection also sets this flag silently when it learns a phase.
        LinearLayout phaseToggleRow = row(Gravity.CENTER_VERTICAL);
        phaseToggleRow.setPadding(0, dp(4), 0, dp(2));
        TextView phaseLbl = txt("⚡ Phase Boss", 9, true, isPhaseBoss ? C_ACCENT : C_MUTED);
        phaseToggleRow.addView(phaseLbl, lp0(1));
        Switch phaseSw = new Switch(this);
        phaseSw.setChecked(isPhaseBoss); phaseSw.setScaleX(0.62f); phaseSw.setScaleY(0.62f);
        phaseSw.setOnCheckedChangeListener((btn, val) -> {
            sp.edit().putBoolean("phase_boss_" + rootKey, val).apply();
            phaseLbl.setTextColor(val ? C_ACCENT : C_MUTED);
            showMain();
        });
        FrameLayout pswFrame = new FrameLayout(this);
        pswFrame.addView(phaseSw, new FrameLayout.LayoutParams(dp(46), dp(26), Gravity.CENTER_VERTICAL | Gravity.END));
        phaseToggleRow.addView(pswFrame, lpWH(dp(54), dp(26)));
        c.addView(phaseToggleRow);
        // ──────────────────────────────────────────────────────────────────────

        // ── row 2: status pill + live phase chip + damage ──────────────────────
        LinearLayout r2 = row(Gravity.CENTER_VERTICAL);
        r2.setPadding(0, dp(5), 0, dp(4));
        String displayStatus = ("WAITING".equalsIgnoreCase(status)||status.isEmpty()) ? "WAIT" : status.toUpperCase(Locale.US);
        int pillBg=alive?Color.argb(50,0,229,200):Color.argb(40,60,80,110);
        int pillTxt=alive?C_ACCENT:C_MUTED;
        int pillStr=alive?Color.argb(150,0,229,200):C_BORDER2;
        r2.addView(chip(displayStatus,pillBg,pillTxt,pillStr));

        // Live phase chip — shown when boss is currently in a different phase name
        if (inDifferentPhase) {
            String shortPhase = shortPhaseLabel(currentPhase);
            TextView phaseChip = chip("⚡ " + shortPhase,
                Color.argb(50,255,176,32), C_AMBER, Color.argb(120,255,176,32));
            phaseChip.setTextSize(7);
            phaseChip.setPadding(dp(5), dp(2), dp(5), dp(2));
            LinearLayout.LayoutParams pclp = lpWH(-2, -2);
            pclp.setMargins(dp(4), 0, 0, 0);
            phaseChip.setLayoutParams(pclp);
            r2.addView(phaseChip);
        }

        TextView dmgTv = txt("  " + fmt(damage), 13, true, alive ? Color.WHITE : C_MUTED);
        dmgTv.setSingleLine(true);
        r2.addView(dmgTv, lp0(1));
        c.addView(r2);

        // ── Progress bar (phase-aware cap) ─────────────────────────────────────
        long effectiveCapLong = noCap ? 0 : parseCap(effectiveCapRaw);
        float effectivePct = effectiveCapLong > 0 ? Math.min(1f, (float)dmgLong / effectiveCapLong) : pct;
        c.addView(buildProgressBar(effectivePct, -1, dp(3)));

        // ── Cap section — branches on isPhaseBoss ──────────────────────────────
        if (!isPhaseBoss) {
            // ── Simple boss: standard cap row ──────────────────────────────────
            LinearLayout r3 = row(Gravity.CENTER_VERTICAL);
            r3.setPadding(0, 0, 0, dp(4));
            TextView capLbl2 = txt("Cap", 9, true, C_MUTED);
            if (Build.VERSION.SDK_INT >= 21) capLbl2.setLetterSpacing(0.04f);
            r3.addView(capLbl2, lp0(1));
            boolean noCap2 = rawCapPref.isEmpty() || parseLong(rawCapPref) == 0;
            TextView capChip = chip(noCap2 ? "No Cap" : rawCapPref,
                noCap2 ? Color.argb(40,255,176,32) : Color.argb(40,59,158,255),
                noCap2 ? C_AMBER : C_BLUE,
                noCap2 ? Color.argb(120,255,176,32) : Color.argb(100,59,158,255));
            capChip.setTextSize(11); capChip.setPadding(dp(14),dp(5),dp(14),dp(5));
            capChip.setOnClickListener(v -> editBossCap(catKey, name, rawCapPref));
            r3.addView(capChip, lpWH(-2, -2));
            c.addView(r3);

            String fracText = noCap2 ? (fmt(damage)+" / No Cap")
                : (fmt(damage)+" / "+fmt(cap)+(capped?" ✓":""));
            TextView frac = txt(fracText, 9, false, C_MUTED);
            frac.setPadding(0, dp(3), 0, dp(4));
            c.addView(frac);

            // Preset buttons
            LinearLayout presets = row(Gravity.CENTER_VERTICAL);
            presets.setPadding(0, dp(2), 0, 0);
            presets.addView(txt("Presets:", 8, false, C_MUTED));
            for (String preset : new String[]{"1b","3b","5b"}) {
                boolean isActive=preset.equals(rawCapPref);
                TextView pb=chip(preset,isActive?Color.argb(60,0,229,200):Color.argb(20,0,229,200),isActive?Color.WHITE:C_ACCENT,isActive?Color.argb(180,0,229,200):Color.argb(60,0,229,200));
                pb.setTextSize(9);pb.setPadding(dp(9),dp(3),dp(9),dp(3));LinearLayout.LayoutParams plp=lpWH(-2,-2);plp.setMargins(dp(5),0,0,0);pb.setLayoutParams(plp);
                pb.setOnClickListener(v->{sp.edit().putString(capKey,preset).apply();showMain();});
                presets.addView(pb);
            }
            c.addView(presets);

        } else {
            // ── Phase boss: fraction line + phase section ───────────────────────
            String fracText = noCap ? (fmt(damage)+" / No Cap")
                : (fmt(damage)+" / "+fmt(String.valueOf(effectiveCapLong))+(effectivePct>=1?" ✓":""));
            TextView frac = txt(fracText, 9, false, C_MUTED);
            frac.setPadding(0, dp(3), 0, dp(4));
            c.addView(frac);

            // Phase settings section — inline, inside this boss card
            c.addView(buildPhaseSection(rootKey, catKey, name, phaseCount));
        }

        // ── Timer row ──────────────────────────────────────────────────────────
        boolean hasAutoTimer = timer != null && !timer.isEmpty();
        if (alive || hasAutoTimer) {
            LinearLayout timerRow = row(Gravity.CENTER_VERTICAL);
            timerRow.setPadding(0, dp(6), 0, dp(2));
            String timerDisplay = alive && hasAutoTimer ? "⏳ Alive - " + timer
                : alive ? "⏳ Alive" : timer;
            TextView timerTv = txt(timerDisplay, 10, false, hasAutoTimer ? C_AMBER : C_MUTED);
            timerTv.setSingleLine(true);
            timerRow.addView(timerTv, lp0(1));
            c.addView(timerRow);
        }

        return c;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PHASE SECTION — built inside the boss card when Phase Boss is ON
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds the phase settings block that appears inside the boss card
     * when the Phase Boss toggle is ON.
     *
     * Shows each declared phase as its own row:
     *   [label] [AUTO/MANUAL] [PvE/PvP toggle] [cap chip] [presets] [delete]
     *
     * Includes a "+ Add Phase" button to manually declare future phases.
     */
    private LinearLayout buildPhaseSection(String rootKey, String catKey,
                                            String bossName, int phaseCount) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(4), 0, dp(2));

        // Section header with + Add Phase button
        LinearLayout sectionHead = row(Gravity.CENTER_VERTICAL);
        sectionHead.setPadding(0, dp(4), 0, dp(6));
        TextView sectionTitle = txt("Phase Settings", 8, true, C_TEXT2);
        if (Build.VERSION.SDK_INT >= 21) sectionTitle.setLetterSpacing(0.06f);
        sectionHead.addView(sectionTitle, lp0(1));

        TextView addBtn = txt("+ Add Phase", 8, true, C_ACCENT);
        addBtn.setPadding(dp(7), dp(3), dp(7), dp(3));
        addBtn.setBackground(roundRect(Color.argb(30,0,229,200), dp(5),
            Color.argb(80,0,229,200)));
        addBtn.setOnClickListener(v ->
            showAddPhaseDialog(rootKey, catKey, bossName, phaseCount));
        sectionHead.addView(addBtn, lpWH(-2, -2));
        section.addView(sectionHead);

        section.addView(divider());

        if (phaseCount == 0) {
            TextView emptyTv = txt(
                "No phases declared. Tap + or wait for auto-detect.", 8, false, C_MUTED);
            emptyTv.setSingleLine(false);
            emptyTv.setPadding(0, dp(5), 0, dp(3));
            section.addView(emptyTv);
        } else {
            for (int i = 0; i < phaseCount; i++)
                section.addView(buildPhaseRow(rootKey, catKey, bossName, i, phaseCount));
        }
        return section;
    }

    /**
     * Builds a single phase row inside the phase section.
     *
     * Layout (two lines):
     *  Line 1: [Phase N]  [AUTO|MANUAL]  [PvE/PvP toggle]  [✕ delete]
     *  Line 2: [🔍 fragment chip]  [cap chip]  [1b][3b][5b]
     *          OR for PvP: [PvP — bot skips this phase]
     */
    private View buildPhaseRow(String rootKey, String catKey,
                                String bossName, int idx, int totalCount) {
        String label  = sp.getString("phase_label_"    + rootKey + "_" + idx, "Phase "+(idx+1));
        String frag   = sp.getString("phase_namefrag_" + rootKey + "_" + idx, "");
        String type   = sp.getString("phase_type_"     + rootKey + "_" + idx, "pve");
        String capRaw = sp.getString("phase_cap_"      + rootKey + "_" + idx, "0");
        String source = sp.getString("phase_source_"   + rootKey + "_" + idx, "manual");

        boolean isPvp  = "pvp".equals(type);
        boolean isAuto = "auto".equals(source);
        boolean noPhCap = capRaw.isEmpty() || parseLong(capRaw) == 0;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = lpW(-1);
        rlp.setMargins(0, dp(4), 0, 0);
        row.setLayoutParams(rlp);
        row.setBackground(roundRect(
            isPvp ? Color.argb(20,255,61,90) : Color.argb(15,0,229,200),
            dp(7),
            isPvp ? Color.argb(60,255,61,90) : Color.argb(40,0,229,200)));
        row.setPadding(dp(7), dp(5), dp(6), dp(5));

        // ── Top line ───────────────────────────────────────────────────────────
        LinearLayout topLine = row(Gravity.CENTER_VERTICAL);

        TextView labelTv = txt(label, 9, true, isPvp ? C_DANGER : C_ACCENT);
        topLine.addView(labelTv, lp0(1));

        // Source badge (AUTO = green-ish, MANUAL = grey)
        TextView srcBadge = chip(isAuto ? "AUTO" : "MANUAL",
            isAuto ? Color.argb(30,59,158,255) : Color.argb(30,107,143,170),
            isAuto ? C_BLUE : C_TEXT2,
            isAuto ? Color.argb(60,59,158,255) : Color.argb(40,107,143,170));
        srcBadge.setTextSize(6);
        srcBadge.setPadding(dp(3), dp(1), dp(3), dp(1));
        LinearLayout.LayoutParams sbLp = lpWH(-2, -2);
        sbLp.setMargins(dp(3), 0, dp(3), 0);
        srcBadge.setLayoutParams(sbLp);
        topLine.addView(srcBadge);

        // PvE/PvP type toggle — tapping flips the type and rebuilds
        final int finalIdx = idx;
        TextView typeBadge = chip(isPvp ? "PvP" : "PvE",
            isPvp ? Color.argb(50,255,61,90) : Color.argb(50,30,240,134),
            isPvp ? C_DANGER : C_OK,
            isPvp ? Color.argb(100,255,61,90) : Color.argb(80,30,240,134));
        typeBadge.setTextSize(7);
        typeBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        typeBadge.setOnClickListener(v -> {
            String cur = sp.getString("phase_type_" + rootKey + "_" + finalIdx, "pve");
            sp.edit().putString("phase_type_" + rootKey + "_" + finalIdx,
                "pvp".equals(cur) ? "pve" : "pvp").apply();
            showMain();
        });
        LinearLayout.LayoutParams tbLp = lpWH(-2, -2);
        tbLp.setMargins(0, 0, dp(5), 0);
        typeBadge.setLayoutParams(tbLp);
        topLine.addView(typeBadge);

        // Delete button
        TextView delBtn = txt("✕", 10, true, C_DANGER);
        delBtn.setPadding(dp(5), dp(2), dp(5), dp(2));
        delBtn.setBackground(roundRect(Color.argb(30,255,61,90), dp(4),
            Color.argb(60,255,61,90)));
        delBtn.setOnClickListener(v ->
            confirmDeletePhase(rootKey, catKey, bossName, finalIdx, totalCount));
        topLine.addView(delBtn, lpWH(-2, -2));

        row.addView(topLine);

        // ── Bottom line ────────────────────────────────────────────────────────
        LinearLayout bottomLine = new LinearLayout(this);
        bottomLine.setOrientation(LinearLayout.HORIZONTAL);
        bottomLine.setPadding(0, dp(3), 0, 0);

        // Name fragment chip — tappable to edit the keyword
        String fragDisplay = frag.isEmpty() ? "tap to set keyword…" : frag;
        TextView fragChip = chip("🔍 " + fragDisplay,
            Color.argb(20,107,143,170), C_TEXT2, Color.argb(40,107,143,170));
        fragChip.setTextSize(7);
        fragChip.setPadding(dp(5), dp(2), dp(5), dp(2));
        fragChip.setOnClickListener(v ->
            showEditFragmentDialog(rootKey, finalIdx, frag));
        bottomLine.addView(fragChip, lp0(1));

        if (!isPvp) {
            // Cap chip — tappable to edit
            TextView capChip = chip(noPhCap ? "No Cap" : capRaw,
                noPhCap ? Color.argb(40,255,176,32) : Color.argb(40,59,158,255),
                noPhCap ? C_AMBER : C_BLUE,
                noPhCap ? Color.argb(100,255,176,32) : Color.argb(80,59,158,255));
            capChip.setTextSize(9);
            capChip.setPadding(dp(9), dp(2), dp(9), dp(2));
            LinearLayout.LayoutParams ccLp = lpWH(-2, -2);
            ccLp.setMargins(dp(4), 0, 0, 0);
            capChip.setLayoutParams(ccLp);
            capChip.setOnClickListener(v ->
                showEditPhaseCapDialog(rootKey, finalIdx, bossName, capRaw));
            bottomLine.addView(capChip);

            // Inline preset buttons
            for (String preset : new String[]{"1b","3b","5b"}) {
                boolean isActive = preset.equals(capRaw);
                TextView pb = chip(preset,
                    isActive ? Color.argb(60,0,229,200) : Color.argb(15,0,229,200),
                    isActive ? Color.WHITE : C_ACCENT,
                    isActive ? Color.argb(180,0,229,200) : Color.argb(40,0,229,200));
                pb.setTextSize(7);
                pb.setPadding(dp(5), dp(2), dp(5), dp(2));
                LinearLayout.LayoutParams plp = lpWH(-2, -2);
                plp.setMargins(dp(3), 0, 0, 0);
                pb.setLayoutParams(plp);
                pb.setOnClickListener(v -> {
                    sp.edit().putString("phase_cap_" + rootKey + "_" + finalIdx, preset).apply();
                    showMain();
                });
                bottomLine.addView(pb);
            }
        } else {
            // PvP note — no cap needed
            TextView pvpNote = txt("PvP — bot skips this phase", 7, false, C_DANGER);
            pvpNote.setPadding(dp(5), 0, 0, 0);
            bottomLine.addView(pvpNote, lp0(1));
        }
        row.addView(bottomLine);
        return row;
    }

    // ── Phase dialogs ──────────────────────────────────────────────────────────

    /** Dialog to manually declare a new phase entry. */
    private void showAddPhaseDialog(String rootKey, String catKey,
                                     String bossName, int currentCount) {
        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackground(roundRect(C_SURFACE, dp(14), C_BORDER2));
        dialogRoot.setPadding(dp(18), dp(16), dp(18), dp(16));

        dialogRoot.addView(txt("Add Phase — " + bossName.split(",")[0].trim(), 15, true, Color.WHITE));
        TextView sub = txt("Enter a keyword from the boss name in this phase", 10, false, C_MUTED);
        sub.setPadding(0, dp(3), 0, dp(10));
        dialogRoot.addView(sub);

        dialogRoot.addView(txt("Phase Label (e.g. 'Phase 3')", 10, true, C_TEXT2));
        EditText labelInput = phaseEditText("Phase " + (currentCount + 1));
        dialogRoot.addView(labelInput);

        LinearLayout.LayoutParams fLp = lpW(-1); fLp.setMargins(0, dp(10), 0, 0);
        dialogRoot.addView(txt("Name Keyword (substring of boss name in this phase)", 10, true, C_TEXT2), fLp);
        EditText fragInput = phaseEditText("e.g. lunar duelist");
        dialogRoot.addView(fragInput);

        LinearLayout.LayoutParams tLp = lpW(-1); tLp.setMargins(0, dp(10), 0, dp(4));
        dialogRoot.addView(txt("Phase Type", 10, true, C_TEXT2), tLp);

        final boolean[] isPvp = {false};
        LinearLayout typeRow = row(0); typeRow.setPadding(0, dp(4), 0, 0);
        TextView pveBtn = chip("⚔ PvE", Color.argb(80,30,240,134), C_OK, Color.argb(120,30,240,134));
        pveBtn.setTextSize(11); pveBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView pvpBtn = chip("🏹 PvP", Color.argb(20,255,61,90), C_TEXT2, Color.argb(40,255,61,90));
        pvpBtn.setTextSize(11); pvpBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams pvpLp = lpWH(-2,-2); pvpLp.setMargins(dp(10),0,0,0);
        pveBtn.setOnClickListener(v -> {
            isPvp[0]=false;
            pveBtn.setBackground(roundRect(Color.argb(80,30,240,134),dp(5),Color.argb(120,30,240,134)));pveBtn.setTextColor(C_OK);
            pvpBtn.setBackground(roundRect(Color.argb(20,255,61,90),dp(5),Color.argb(40,255,61,90)));pvpBtn.setTextColor(C_TEXT2);
        });
        pvpBtn.setOnClickListener(v -> {
            isPvp[0]=true;
            pvpBtn.setBackground(roundRect(Color.argb(80,255,61,90),dp(5),Color.argb(120,255,61,90)));pvpBtn.setTextColor(C_DANGER);
            pveBtn.setBackground(roundRect(Color.argb(20,30,240,134),dp(5),Color.argb(40,30,240,134)));pveBtn.setTextColor(C_TEXT2);
        });
        typeRow.addView(pveBtn, lpWH(-2,-2)); typeRow.addView(pvpBtn, pvpLp);
        dialogRoot.addView(typeRow);

        LinearLayout btnRow = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = lpW(-1); brLp.setMargins(0, dp(16), 0, 0);
        btnRow.setLayoutParams(brLp);
        TextView cancelBtn = txt("Cancel", 12, true, C_TEXT2);
        cancelBtn.setGravity(Gravity.CENTER); cancelBtn.setPadding(dp(16),dp(10),dp(16),dp(10));
        cancelBtn.setBackground(roundRect(Color.TRANSPARENT, dp(8), C_BORDER2));
        TextView saveBtn = txt("Save Phase", 12, true, Color.parseColor("#03090f"));
        saveBtn.setGravity(Gravity.CENTER); saveBtn.setPadding(dp(16),dp(10),dp(16),dp(10));
        GradientDrawable saveBg=new GradientDrawable();saveBg.setColor(C_ACCENT);saveBg.setCornerRadius(dp(8));saveBtn.setBackground(saveBg);
        LinearLayout.LayoutParams sbLp2=lpWH(0,-2);sbLp2.weight=1.5f;sbLp2.setMargins(dp(10),0,0,0);
        btnRow.addView(cancelBtn, lp0(1)); btnRow.addView(saveBtn, sbLp2);
        dialogRoot.addView(btnRow);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogRoot).create();
        if (dialog.getWindow()!=null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        saveBtn.setOnClickListener(v -> {
            String newLabel = labelInput.getText().toString().trim();
            String newFrag  = fragInput.getText().toString().trim().toLowerCase(Locale.US);
            if (newLabel.isEmpty()) newLabel = "Phase " + (currentCount + 1);
            int idx = currentCount;
            sp.edit()
              .putBoolean("phase_boss_"      + rootKey,            true)
              .putString ("phase_label_"    + rootKey + "_" + idx, newLabel)
              .putString ("phase_namefrag_" + rootKey + "_" + idx, newFrag)
              .putString ("phase_type_"     + rootKey + "_" + idx, isPvp[0] ? "pvp" : "pve")
              .putString ("phase_cap_"      + rootKey + "_" + idx, "0")
              .putString ("phase_source_"   + rootKey + "_" + idx, "manual")
              .putInt    ("phase_count_"    + rootKey,            idx + 1)
              .apply();
            dialog.dismiss(); toast("Phase saved"); showMain();
        });
        dialog.show();
        if (dialog.getWindow()!=null)
            dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.9f),
                WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void showEditFragmentDialog(String rootKey, int idx, String current) {
        LinearLayout root = darkDialog();
        root.addView(txt("Edit Phase Keyword", 16, true, Color.WHITE));
        TextView sub = txt("Substring of the boss name during this phase\n(e.g. 'lunar duelist')", 10, false, C_MUTED);
        sub.setSingleLine(false); sub.setPadding(0, dp(4), 0, dp(12)); root.addView(sub);
        EditText input = phaseEditText(current); root.addView(input);
        AlertDialog dialog = darkDialogCreate(root);
        root.addView(darkDialogButtons(dialog, "Cancel", null, "Save", () -> {
            sp.edit()
              .putString("phase_namefrag_" + rootKey + "_" + idx,
                  input.getText().toString().trim().toLowerCase(Locale.US))
              .putString("phase_source_" + rootKey + "_" + idx, "manual")
              .apply();
            toast("Keyword updated"); showMain();
        }));
        dialog.show(); darkDialogSize(dialog);
    }

    private void showEditPhaseCapDialog(String rootKey, int idx,
                                         String bossName, String current) {
        String label = sp.getString("phase_label_" + rootKey + "_" + idx, "Phase "+(idx+1));
        LinearLayout root = darkDialog();
        root.addView(txt("Cap for " + label, 16, true, Color.WHITE));
        TextView sub = txt(bossName.split(",")[0].trim(), 11, false, C_MUTED);
        sub.setPadding(0, dp(3), 0, dp(12)); root.addView(sub);
        EditText input = phaseEditText(current.equals("0") ? "" : current);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("e.g. 5b, 1000000000"); root.addView(input);
        AlertDialog dialog = darkDialogCreate(root);
        root.addView(darkDialogButtons(dialog, "Cancel", null, "Save", () -> {
            String val = input.getText().toString().trim();
            if (val.isEmpty()) val = "0";
            sp.edit().putString("phase_cap_" + rootKey + "_" + idx, val).apply();
            toast("Phase cap updated"); showMain();
        }));
        dialog.show(); darkDialogSize(dialog);
    }

    /**
     * Confirm and delete a phase entry.
     * Shifts subsequent entries down by one index to keep the list compact.
     */
    private void confirmDeletePhase(String rootKey, String catKey,
                                     String bossName, int idx, int totalCount) {
        String label = sp.getString("phase_label_" + rootKey + "_" + idx, "Phase "+(idx+1));
        LinearLayout root = darkDialog();
        root.addView(txt("Delete " + label + "?", 16, true, Color.WHITE));
        TextView sub = txt("Remove phase entry for: " + bossName.split(",")[0].trim()
            + "\n\nAuto-detected phases will be re-learned on the next scan.", 11, false, C_MUTED);
        sub.setSingleLine(false); sub.setPadding(0, dp(6), 0, dp(4)); root.addView(sub);
        AlertDialog dialog = darkDialogCreate(root);
        root.addView(darkDialogButtons(dialog, "Cancel", null, "Delete", () -> {
            SharedPreferences.Editor ed = sp.edit();
            for (int i = idx; i < totalCount - 1; i++) {
                String next = rootKey + "_" + (i+1); String cur = rootKey + "_" + i;
                ed.putString("phase_label_"    + cur, sp.getString("phase_label_"    + next, "Phase "+(i+2)))
                  .putString("phase_namefrag_" + cur, sp.getString("phase_namefrag_" + next, ""))
                  .putString("phase_type_"     + cur, sp.getString("phase_type_"     + next, "pve"))
                  .putString("phase_cap_"      + cur, sp.getString("phase_cap_"      + next, "0"))
                  .putString("phase_source_"   + cur, sp.getString("phase_source_"   + next, "manual"));
            }
            String last = rootKey + "_" + (totalCount-1);
            ed.remove("phase_label_"+last).remove("phase_namefrag_"+last)
              .remove("phase_type_"+last).remove("phase_cap_"+last).remove("phase_source_"+last)
              .putInt("phase_count_"+rootKey, Math.max(0, totalCount-1)).apply();
            toast("Phase deleted"); showMain();
        }));
        dialog.show(); darkDialogSize(dialog);
    }

    // ── Phase helpers ──────────────────────────────────────────────────────────

    /**
     * Resolves the effective cap raw string for progress display.
     * For phase bosses: matches current boss name against stored fragments.
     * Falls back to generic cap for non-phase bosses or unmatched fragments.
     */
    private String resolveEffectiveCapRaw(String rootKey, String bossName, String catKey) {
        if (sp.getBoolean("phase_boss_" + rootKey, false)) {
            String nameLower = bossName.toLowerCase(Locale.US);
            int count = sp.getInt("phase_count_" + rootKey, 0);
            for (int i = 0; i < count; i++) {
                String type = sp.getString("phase_type_" + rootKey + "_" + i, "pve");
                if ("pvp".equals(type)) continue;
                String frag = sp.getString("phase_namefrag_" + rootKey + "_" + i, "")
                               .toLowerCase(Locale.US).trim();
                if (!frag.isEmpty() && nameLower.contains(frag)) {
                    String phaseCap = sp.getString("phase_cap_" + rootKey + "_" + i, "0");
                    if (!phaseCap.isEmpty() && !phaseCap.equals("0")) return phaseCap;
                }
            }
        }
        return sp.getString("cap_" + catKey + "_" + rootKey, "");
    }

    /**
     * Returns a short display label from a full phase name.
     * "Artemis, Lunar Duelist of the Sacred Hunt" → "Lunar Duelist"
     */
    private String shortPhaseLabel(String fullName) {
        if (fullName == null || fullName.isEmpty()) return "";
        String[] parts = fullName.split(",", 2);
        if (parts.length < 2) return fullName;
        String[] words = parts[1].trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(2, words.length); i++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /** Styled EditText for phase dialogs — matches app dark theme. */
    private EditText phaseEditText(String defaultText) {
        EditText et = new EditText(this);
        et.setText(defaultText);
        et.setTextColor(C_TEXT); et.setHintTextColor(C_MUTED);
        et.setSingleLine(true); et.setTextSize(13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD); bg.setCornerRadius(dp(8)); bg.setStroke(dp(1), C_BORDER2);
        et.setBackground(bg); et.setPadding(dp(10), dp(9), dp(10), dp(9));
        LinearLayout.LayoutParams lp = lpW(-1); lp.setMargins(0, dp(4), 0, 0);
        et.setLayoutParams(lp); et.setSelectAllOnFocus(true);
        return et;
    }

    /** parseCap — same logic as service side for consistent display. */
    private long parseCap(String v) {
        if (v==null) return 0;
        String s=v.trim().toLowerCase(Locale.US).replace(",","");
        try { double mult=1; if(s.endsWith("k")){mult=1_000d;s=s.substring(0,s.length()-1);}else if(s.endsWith("m")){mult=1_000_000d;s=s.substring(0,s.length()-1);}else if(s.endsWith("b")){mult=1_000_000_000d;s=s.substring(0,s.length()-1);} return (long)(Double.parseDouble(s)*mult); } catch(Exception e){return 0;}
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ⚔️ MONSTERS TAB — direct battle.php hunts (event bosses etc.)
    // ═══════════════════════════════════════════════════════════════════════════

    private LinearLayout buildMonstersPanel(List<String[]> allBossLines) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = row(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(6), dp(4), dp(10));
        head.addView(txt("⚔️ Direct Monsters", 13, true, C_TEXT), lp0(1));
        TextView addBtn = txt("+ Add Monster", 10, true, C_AMBER);
        addBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        addBtn.setBackground(roundRect(Color.argb(30,255,176,32), dp(8), Color.argb(100,255,176,32)));
        addBtn.setOnClickListener(v -> showAddMonsterDialog());
        head.addView(addBtn, lpWH(-2,-2));
        panel.addView(head);
        panel.addView(divider());
        TextView sub = txt("Paste a battle.php URL to track and auto-attack event monsters directly.", 10, false, C_MUTED);
        sub.setSingleLine(false); sub.setPadding(dp(2), dp(6), dp(2), dp(10));
        panel.addView(sub);
        List<String[]> savedMonsters = loadDirectMonsters();
        if (savedMonsters.isEmpty()) {
            panel.addView(emptyCard("No direct monsters added yet.\n\nTap + Add Monster and paste a battle.php link to get started."));
            return panel;
        }
        Map<String,String[]> liveLookup = new HashMap<>();
        for (String[] p : allBossLines)
            if (p.length > 7 && "monsters".equals(p[7])) liveLookup.put(norm(p[1]), p);
        for (String[] monster : savedMonsters) {
            String prefKey   = monster[0];
            String savedName = monster.length > 1 ? monster[1] : "Direct Monster";
            String monsterId = monster.length > 2 ? monster[2] : "";
            String capRaw    = monster.length > 3 ? monster[3] : "0";
            String[] live    = liveLookup.get(norm(savedName));
            LinearLayout.LayoutParams clp = lpW(-1); clp.setMargins(0, dp(6), 0, 0);
            View card = buildMonsterCard(prefKey, savedName, monsterId, capRaw, live);
            card.setLayoutParams(clp);
            panel.addView(card);
        }
        return panel;
    }

    private View buildMonsterCard(String prefKey, String savedName, String monsterId,
                                   String capRaw, String[] live) {
        final String enableKey = "monster_enabled_" + prefKey;
        final String capKey    = "monster_cap_"     + prefKey;
        boolean enabled  = sp.getBoolean(enableKey, false);
        String status    = live != null && live.length > 2 ? live[2] : "CHECKING";
        String damage    = live != null && live.length > 3 ? live[3] : "0";
        String liveName  = live != null && live.length > 1 && !live[1].isEmpty() ? live[1] : savedName;
        String timer     = live != null && live.length > 8 ? live[8] : "";
        boolean alive    = "ALIVE".equalsIgnoreCase(status);
        long dmgLong     = parseLong(damage);
        String storedCap = sp.getString(capKey, capRaw);
        long capLong     = parseCap(storedCap);
        float pct        = capLong > 0 ? Math.min(1f,(float)dmgLong/capLong) : 0f;
        boolean capped   = capLong > 0 && dmgLong >= capLong;
        boolean noCap    = storedCap.isEmpty() || parseLong(storedCap) == 0;

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10),dp(10),dp(10),dp(10));
        GradientDrawable cardBg = roundRect(alive?Color.parseColor("#0d1e1c"):C_CARD, dp(12),
            alive?Color.argb(180,255,176,32):C_BORDER2);
        if (!enabled) cardBg.setAlpha(120);
        c.setBackground(cardBg);
        if (Build.VERSION.SDK_INT>=21) c.setElevation(dp(1));

        // Name + toggle
        FrameLayout nameFrame = new FrameLayout(this);
        TextView nameTv = txt(liveName,12,true,Color.WHITE); nameTv.setSingleLine(false);
        FrameLayout.LayoutParams nameFLp = new FrameLayout.LayoutParams(-1,-2); nameFLp.rightMargin=dp(50);
        nameTv.setLayoutParams(nameFLp); nameFrame.addView(nameTv);
        Switch sw = new Switch(this); sw.setChecked(enabled); sw.setScaleX(0.72f); sw.setScaleY(0.72f);
        sw.setOnCheckedChangeListener((b,val)->{sp.edit().putBoolean(enableKey,val).apply();cardBg.setAlpha(val?255:120);});
        nameFrame.addView(sw,new FrameLayout.LayoutParams(dp(46),dp(28),Gravity.TOP|Gravity.END));
        c.addView(nameFrame);

        // Status + Direct badge + damage
        LinearLayout r2 = row(Gravity.CENTER_VERTICAL); r2.setPadding(0,dp(5),0,dp(4));
        int pillBg=alive?Color.argb(50,255,176,32):Color.argb(40,60,80,110);
        int pillTxt=alive?C_AMBER:C_MUTED; int pillStr=alive?Color.argb(150,255,176,32):C_BORDER2;
        r2.addView(chip("CHECKING".equals(status)?"…":status.toUpperCase(Locale.US),pillBg,pillTxt,pillStr));
        TextView directBadge=chip("⚔ Direct",Color.argb(30,255,176,32),C_AMBER,Color.argb(80,255,176,32));
        directBadge.setTextSize(7); directBadge.setPadding(dp(5),dp(2),dp(5),dp(2));
        LinearLayout.LayoutParams dbLp=lpWH(-2,-2); dbLp.setMargins(dp(4),0,0,0); directBadge.setLayoutParams(dbLp);
        r2.addView(directBadge);
        TextView dmgTv=txt("  "+fmt(damage),13,true,alive?Color.WHITE:C_MUTED); dmgTv.setSingleLine(true);
        r2.addView(dmgTv,lp0(1)); c.addView(r2);

        // Progress + fraction
        c.addView(buildProgressBar(pct,-1,dp(3)));
        String fracText = capLong<=0?(fmt(damage)+" / No Cap"):(fmt(damage)+" / "+fmt(String.valueOf(capLong))+(capped?" ✓":""));
        TextView frac=txt(fracText,9,false,C_MUTED); frac.setPadding(0,dp(3),0,dp(4)); c.addView(frac);

        // Cap row
        LinearLayout capRow=row(Gravity.CENTER_VERTICAL); capRow.setPadding(0,0,0,dp(3));
        TextView capLbl=txt("Cap",9,true,C_MUTED); capRow.addView(capLbl,lp0(1));
        TextView capChip=chip(noCap?"No Cap":storedCap,
            noCap?Color.argb(40,255,176,32):Color.argb(40,59,158,255),
            noCap?C_AMBER:C_BLUE,
            noCap?Color.argb(120,255,176,32):Color.argb(100,59,158,255));
        capChip.setTextSize(11); capChip.setPadding(dp(14),dp(5),dp(14),dp(5));
        capChip.setOnClickListener(v -> {
            LinearLayout dlgRoot = darkDialog();
            dlgRoot.addView(txt("Damage Cap", 15, true, Color.WHITE));
            TextView dlgSub = txt(liveName, 10, false, C_MUTED);
            dlgSub.setPadding(0, dp(3), 0, dp(10)); dlgRoot.addView(dlgSub);
            EditText dlgInput = phaseEditText(noCap ? "" : storedCap);
            dlgInput.setInputType(InputType.TYPE_CLASS_TEXT);
            dlgInput.setHint("e.g. 5b, 1000000000"); dlgRoot.addView(dlgInput);
            AlertDialog dlg = darkDialogCreate(dlgRoot);
            dlgRoot.addView(darkDialogButtons(dlg, "Cancel", null, "Save", () -> {
                String val = dlgInput.getText().toString().trim();
                if (val.isEmpty()) val = "0";
                sp.edit().putString(capKey, val).apply();
                updateMonsterCapInStorage(prefKey, val);
                toast("Cap updated"); showMain();
            }));
            dlg.show(); darkDialogSize(dlg);
        });
        capRow.addView(capChip,lpWH(-2,-2)); c.addView(capRow);

        // Presets
        LinearLayout presets=row(Gravity.CENTER_VERTICAL); presets.setPadding(0,dp(2),0,0);
        presets.addView(txt("Presets:",8,false,C_MUTED));
        for (String preset:new String[]{"1b","3b","5b"}) {
            boolean isActive=preset.equals(storedCap);
            TextView pb=chip(preset,isActive?Color.argb(60,255,176,32):Color.argb(20,255,176,32),isActive?Color.WHITE:C_AMBER,isActive?Color.argb(180,255,176,32):Color.argb(60,255,176,32));
            pb.setTextSize(9); pb.setPadding(dp(9),dp(3),dp(9),dp(3));
            LinearLayout.LayoutParams plp=lpWH(-2,-2); plp.setMargins(dp(5),0,0,0); pb.setLayoutParams(plp);
            pb.setOnClickListener(v->{sp.edit().putString(capKey,preset).apply();updateMonsterCapInStorage(prefKey,preset);showMain();});
            presets.addView(pb);
        }
        c.addView(presets);

        // Timer row
        boolean hasAutoTimer = timer != null && !timer.isEmpty();
        if (alive || hasAutoTimer) {
            LinearLayout timerRow = row(Gravity.CENTER_VERTICAL);
            timerRow.setPadding(0, dp(6), 0, dp(2));
            String timerDisplay = alive && hasAutoTimer ? "⏳ " + timer : alive ? "⏳ Alive" : timer;
            TextView timerTv = txt(timerDisplay, 10, false, hasAutoTimer ? C_AMBER : C_MUTED);
            timerTv.setSingleLine(true);
            timerRow.addView(timerTv, lp0(1));
            c.addView(timerRow);
        }

        // Bottom: ID chip + delete
        LinearLayout bottomRow=row(Gravity.CENTER_VERTICAL); bottomRow.setPadding(0,dp(8),0,dp(2));
        TextView idChip=chip("🔗 id: "+monsterId,Color.argb(20,107,143,170),C_TEXT2,Color.argb(40,107,143,170));
        idChip.setTextSize(8); idChip.setPadding(dp(6),dp(3),dp(6),dp(3));
        idChip.setOnLongClickListener(v->{
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("url","https://demonicscans.org/battle.php?id="+monsterId));
            toast("URL copied"); return true;
        });
        bottomRow.addView(idChip,lp0(1));
        TextView delBtn=txt("🗑 Remove",9,true,C_DANGER); delBtn.setPadding(dp(8),dp(4),dp(8),dp(4));
        delBtn.setBackground(roundRect(Color.argb(30,255,61,90),dp(6),Color.argb(80,255,61,90)));
        delBtn.setOnClickListener(v->confirmDeleteMonster(prefKey,liveName));
        LinearLayout.LayoutParams dlp=lpWH(-2,-2); dlp.setMargins(dp(8),0,0,0); delBtn.setLayoutParams(dlp);
        bottomRow.addView(delBtn); c.addView(bottomRow);
        return c;
    }

    private void showAddMonsterDialog() {
        LinearLayout dialogRoot=new LinearLayout(this); dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setBackground(roundRect(C_SURFACE,dp(16),C_BORDER2)); dialogRoot.setPadding(dp(18),dp(18),dp(18),dp(16));
        LinearLayout titleRow=row(Gravity.CENTER_VERTICAL);
        titleRow.addView(txt("⚔️",20,false,Color.WHITE));
        titleRow.addView(txt("  Add Direct Monster",16,true,Color.WHITE),lp0(1));
        dialogRoot.addView(titleRow);
        TextView sub=txt("Paste the battle.php link of any monster.\nThe bot will track and attack it directly.",10,false,C_MUTED);
        sub.setSingleLine(false); sub.setPadding(0,dp(4),0,dp(14)); dialogRoot.addView(sub);
        dialogRoot.addView(txt("🔗 Battle URL",10,true,C_TEXT2));
        EditText urlInput=phaseEditText("https://demonicscans.org/battle.php?id=");
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        dialogRoot.addView(urlInput);
        TextView idPreview=txt("Monster ID: —",9,false,C_MUTED); idPreview.setPadding(0,dp(4),0,dp(10));
        dialogRoot.addView(idPreview);
        urlInput.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            @Override public void onTextChanged(CharSequence s,int st,int b,int c){}
            @Override public void afterTextChanged(android.text.Editable s){
                String ex=extractMonsterIdFromUrl(s.toString());
                idPreview.setText(ex.isEmpty()?"Monster ID: —":"Monster ID: "+ex);
                idPreview.setTextColor(ex.isEmpty()?C_MUTED:C_OK);
            }
        });
        LinearLayout.LayoutParams nLp=lpW(-1); nLp.setMargins(0,dp(8),0,0);
        dialogRoot.addView(txt("📝 Display Name (optional)",10,true,C_TEXT2),nLp);
        EditText nameInput=phaseEditText(""); nameInput.setHint("e.g. Grathmor Event Boss"); dialogRoot.addView(nameInput);
        LinearLayout.LayoutParams cLp=lpW(-1); cLp.setMargins(0,dp(8),0,0);
        dialogRoot.addView(txt("💥 Damage Cap (optional)",10,true,C_TEXT2),cLp);
        EditText capInput=phaseEditText(""); capInput.setInputType(InputType.TYPE_CLASS_TEXT);
        capInput.setHint("e.g. 5b, 1000000000"); dialogRoot.addView(capInput);
        LinearLayout btnRow=row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp=lpW(-1); brLp.setMargins(0,dp(18),0,0); btnRow.setLayoutParams(brLp);
        TextView cancelBtn=txt("Cancel",12,true,C_TEXT2); cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(dp(16),dp(10),dp(16),dp(10)); cancelBtn.setBackground(roundRect(Color.TRANSPARENT,dp(8),C_BORDER2));
        TextView saveBtn=txt("Add Monster",12,true,Color.parseColor("#03090f")); saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(dp(16),dp(10),dp(16),dp(10));
        GradientDrawable saveBg=new GradientDrawable(); saveBg.setColor(C_AMBER); saveBg.setCornerRadius(dp(8));
        saveBtn.setBackground(saveBg);
        LinearLayout.LayoutParams sbLp=lpWH(0,-2); sbLp.weight=1.5f; sbLp.setMargins(dp(10),0,0,0);
        btnRow.addView(cancelBtn,lp0(1)); btnRow.addView(saveBtn,sbLp); dialogRoot.addView(btnRow);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(dialogRoot).create();
        if(dialog.getWindow()!=null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        cancelBtn.setOnClickListener(v->dialog.dismiss());
        saveBtn.setOnClickListener(v->{
            String url=urlInput.getText().toString().trim();
            String monsterId=extractMonsterIdFromUrl(url);
            String name=nameInput.getText().toString().trim();
            String cap=capInput.getText().toString().trim();
            if(monsterId.isEmpty()){toast("Could not extract Monster ID from URL");return;}
            if(name.isEmpty()) name="Monster "+monsterId;
            if(cap.isEmpty()) cap="0";
            for(String[] m:loadDirectMonsters()) if(m.length>2&&m[2].equals(monsterId)){toast("Monster already added");return;}
            String pk="dm_"+monsterId+"_"+System.currentTimeMillis()%10000;
            sp.edit().putBoolean("monster_enabled_"+pk,true).apply();
            List<String[]> monsters=loadDirectMonsters(); monsters.add(new String[]{pk,name,monsterId,cap});
            saveDirectMonsters(monsters);
            startService(new Intent(this,BotForegroundService.class).setAction(BotForegroundService.ACTION_RELOAD_MONSTERS));
            dialog.dismiss(); activeTab=allWaveTabs.size()+1; toast("Monster added!"); showMain();
        });
        dialog.show();
        if(dialog.getWindow()!=null) dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.92f),WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private void confirmDeleteMonster(String prefKey, String name) {
        LinearLayout root = darkDialog();
        root.addView(txt("Remove Monster?", 16, true, Color.WHITE));
        TextView sub = txt("Remove \"" + name + "\" from direct hunt list?\nThis won't affect in-game progress.", 11, false, C_MUTED);
        sub.setSingleLine(false); sub.setPadding(0, dp(6), 0, dp(4)); root.addView(sub);
        AlertDialog dialog = darkDialogCreate(root);
        root.addView(darkDialogButtons(dialog, "Cancel", null, "Remove", () -> {
            List<String[]> monsters = loadDirectMonsters();
            monsters.removeIf(m -> m[0].equals(prefKey));
            saveDirectMonsters(monsters);
            sp.edit().remove("monster_enabled_"+prefKey).remove("monster_cap_"+prefKey).apply();
            toast("Monster removed"); showMain();
        }));
        dialog.show(); darkDialogSize(dialog);
    }
    private String extractMonsterIdFromUrl(String url){if(url==null||url.isEmpty())return "";Matcher m=Pattern.compile("[?&]id=([0-9]+)").matcher(url);return m.find()?m.group(1):"";}
    private List<String[]> loadDirectMonsters(){List<String[]> list=new ArrayList<>();String raw=sp.getString(PREF_DIRECT_MONSTERS,"");if(raw==null||raw.trim().isEmpty())return list;for(String line:raw.split("\n")){String[]parts=line.split("\\|",-1);if(parts.length>=3&&!parts[2].trim().isEmpty())list.add(parts);}return list;}
    private void saveDirectMonsters(List<String[]> monsters){StringBuilder sb=new StringBuilder();for(String[]m:monsters){if(sb.length()>0)sb.append("\n");sb.append(String.join("|",(CharSequence[])m));}sp.edit().putString(PREF_DIRECT_MONSTERS,sb.toString()).apply();}
    private void updateMonsterCapInStorage(String prefKey,String newCap){List<String[]> monsters=loadDirectMonsters();for(String[]m:monsters)if(m[0].equals(prefKey)&&m.length>3)m[3]=newCap;saveDirectMonsters(monsters);}
    private String monsterAliveCount(){List<String[]> lines=parseBossLines(sp.getString("last_bosses",""));long alive=0;for(String[]p:lines)if(p.length>7&&"monsters".equals(p[7])&&p.length>2&&"ALIVE".equalsIgnoreCase(p[2]))alive++;return alive>0?alive+" ⚡":loadDirectMonsters().size()+"";}

    // ═══════════════════════════════════════════════════════════════════════════
    //  LOGS PANEL
    // ═══════════════════════════════════════════════════════════════════════════
    private LinearLayout buildLogsPanel() {
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head=row(Gravity.CENTER_VERTICAL);head.setPadding(dp(4),dp(6),dp(4),dp(10));
        TextView title=txt("Activity Log",12,true,C_TEXT2);if(Build.VERSION.SDK_INT>=21)title.setLetterSpacing(0.06f);head.addView(title,lp0(1));
        head.addView(smallBtn("Copy",C_BLUE,v->copyLogs()));head.addView(smallBtn("Clear",C_DANGER,v->clearLogs()));panel.addView(head);
        String raw=sp.getString("logs","");
        if(raw.isEmpty()){panel.addView(emptyCard("No logs yet."));}
        else{String[] lines=raw.split("\n");int start=Math.max(0,lines.length-80);for(int i=start;i<lines.length;i++)panel.addView(buildLogRow(levelOf(lines[i]),msgOf(lines[i])));}
        return panel;
    }
    private View buildLogRow(String level,String msg){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(dp(10),dp(7),dp(10),dp(7));LinearLayout.LayoutParams rlp=lpW(-1);rlp.setMargins(0,dp(3),0,0);row.setLayoutParams(rlp);row.setBackground(roundRect(Color.parseColor("#050e1d"),dp(8),C_BORDER));boolean isErr="ERROR".equalsIgnoreCase(level)||"ERR".equalsIgnoreCase(level);TextView tag=chip(isErr?"ERR":"INFO",isErr?Color.argb(40,255,61,90):Color.argb(30,59,158,255),isErr?C_DANGER:C_BLUE,isErr?Color.argb(100,255,61,90):Color.argb(80,59,158,255));tag.setTextSize(8);tag.setPadding(dp(5),dp(2),dp(5),dp(2));LinearLayout.LayoutParams tlp=lpWH(dp(36),-2);tlp.setMargins(0,0,dp(8),0);tlp.gravity=Gravity.TOP;row.addView(tag,tlp);TextView msgTv=txt(msg,11,false,C_TEXT);msgTv.setMaxLines(3);row.addView(msgTv,lp0(1));return row;}

    // ── Bottom nav ─────────────────────────────────────────────────────────────
    private View buildBottomNav() {
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable fade=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{Color.TRANSPARENT,C_BG,C_BG});nav.setBackground(fade);nav.setPadding(dp(12),dp(8),dp(12),dp(20));
        LinearLayout buttons=new LinearLayout(this);buttons.setOrientation(LinearLayout.HORIZONTAL);
        boolean running=sp.getBoolean("global_enabled",false);boolean conn=isConnected();
        View logoutBtn=navBtn(conn?"Logout":"Login",Color.parseColor("#0a1422"),C_MUTED,C_BORDER2,v->{if(conn)logout();else showLogin();});
        View configBtn=navBtn("⚙ Config",Color.parseColor("#0a2828"),C_ACCENT,Color.argb(120,0,229,200),v->showConfig());
        View actionBtn=navBtn(running?"■ Stop":"▶ Start",running?Color.parseColor("#2e0c18"):Color.parseColor("#082814"),running?C_DANGER:C_OK,running?Color.argb(120,255,61,90):Color.argb(120,30,240,134),v->toggleBot());
        LinearLayout.LayoutParams bp=lp0(1);bp.setMargins(dp(4),0,dp(4),0);buttons.addView(logoutBtn,bp);buttons.addView(configBtn,new LinearLayout.LayoutParams(0,dp(48),1));buttons.addView(actionBtn,new LinearLayout.LayoutParams(0,dp(48),1));
        ((LinearLayout.LayoutParams)logoutBtn.getLayoutParams()).height=dp(48);
        nav.addView(buttons,lpW(-1));return nav;
    }
    private View navBtn(String label,int bg,int textColor,int stroke,View.OnClickListener l){TextView b=txt(label,11,true,textColor);b.setGravity(Gravity.CENTER);b.setBackground(roundRect(bg,dp(10),stroke));b.setOnClickListener(l);b.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_DOWN)v.setAlpha(0.75f);else if(e.getAction()==MotionEvent.ACTION_UP||e.getAction()==MotionEvent.ACTION_CANCEL)v.setAlpha(1f);return false;});return b;}

    // ═══════════════════════════════════════════════════════════════════════════
    //  BOT TOGGLE / CONFIG / LOGIN
    // ═══════════════════════════════════════════════════════════════════════════
    private void toggleBot(){if(sp.getBoolean("global_enabled",false)){sp.edit().putBoolean("global_enabled",false).apply();startService(new Intent(this,BotForegroundService.class).setAction(BotForegroundService.ACTION_STOP));toast("Bot stopped");}else{requestIgnoreBatteryOptimizationsOnce();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},99);sp.edit().putBoolean("global_enabled",true).apply();Intent in=new Intent(this,BotForegroundService.class).setAction(BotForegroundService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(in);else startService(in);toast("Bot started");}showMain();}
    private void logout(){sp.edit().putBoolean("connected",false).putBoolean("global_enabled",false).apply();startService(new Intent(this,BotForegroundService.class).setAction(BotForegroundService.ACTION_STOP));try{CookieManager.getInstance().removeAllCookies(null);CookieManager.getInstance().flush();}catch(Exception ignored){}toast("Logged out");showMain();}

    private void editBossCap(String catKey, String name, String current) {
        String key = "cap_" + catKey + "_" + bossRootKey(name);
        LinearLayout root = darkDialog();
        root.addView(txt("Damage Cap", 16, true, Color.WHITE));
        TextView sub = txt(name, 11, false, C_MUTED); sub.setPadding(0, dp(3), 0, dp(10)); root.addView(sub);
        EditText input = phaseEditText(sp.getString(key, current));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("e.g. 5b, 1000000000"); root.addView(input);
        AlertDialog dialog = darkDialogCreate(root);
        root.addView(darkDialogButtons(dialog, "Cancel", null, "Save", () -> {
            String v = input.getText().toString().trim();
            if (v.isEmpty()) v = "0";
            sp.edit().putString(key, v).apply();
            toast("Cap updated"); showMain();
        }));
        dialog.show(); darkDialogSize(dialog);
    }

    private void showLogin(){currentScreen="login";destroyLogin();root.removeAllViews();LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setBackgroundColor(C_BG);root.addView(box,new FrameLayout.LayoutParams(-1,-1));LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);top.setPadding(dp(8),statusBarHeight()+dp(4),dp(8),dp(6));top.setBackgroundColor(C_SURFACE);top.setGravity(Gravity.CENTER_VERTICAL);box.addView(top,lpWH(-1,-2));top.addView(navBtn("← Back",C_SURFACE,C_MUTED,C_BORDER2,v->showMain()),lpWH(0,dp(42)));((LinearLayout.LayoutParams)top.getChildAt(0).getLayoutParams()).weight=1;top.addView(navBtn("Reload",C_SURFACE,C_BLUE,C_BORDER2,v->{if(loginWeb!=null)loginWeb.reload();}),lpWH(0,dp(42)));((LinearLayout.LayoutParams)top.getChildAt(1).getLayoutParams()).weight=1;top.addView(navBtn(isConnected()?"✓ Connected":"Save & Connect",C_SURFACE,isConnected()?C_OK:C_ACCENT,C_BORDER2,v->{CookieManager.getInstance().flush();sp.edit().putBoolean("connected",isConnected()).apply();toast(isConnected()?"Connected cookies saved":"No login cookie detected yet");showLogin();}),lpWH(0,dp(42)));((LinearLayout.LayoutParams)top.getChildAt(2).getLayoutParams()).weight=1;loginWeb=new WebView(this);WebSettings s=loginWeb.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setDatabaseEnabled(true);s.setLoadsImagesAutomatically(true);s.setUseWideViewPort(true);s.setLoadWithOverviewMode(true);if(Build.VERSION.SDK_INT>=21){s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);CookieManager.getInstance().setAcceptThirdPartyCookies(loginWeb,true);}CookieManager.getInstance().setAcceptCookie(true);loginWeb.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView v,String u){CookieManager.getInstance().flush();}});box.addView(loginWeb,new LinearLayout.LayoutParams(-1,0,1));loginWeb.loadUrl("https://demonicscans.org/");}

    private void showConfig(){currentScreen="config";destroyLogin();root.removeAllViews();root.setBackgroundColor(C_BG);ScrollView sv=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(12),statusBarHeight()+dp(12),dp(12),dp(24));box.addView(txt("Configuration",22,true,C_TEXT));TextView sub=txt("All settings saved instantly",12,false,C_MUTED);sub.setPadding(0,0,0,dp(14));box.addView(sub);box.addView(navBtn("← Back to Main",C_SURFACE,C_MUTED,C_BORDER2,v->showMain()),lpWH(-1,dp(46)));addSwitch(box,"Global ON/OFF","global_enabled");addSwitch(box,"Enable Grakthar","enable_grakthar");addSwitch(box,"Enable Olympus W9","enable_olympus");addSwitch(box,"Enable Hermes","enable_hermes");for(String[] w:loadCustomWaves())addSwitch(box,"Enable "+w[1],"enable_"+w[0]);addSwitch(box,"Smart delay / jitter","smart_delay");addSwitch(box,"Vibrate + sound alerts","alerts");addSwitch(box,"Auto stamina potion","auto_potion");addSwitch(box,"Asterion computation","enable_asterion");addSwitch(box,"Dark theme","dark");addSkillPicker(box);addEdit(box,"Scan interval (seconds)","scan_interval");addEdit(box,"Asterion stamina threshold","asterion_stamina_threshold");addEdit(box,"LSP limit","lsp_limit");addEdit(box,"FSP limit","fsp_limit");addEdit(box,"HP potion limit","hp_limit");box.addView(navBtn("Open Battery Settings",C_SURFACE,C_AMBER,C_BORDER2,v->{try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}}),lpWH(-1,dp(46)));sv.addView(box,new ScrollView.LayoutParams(-1,-2));root.addView(sv,new FrameLayout.LayoutParams(-1,-1));}

    private void addSkillPicker(LinearLayout box){LinearLayout s=configSection("Skill selection");final String[][] skills={{"Worldbreaker Slash","-5","1000"},{"Ultimate Slash","-4","200"},{"Heroic Slash","-2","50"},{"Power Slash","-1","10"},{"Slash","0","1"}};RadioGroup group=new RadioGroup(this);group.setOrientation(RadioGroup.VERTICAL);String current=sp.getString("skill_id","0");for(String[] sk:skills){RadioButton rb=new RadioButton(this);rb.setText(sk[0]+"  •  id="+sk[1]+"  •  sta="+sk[2]);rb.setTextColor(C_TEXT);rb.setTextSize(13);rb.setId(sk[1].hashCode());rb.setChecked(sk[1].equals(current));group.addView(rb);}group.setOnCheckedChangeListener((g,id)->{for(String[] sk:skills)if(sk[1].hashCode()==id)sp.edit().putString("skill_id",sk[1]).apply();});s.addView(group);box.addView(s);}
    private void addSwitch(LinearLayout box,String label,String key){LinearLayout s=configSection(null);Switch sw=new Switch(this);sw.setText(label);sw.setTextSize(15);sw.setTextColor(C_TEXT);sw.setChecked(sp.getBoolean(key,false));sw.setOnCheckedChangeListener((b,v)->sp.edit().putBoolean(key,v).apply());s.addView(sw);box.addView(s);}
    private void addEdit(LinearLayout box,String label,String key){LinearLayout s=configSection(null);s.addView(txt(label,12,true,C_MUTED));EditText e=new EditText(this);e.setText(sp.getString(key,""));e.setTextColor(C_TEXT);e.setHintTextColor(C_MUTED);e.setSingleLine(true);e.setOnFocusChangeListener((v,has)->{if(!has)sp.edit().putString(key,((EditText)v).getText().toString()).apply();});s.addView(e);box.addView(s);}
    private LinearLayout configSection(String title){LinearLayout s=new LinearLayout(this);s.setOrientation(LinearLayout.VERTICAL);s.setPadding(dp(12),dp(10),dp(12),dp(10));s.setBackground(roundRect(C_CARD,dp(10),C_BORDER2));LinearLayout.LayoutParams lp=lpW(-1);lp.setMargins(0,dp(6),0,0);s.setLayoutParams(lp);if(title!=null)s.addView(txt(title,14,true,C_TEXT));return s;}

    // ═══════════════════════════════════════════════════════════════════════════
    //  CUSTOM WAVE MANAGEMENT (unchanged)
    // ═══════════════════════════════════════════════════════════════════════════
    private List<String[]> buildAllWaveTabs() {
        List<String[]> list = new ArrayList<>();
        for (String[] b : BUILTIN_WAVES) list.add(new String[]{b[0], b[1], b[2], waveIcon(b[0])});
        for (String[] c : loadCustomWaves()) list.add(c);
        return list;
    }
    private List<String[]> loadCustomWaves(){List<String[]> list=new ArrayList<>();String raw=sp.getString(PREF_CUSTOM_WAVES,"");if(raw==null||raw.isEmpty())return list;for(String line:raw.split("\n")){String[] parts=line.split("\\|",-1);if(parts.length>=3&&!parts[2].isEmpty())list.add(parts);}return list;}
    private void saveCustomWaves(List<String[]> waves){StringBuilder sb=new StringBuilder();for(String[] w:waves){if(sb.length()>0)sb.append("\n");sb.append(String.join("|",(CharSequence[])w));}sp.edit().putString(PREF_CUSTOM_WAVES,sb.toString()).apply();startService(new Intent(this,BotForegroundService.class).setAction(BotForegroundService.ACTION_RELOAD_WAVES));}
    private String customWavePrefKey(String title){return "custom_"+norm(title)+"_"+System.currentTimeMillis();}

    private void showAddWaveDialog() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg=new GradientDrawable();rootBg.setColor(C_SURFACE);rootBg.setCornerRadius(dp(18));rootBg.setStroke(dp(1),C_BORDER2);root.setBackground(rootBg);if(Build.VERSION.SDK_INT>=21)root.setClipToOutline(true);
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setPadding(dp(20),dp(18),dp(20),dp(14));GradientDrawable headerBg=new GradientDrawable(GradientDrawable.Orientation.BL_TR,new int[]{Color.parseColor("#061e2a"),Color.parseColor("#042818")});float[]radii={dp(18),dp(18),dp(18),dp(18),0,0,0,0};headerBg.setCornerRadii(radii);header.setBackground(headerBg);
        LinearLayout titleRow=row(Gravity.CENTER_VERTICAL);titleRow.addView(txt("🌊",20,false,Color.WHITE));titleRow.addView(txt("  Add New Wave",16,true,Color.WHITE),lp0(1));header.addView(titleRow);
        TextView dlgSub=txt("Set up a new boss wave tab",11,false,C_TEXT2);dlgSub.setPadding(0,dp(3),0,0);header.addView(dlgSub);root.addView(header);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),dp(14),dp(18),dp(6));
        LinearLayout previewRow=row(Gravity.CENTER_VERTICAL);previewRow.setPadding(0,0,0,dp(14));previewRow.addView(txt("Preview:",10,true,C_MUTED));final TextView previewChip=chip("⚡  New Wave",Color.argb(40,0,229,200),C_ACCENT,Color.argb(120,0,229,200));previewChip.setTextSize(11);previewChip.setPadding(dp(12),dp(6),dp(12),dp(6));LinearLayout.LayoutParams pclp=lpWH(-2,-2);pclp.setMargins(dp(8),0,0,0);previewChip.setLayoutParams(pclp);previewRow.addView(previewChip);form.addView(previewRow);
        form.addView(txt("🎯  Tab Icon (emoji)",11,true,C_TEXT2));
        EditText emojiInput=new EditText(this);emojiInput.setText("⚡");emojiInput.setTextColor(C_TEXT);emojiInput.setHintTextColor(C_MUTED);emojiInput.setHint("e.g.  ⚔️  🔥  💀  🌊");emojiInput.setSingleLine(true);emojiInput.setTextSize(18);emojiInput.setBackground(waveFieldBg(false));emojiInput.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams eilp=lpW(-1);eilp.setMargins(0,dp(4),0,dp(14));emojiInput.setLayoutParams(eilp);form.addView(emojiInput);
        form.addView(txt("📝  Wave Title",11,true,C_TEXT2));
        EditText titleInput=new EditText(this);titleInput.setHint("e.g.  Oly W12");titleInput.setTextColor(C_TEXT);titleInput.setHintTextColor(C_MUTED);titleInput.setSingleLine(true);titleInput.setTextSize(14);titleInput.setBackground(waveFieldBg(false));titleInput.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams tilp=lpW(-1);tilp.setMargins(0,dp(4),0,dp(14));titleInput.setLayoutParams(tilp);form.addView(titleInput);
        form.addView(txt("🔗  Wave URL",11,true,C_TEXT2));
        EditText urlInput=new EditText(this);urlInput.setHint("https://demonicscans.org/active_wave.php?gate=5&wave=12");urlInput.setTextColor(C_TEXT);urlInput.setHintTextColor(C_MUTED);urlInput.setSingleLine(true);urlInput.setTextSize(11);urlInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);urlInput.setBackground(waveFieldBg(false));urlInput.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams uilp=lpW(-1);uilp.setMargins(0,dp(4),0,dp(4));urlInput.setLayoutParams(uilp);form.addView(urlInput);
        root.addView(form);root.addView(divider());
        LinearLayout btnRow=row(Gravity.CENTER_VERTICAL);btnRow.setPadding(dp(16),dp(12),dp(16),dp(18));
        TextView cancelBtn=txt("Cancel",13,true,C_TEXT2);cancelBtn.setGravity(Gravity.CENTER);cancelBtn.setPadding(dp(16),dp(12),dp(16),dp(12));cancelBtn.setBackground(roundRect(Color.TRANSPARENT,dp(10),C_BORDER2));LinearLayout.LayoutParams cblp=new LinearLayout.LayoutParams(0,dp(46),1f);cancelBtn.setLayoutParams(cblp);
        LinearLayout.LayoutParams ablp=new LinearLayout.LayoutParams(0,dp(46),1.6f);ablp.setMargins(dp(10),0,0,0);GradientDrawable addBtnBg=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{Color.parseColor("#00c4a8"),C_ACCENT});addBtnBg.setCornerRadius(dp(10));TextView addBtn=txt("✦  Add Wave",13,true,Color.parseColor("#03090f"));addBtn.setGravity(Gravity.CENTER);addBtn.setBackground(addBtnBg);addBtn.setLayoutParams(ablp);
        btnRow.addView(cancelBtn);btnRow.addView(addBtn);root.addView(btnRow);
        android.text.TextWatcher pw=new android.text.TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int st,int c,int a){}@Override public void onTextChanged(CharSequence s,int st,int b,int c){}@Override public void afterTextChanged(android.text.Editable s){String e=emojiInput.getText().toString().trim();String t=titleInput.getText().toString().trim();if(e.isEmpty())e="⚡";previewChip.setText(t.isEmpty()?e+"  New Wave":e+"  "+t);}};
        emojiInput.addTextChangedListener(pw);titleInput.addTextChangedListener(pw);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(root).create();
        if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        cancelBtn.setOnClickListener(v->dialog.dismiss());
        addBtn.setOnClickListener(v->{String emoji=emojiInput.getText().toString().trim();String title=titleInput.getText().toString().trim();String url=urlInput.getText().toString().trim();if(title.isEmpty()){toast("Title is required");return;}if(url.isEmpty()||!url.startsWith("http")){toast("Enter a valid URL");return;}if(emoji.isEmpty())emoji="⚡";for(String[] w:loadCustomWaves())if(w[1].equalsIgnoreCase(title)){toast("A wave with this title already exists");return;}String prefKey=customWavePrefKey(title);sp.edit().putBoolean("enable_"+prefKey,true).apply();List<String[]> customs=loadCustomWaves();customs.add(new String[]{prefKey,title,url,emoji});saveCustomWaves(customs);activeTab=1+allWaveTabs.size();dialog.dismiss();toast("Wave added!");showMain();});
        dialog.show();if(dialog.getWindow()!=null)dialog.getWindow().setLayout((int)(getResources().getDisplayMetrics().widthPixels*0.92f),WindowManager.LayoutParams.WRAP_CONTENT);
    }
    private GradientDrawable waveFieldBg(boolean focused){GradientDrawable g=new GradientDrawable();g.setColor(C_CARD);g.setCornerRadius(dp(10));g.setStroke(dp(1),focused?C_ACCENT:C_BORDER2);return g;}
    private void showDeleteWaveDialog(String[] wave,boolean isBuiltin){if(isBuiltin){new AlertDialog.Builder(this).setTitle("Built-in Wave").setMessage("\""+wave[1]+"\" is a built-in wave and cannot be deleted.").setPositiveButton("OK",null).show();return;}new AlertDialog.Builder(this).setTitle("Delete Wave").setMessage("Remove \""+wave[1]+"\" tab?\n\nBoss toggles and caps for this wave will also be cleared.").setPositiveButton("Delete",(d,w)->{List<String[]> customs=loadCustomWaves();customs.removeIf(c->c[0].equals(wave[0]));saveCustomWaves(customs);SharedPreferences.Editor ed=sp.edit();Map<String,?> allPrefs=sp.getAll();for(String key:allPrefs.keySet()){if(key.startsWith("boss_enabled_"+wave[0])||key.startsWith("cap_"+wave[0]))ed.remove(key);}ed.remove("enable_"+wave[0]);String existing=sp.getString("last_bosses","");if(!existing.isEmpty()){StringBuilder cleaned=new StringBuilder();for(String line:existing.split("\n")){String[]parts=line.split("\\|",-1);if(parts.length>0&&wave[1].equalsIgnoreCase(parts[0].trim()))continue;if(cleaned.length()>0)cleaned.append("\n");cleaned.append(line);}ed.putString("last_bosses",cleaned.toString());}ed.apply();activeTab=0;toast("Wave removed");showMain();}).setNegativeButton("Cancel",null).show();}
    private GradientDrawable inputBg(){return roundRect(C_CARD,dp(8),C_BORDER2);}

    // ═══════════════════════════════════════════════════════════════════════════
    //  DARK DIALOG HELPERS — consistent dark-themed dialogs across the app
    // ═══════════════════════════════════════════════════════════════════════════

    /** Creates a dark-themed dialog root layout. */
    private LinearLayout darkDialog() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundRect(C_SURFACE, dp(16), C_BORDER2));
        root.setPadding(dp(20), dp(20), dp(20), dp(16));
        return root;
    }

    /** Creates and configures a transparent-background AlertDialog from a view. */
    private AlertDialog darkDialogCreate(View content) {
        AlertDialog d = new AlertDialog.Builder(this).setView(content).create();
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    /** Sets dialog width to 92% of screen. */
    private void darkDialogSize(AlertDialog d) {
        if (d.getWindow() != null)
            d.getWindow().setLayout(
                (int)(getResources().getDisplayMetrics().widthPixels * 0.92f),
                WindowManager.LayoutParams.WRAP_CONTENT);
    }

    /**
     * Builds a Cancel + action button row for dark dialogs.
     * cancelLabel/cancelAction: null = just dismiss.
     * actionLabel/actionRunnable: the positive action.
     * Action button is red for destructive actions (Delete/Remove), teal otherwise.
     */
    private LinearLayout darkDialogButtons(AlertDialog dialog,
                                            String cancelLabel, Runnable cancelAction,
                                            String actionLabel, Runnable actionRunnable) {
        LinearLayout btnRow = row(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = lpW(-1); brLp.setMargins(0, dp(18), 0, 0);
        btnRow.setLayoutParams(brLp);

        TextView cancelBtn = txt(cancelLabel != null ? cancelLabel : "Cancel", 12, true, C_TEXT2);
        cancelBtn.setGravity(Gravity.CENTER);
        cancelBtn.setPadding(dp(16), dp(11), dp(16), dp(11));
        cancelBtn.setBackground(roundRect(Color.TRANSPARENT, dp(9), C_BORDER2));
        cancelBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (cancelAction != null) cancelAction.run();
        });

        boolean isDestructive = actionLabel != null &&
            (actionLabel.toLowerCase(Locale.US).contains("delete")
            || actionLabel.toLowerCase(Locale.US).contains("remove"));
        int actionColor = isDestructive ? C_DANGER : C_ACCENT;
        int actionTextColor = isDestructive ? Color.WHITE : Color.parseColor("#03090f");

        TextView actionBtn = txt(actionLabel != null ? actionLabel : "OK", 12, true, actionTextColor);
        actionBtn.setGravity(Gravity.CENTER);
        actionBtn.setPadding(dp(16), dp(11), dp(16), dp(11));
        GradientDrawable actionBg = new GradientDrawable();
        actionBg.setColor(actionColor); actionBg.setCornerRadius(dp(9));
        actionBtn.setBackground(actionBg);
        actionBtn.setOnClickListener(v -> {
            dialog.dismiss();
            if (actionRunnable != null) actionRunnable.run();
        });

        LinearLayout.LayoutParams abLp = lpWH(0, -2);
        abLp.weight = 1.4f; abLp.setMargins(dp(10), 0, 0, 0);

        btnRow.addView(cancelBtn, lp0(1));
        btnRow.addView(actionBtn, abLp);
        return btnRow;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPERS — views & misc
    // ═══════════════════════════════════════════════════════════════════════════
    private View buildProgressBar(float pct,int width,int height){pct=Math.max(0f,Math.min(1f,pct));LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams wlp=new LinearLayout.LayoutParams(width<0?-1:width,height);wlp.setMargins(0,dp(4),0,0);bar.setLayoutParams(wlp);GradientDrawable trackBg=new GradientDrawable();trackBg.setColor(Color.argb(40,255,255,255));trackBg.setCornerRadius(dp(2));if(pct>0f){GradientDrawable fd=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,pct>=1f?new int[]{C_OK,Color.parseColor("#00ffaa")}:new int[]{C_BLUE,C_ACCENT});fd.setCornerRadius(dp(2));View fill=new View(this);fill.setBackground(fd);bar.addView(fill,new LinearLayout.LayoutParams(0,height,pct));}if(pct<1f){View track=new View(this);track.setBackground(trackBg);bar.addView(track,new LinearLayout.LayoutParams(0,height,1f-pct));}return bar;}
    private View emptyCard(String msg){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(14),dp(18),dp(14),dp(18));c.setBackground(roundRect(Color.parseColor("#0d1e36"),dp(12),C_BORDER2));LinearLayout.LayoutParams lp=lpW(-1);lp.setMargins(0,dp(8),0,0);c.setLayoutParams(lp);TextView t=txt(msg,13,false,C_TEXT2);t.setGravity(Gravity.CENTER);c.addView(t);return c;}
    private TextView smallBtn(String label,int color,View.OnClickListener l){TextView b=txt(label,10,true,color);b.setPadding(dp(10),dp(4),dp(10),dp(4));b.setBackground(roundRect(Color.TRANSPARENT,dp(6),color));b.setOnClickListener(l);LinearLayout.LayoutParams lp=lpWH(-2,-2);lp.setMargins(dp(6),0,0,0);b.setLayoutParams(lp);return b;}
    private TextView chip(String text,int bg,int textColor,int stroke){TextView t=txt(text,9,true,textColor);t.setGravity(Gravity.CENTER);t.setSingleLine(true);t.setPadding(dp(7),dp(2),dp(7),dp(2));t.setBackground(roundRect(bg,dp(5),stroke));return t;}
    private View divider(){View d=new View(this);d.setBackgroundColor(C_BORDER);d.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));return d;}
    private GradientDrawable rightBorder(int color){return roundRect(C_SURFACE,0,color);}
    private LinearLayout row(int gravity){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(gravity);return r;}
    private TextView txt(String v,int spSize,boolean bold,int color){TextView t=new TextView(this);t.setText(v);t.setTextSize(spSize);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT_BOLD);return t;}
    private GradientDrawable roundRect(int color,int radius,int strokeColor){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius);if(strokeColor!=Color.TRANSPARENT)g.setStroke(dp(1),strokeColor);return g;}
    private LinearLayout.LayoutParams lp0(float weight){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2);lp.weight=weight;return lp;}
    private LinearLayout.LayoutParams lpW(int width){return new LinearLayout.LayoutParams(width,-2);}
    private LinearLayout.LayoutParams lpWH(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private GridLayout.LayoutParams bossCardLp(){GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=(getResources().getDisplayMetrics().widthPixels-dp(36))/2;lp.height=GridLayout.LayoutParams.WRAP_CONTENT;lp.setMargins(dp(4),dp(4),dp(4),dp(4));return lp;}
    private String waveIcon(String key){if(key.contains("grakthar"))return "⚔️";if(key.contains("olympus"))return "🌊";return "⚡";}
    private String waveIconForLabel(String label){String l=label.toLowerCase(Locale.US);if(l.contains("grakthar"))return "⚔️";if(l.contains("olympus"))return "🌊";return "⚡";}
    private static String norm(String s){return(s==null?"":s).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");}
    private static long parseLong(String s){try{return Long.parseLong((s==null?"0":s).replaceAll("[^0-9]",""));}catch(Exception e){return 0;}}
    private String fmt(String s){try{long n=Long.parseLong(s);if(n>=1_000_000_000)return String.format(Locale.US,"%.1fb",n/1_000_000_000d).replace(".0","");if(n>=1_000_000)return String.format(Locale.US,"%.1fm",n/1_000_000d).replace(".0","");if(n>=1_000)return (n/1_000)+"k";return String.valueOf(n);}catch(Exception e){return(s==null||s.isEmpty())?"—":s;}}
    private static String bossRootKey(String name){if(name==null||name.isEmpty())return "";String[]parts=name.split("[,\\-]",2);return norm(parts[0].trim());}
    private static List<String[]> parseBossLines(String data){List<String[]> out=new ArrayList<>();if(data==null||data.isEmpty())return out;for(String line:data.split("\n")){String[]p=line.split("\\|",-1);if(p.length>=6)out.add(p);}return out;}
    private String aliveCountAll(){List<String[]> lines=parseBossLines(sp.getString("last_bosses",""));long c=0;for(String[]p:lines)if(p.length>2&&"ALIVE".equalsIgnoreCase(p[2]))c++;return c>0?c+" ⚡":"0";}
    private String aliveCountForKey(String key){List<String[]> lines=parseBossLines(sp.getString("last_bosses",""));long total=0,alive=0;for(String[]p:lines){String cat=p.length>0?norm(p[0]):"";if(cat.equals(norm(key))||cat.startsWith(norm(key))){total++;if(p.length>2&&"ALIVE".equalsIgnoreCase(p[2]))alive++;}}return alive>0?alive+" ⚡":total+" dead";}
    private boolean hasAlive(String pip){return pip!=null&&pip.contains("⚡");}
    private String latestStamina(){int v=sp.getInt("live_stamina",-1);return v>=0?String.valueOf(v):"—";}
    private String potionCount(String usedKey,String limitKey){int used=sp.getInt(usedKey,0);String limit=sp.getString(limitKey,"10");return used+"/"+limit;}
    private boolean isConnected(){String c=CookieManager.getInstance().getCookie("https://demonicscans.org");return c!=null&&c.length()>12&&!c.toLowerCase(Locale.US).contains("deleted");}
    private String lastScanLabel(){long t=sp.getLong("last_scan_ms",0);if(t<=0)return "never";long s=Math.max(0,(System.currentTimeMillis()-t)/1000);if(s<60)return s+"s ago";if(s<3600)return(s/60)+"m ago";return(s/3600)+"h ago";}
    private int logCount(){String l=sp.getString("logs","");return l.isEmpty()?0:l.split("\n").length;}
    private String levelOf(String line){int i=line.indexOf(']');if(line.startsWith("[")&&i>0)return line.substring(1,i);return "INFO";}
    private String msgOf(String line){int i=line.indexOf(']');return i>=0?line.substring(i+1).trim():line;}
    private void copyLogs(){String v=sp.getString("logs","");((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("CRN Boss Bot logs",v));toast("Logs copied");}
    private void clearLogs(){sp.edit().putString("logs","").apply();startService(new Intent(this,BotForegroundService.class).setAction(BotForegroundService.ACTION_CLEAR_LOGS));toast("Logs cleared");showMain();}
    private int statusBarHeight(){int id=getResources().getIdentifier("status_bar_height","dimen","android");return id>0?getResources().getDimensionPixelSize(id):dp(24);}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void destroyLogin(){if(loginWeb!=null){try{loginWeb.stopLoading();loginWeb.destroy();}catch(Exception ignored){}loginWeb=null;}}
    private void requestIgnoreBatteryOptimizationsOnce(){if(Build.VERSION.SDK_INT<23)return;if(sp.getBoolean("asked_battery_optimization",false))return;try{PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);if(pm!=null&&!pm.isIgnoringBatteryOptimizations(getPackageName())){sp.edit().putBoolean("asked_battery_optimization",true).apply();Intent i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);i.setData(Uri.parse("package:"+getPackageName()));startActivity(i);}}catch(Exception ignored){}}
    private void requestInitialScanOnce(){if(!isConnected())return;if(sp.getBoolean("global_enabled",false))return;long last=sp.getLong("initial_scan_request_ms",0);if(System.currentTimeMillis()-last<30000)return;sp.edit().putLong("initial_scan_request_ms",System.currentTimeMillis()).putString("ui_state","SCAN").apply();Intent in=new Intent(this,BotForegroundService.class).setAction(BotForegroundService.ACTION_SCAN_ONCE);if(Build.VERSION.SDK_INT>=26)startForegroundService(in);else startService(in);}
}
