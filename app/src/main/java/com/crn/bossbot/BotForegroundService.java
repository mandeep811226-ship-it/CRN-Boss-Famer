package com.crn.bossbot;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.*;
import android.webkit.CookieManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public class BotForegroundService extends Service {
    public static final String ACTION_START        = "START";
    public static final String ACTION_STOP         = "STOP";
    public static final String ACTION_STATUS       = "com.crn.bossbot.STATUS";
    public static final String ACTION_CLEAR_LOGS   = "CLEAR_LOGS";
    public static final String ACTION_SCAN_ONCE    = "SCAN_ONCE";
    public static final String ACTION_RELOAD_WAVES = "RELOAD_WAVES";

    private static final String PREF_CUSTOM_WAVES    = "custom_waves_json";
    // ── Direct monsters — each entry: "prefKey|name|monsterId|cap"
    private static final String PREF_DIRECT_MONSTERS = "direct_monsters_json";
    public  static final String ACTION_RELOAD_MONSTERS = "RELOAD_MONSTERS";

    private static final int    NOTIF_ID  = 100;
    private static final String CH_MAIN   = "crn_java_main_visible_v2";
    private static final String CH_ALERT  = "crn_java_alert";
    private static final String BASE      = "https://demonicscans.org";
    private static final String JOIN_URL  = BASE + "/user_join_battle.php";
    private static final String DMG_URL   = BASE + "/damage.php";
    private static final String BATTLE_URL    = BASE + "/battle.php";
    private static final String USE_ITEM_URL  = BASE + "/use_item.php";
    private static final String HP_POTION_URL = BASE + "/user_heal_potion.php";
    private static final String AUTO_STATUS_URL  = BASE + "/auto_farm_status.php";
    private static final String AUTO_ACTIONS_URL = BASE + "/auto_farm_actions.php";

    // ── Runtime state ──────────────────────────────────────────────────────────
    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock  wifiLock;
    private SharedPreferences sp;
    private long lastAlertMs = 0L;
    private final List<String>         logs                = new ArrayList<>();
    private final Map<String, Long>    lastSpawnAlertByBoss = new HashMap<>();
    private final Set<String>          cappedBossSkipKeys  = new HashSet<>();
    private final Map<String, Long>    damageByBoss        = new HashMap<>();
    private final Map<String, Long>    aliveDamageByBoss   = new HashMap<>();
    private boolean attacking              = false;
    private boolean aliveDamagePreScanDone = false;
    private int usedHp = 0, usedLsp = 0, usedFsp = 0;
    private volatile String notifBossName = "";
    private volatile long   notifDamage   = 0L, notifCap = 0L;

    // ── Category class ─────────────────────────────────────────────────────────
    static class Category { final String key, label, url; Category(String k,String l,String u){key=k;label=l;url=u;} }

    // ── Hardcoded built-in waves ───────────────────────────────────────────────
    private static final Category[] BUILTIN_CATEGORIES = {
        new Category("grakthar", "Grakthar", BASE + "/active_wave.php?gate=3&wave=8"),
        new Category("olympus",  "Olympus",  BASE + "/active_wave.php?gate=5&wave=9"),
        new Category("hermes",   "Hermes",   BASE + "/active_wave.php?gate=5&wave=10"),
    };

    private Category[] buildCategories() {
        List<Category> list = new ArrayList<>(Arrays.asList(BUILTIN_CATEGORIES));
        String raw = sp.getString(PREF_CUSTOM_WAVES, "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String[] parts = line.split("\\|", -1);
                if (parts.length >= 3 && !empty(parts[2]))
                    list.add(new Category(parts[0], parts[1], parts[2]));
            }
        }
        return list.toArray(new Category[0]);
    }

    // ── Data classes ───────────────────────────────────────────────────────────
    static class Boss {
        String categoryKey, categoryLabel, name, image, monsterId, battleId, key, status, timer;
        String currentPhaseName = "";
        // ── Direct monster flag ─────────────────────────────────────────────────
        // true when this Boss was registered manually via "Add Monster" (not parsed
        // from a wave page). The bot fetches its state directly from battle.php.
        boolean isDirectMonster = false;
        // ───────────────────────────────────────────────────────────────────────
        boolean alive, loot, enabled;
        long damage, cap;
    }
    static class Skill {
        String name; int skill_id, stamina_cost;
        Skill(String n,int s,int c){name=n;skill_id=s;stamina_cost=c;}
    }
    private static final Skill[] SKILLS = {
        new Skill("Worldbreaker Slash", -5, 1000),
        new Skill("Ultimate Slash",     -4, 200),
        new Skill("Heroic Slash",       -2, 50),
        new Skill("Power Slash",        -1, 10),
        new Skill("Slash",               0, 1),
    };

    static class PotionInfo { String invId, name; PotionInfo(String i,String n){invId=i;name=n;} }
    static class PotionSet  { PotionInfo large, full, hp; }
    static class AttackResult {
        boolean hasJson; int status; String message = "";
        long hitDamage=0,logDamage=0,totalDamage=0,leaderboardTotal=0; int stamina=-1;
    }
    static class HttpResult { int status; String text=""; HttpResult(int s,String t){status=s;text=t==null?"":t;} }

    // ═══════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    @Override public void onCreate() {
        super.onCreate();
        sp = getSharedPreferences("crn_java_bot", MODE_PRIVATE);
        usedLsp = sp.getInt("used_lsp", 0);
        usedFsp = sp.getInt("used_fsp", 0);
        usedHp  = sp.getInt("used_hp",  0);
        createChannels();
        try { startForeground(NOTIF_ID, notification("CRN Boss Bot", "Service ready", false)); } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action))         { stopBot(); return START_NOT_STICKY; }
        if (ACTION_CLEAR_LOGS.equals(action))   { clearLogs(); return running ? START_STICKY : START_NOT_STICKY; }
        if (ACTION_SCAN_ONCE.equals(action))    { scanOnceAsync(); return running ? START_STICKY : START_NOT_STICKY; }
        if (ACTION_RELOAD_WAVES.equals(action) || ACTION_RELOAD_MONSTERS.equals(action)) {
            append("INFO", "Wave/monster list reloaded — will take effect on next scan.");
            return running ? START_STICKY : START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, notification("CRN Boss Bot", "Starting engine…", false));
        startBot();
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (running || sp.getBoolean("global_enabled", false)) {
            Intent restart = new Intent(getApplicationContext(), BotForegroundService.class).setAction(ACTION_START);
            PendingIntent pi = PendingIntent.getService(getApplicationContext(), 3, restart, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null) {
                long t = System.currentTimeMillis() + 1000L;
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
                else am.set(AlarmManager.RTC_WAKEUP, t, pi);
            }
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        releaseLocks();
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  BOT ENGINE
    // ═══════════════════════════════════════════════════════════════════════════
    private void startBot() {
        if (running) return;
        running = true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "crn:java-standalone-bot");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "crn:java-standalone-wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
        worker = new Thread(this::loop, "CRN-Java-Standalone-Engine");
        worker.start();
    }

    private void stopBot() {
        running = false;
        if (worker != null) worker.interrupt();
        releaseLocks();
        sendStatus("STOPPED", "Bot stopped", null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void loop() {
        append("INFO", "Java engine started.");
        fetchPotions();
        while (running) {
            try {
                if (!sp.getBoolean("global_enabled", false)) {
                    sendStatus("OFF", "Global OFF", null);
                    sleep(3000); continue;
                }
                sp.edit().putString("ui_state","SCAN").putString("ui_message","Scanning boss status").apply();
                int liveStamina = fetchStamina();
                if (liveStamina >= 0) append("INFO", "Live stamina " + liveStamina);
                List<Boss> bosses = fetchBosses();
                saveBossesForUi(bosses);
                sp.edit().putLong("last_scan_ms", System.currentTimeMillis()).apply();
                Boss target = selectTarget(bosses);
                if (target == null) sendStatus("IDLE", "No enabled alive/loot boss target found", bosses);
                else { sendStatus("TARGET", target.name + " • " + target.status, bosses); processTarget(target); }
                sleep(nextDelayMs());
            } catch (InterruptedException e) { break; }
            catch (Exception e) {
                append("ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
                sendStatus("ERROR", e.getMessage(), null);
                try { sleep(8000); } catch (Exception ignored) {}
            }
        }
    }

    private void scanOnceAsync() {
        new Thread(() -> {
            try {
                sp.edit().putString("ui_state","SCAN").putString("ui_message","Scanning boss status").apply();
                update("CRN Boss Bot • SCAN", "Scanning boss status", false);
                int sta = fetchStamina();
                if (sta >= 0) append("INFO", "Live stamina " + sta);
                List<Boss> bosses = fetchBosses();
                saveBossesForUi(bosses);
                sp.edit().putLong("last_scan_ms", System.currentTimeMillis()).apply();
                sendStatus("IDLE", "Initial scan complete", bosses);
            } catch (Exception e) {
                append("ERROR", "Initial scan failed: " + e.getMessage());
                sendStatus("ERROR", e.getMessage(), null);
            }
        }, "CRN-Initial-Scan").start();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PHASE SYSTEM — helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if this boss root has been marked as phase-capable,
     * either by the user manually toggling it or by auto-detection.
     */
    private boolean isPhaseBoss(String root) {
        return sp.getBoolean("phase_boss_" + root, false);
    }

    /**
     * Returns true if the boss's current name matches a phase entry
     * that the user has marked as PvP type.
     * Used to skip attacking entirely during PvP phases.
     */
    private boolean isBossInPvpPhase(String bossName) {
        String root     = bossRootKey(bossName);
        String nameLower = bossName.toLowerCase(Locale.US);
        if (!isPhaseBoss(root)) return false;
        int count = sp.getInt("phase_count_" + root, 0);
        for (int i = 0; i < count; i++) {
            String type = sp.getString("phase_type_" + root + "_" + i, "pve");
            String frag = sp.getString("phase_namefrag_" + root + "_" + i, "")
                           .toLowerCase(Locale.US).trim();
            if (!empty(frag) && nameLower.contains(frag) && "pvp".equals(type))
                return true;
        }
        return false;
    }

    /**
     * Extracts a short matchable keyword from a full phase name.
     * "Artemis, Lunar Duelist of the Sacred Hunt" → "lunar duelist"
     * Takes first 3 words after the comma for reliable, non-greedy matching.
     */
    private String extractPhaseFragment(String fullName) {
        if (empty(fullName)) return "";
        String[] parts = fullName.split(",", 2);
        if (parts.length < 2) return fullName.toLowerCase(Locale.US).trim();
        String afterComma = parts[1].trim().toLowerCase(Locale.US);
        String[] words = afterComma.split("\\s+");
        int take = Math.min(3, words.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < take; i++) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /**
     * Called from parseBosses() whenever a live monster-card name differs
     * from the auto-summon-card name for the same root key.
     *
     * Guards applied before saving:
     *   1. URL must be active_wave.php  (not pvp or other page types)
     *   2. Name must not contain pvp-related keywords
     *   3. Fragment must not already be recorded
     *
     * Auto-marks the boss as phase-capable and saves the new phase entry
     * with default type "pve". User can switch it to "pvp" in the UI.
     */
    private void autoLearnPhase(String root, String summonName,
                                 String liveCardName, Category category) {
        // Guard 1: only learn from wave pages
        if (!category.url.contains("active_wave.php")) {
            append("INFO", "⚡ Phase change seen for [" + root
                + "] but not on a wave page — skipped auto-learn.");
            return;
        }
        // Guard 2: PvP keyword guard
        String lower = liveCardName.toLowerCase(Locale.US);
        boolean looksPvp = lower.contains("pvp") || lower.contains("arena")
                        || lower.contains("challenge");
        if (looksPvp) {
            append("INFO", "⚡ Phase change for [" + root
                + "] — PvP keyword guard blocked auto-learn: " + liveCardName);
            return;
        }

        String newFrag = extractPhaseFragment(liveCardName);
        if (empty(newFrag)) return;

        // Guard 3: don't duplicate existing fragments
        int count = sp.getInt("phase_count_" + root, 0);
        for (int i = 0; i < count; i++) {
            String existing = sp.getString("phase_namefrag_" + root + "_" + i, "");
            if (existing.equalsIgnoreCase(newFrag)) return; // already known
        }

        SharedPreferences.Editor ed = sp.edit();
        int idx = count;

        // If first auto-learn: also record phase 1 (from the summon card name)
        if (count == 0) {
            String frag0 = extractPhaseFragment(summonName);
            if (!empty(frag0)) {
                ed.putString("phase_label_"    + root + "_0", "Phase 1")
                  .putString("phase_namefrag_" + root + "_0", frag0)
                  .putString("phase_type_"     + root + "_0", "pve")
                  .putString("phase_cap_"      + root + "_0", "0")
                  .putString("phase_source_"   + root + "_0", "auto");
                idx = 1;
            }
        }

        // Save the newly detected phase
        ed.putBoolean("phase_boss_"      + root,            true)
          .putString ("phase_label_"    + root + "_" + idx, "Phase " + (idx + 1))
          .putString ("phase_namefrag_" + root + "_" + idx, newFrag)
          .putString ("phase_type_"     + root + "_" + idx, "pve")
          .putString ("phase_cap_"      + root + "_" + idx, "0")
          .putString ("phase_source_"   + root + "_" + idx, "auto")
          .putInt    ("phase_count_"    + root,            idx + 1)
          .apply();

        append("INFO", "⚡ Auto-learned phase for [" + root + "]: \""
            + newFrag + "\" → Phase " + (idx + 1)
            + ". Review in wave tab to set cap or mark PvP.");
    }

    /**
     * Three-level cap resolution:
     *   1. Phase boss + name fragment matches a PvE phase → per-phase cap
     *   2. Phase boss but no fragment matched current name → generic cap (safe fallback)
     *   3. Non-phase boss → generic single cap
     *
     * PvP-typed phases are skipped (bot never attacks them, cap irrelevant).
     */
    private long capForBoss(String bossName, String categoryKey) {
        String root     = bossRootKey(bossName);
        String nameLower = bossName.toLowerCase(Locale.US);

        if (isPhaseBoss(root)) {
            int count = sp.getInt("phase_count_" + root, 0);
            for (int i = 0; i < count; i++) {
                String type = sp.getString("phase_type_" + root + "_" + i, "pve");
                if ("pvp".equals(type)) continue; // PvP phase → skip

                String frag = sp.getString("phase_namefrag_" + root + "_" + i, "")
                               .toLowerCase(Locale.US).trim();
                if (!empty(frag) && nameLower.contains(frag)) {
                    long phaseCap = parseCap(
                        sp.getString("phase_cap_" + root + "_" + i, "0"));
                    if (phaseCap > 0) return phaseCap;
                    break; // phase matched but cap = 0 → fall through to generic
                }
            }
            // Phase boss but nothing matched → warn and fall through
            append("WARN", bossName
                + ": phase boss — no fragment matched current name, using generic cap.");
        }

        // Generic single cap (original behaviour — unchanged for simple bosses)
        String raw = sp.getString("cap_" + categoryKey + "_" + root, "");
        return parseCap(empty(raw) ? "0" : raw);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  BOSS FETCHING
    // ═══════════════════════════════════════════════════════════════════════════
    private List<Boss> fetchBosses() throws Exception {
        List<Boss> all = new ArrayList<>();
        for (Category c : buildCategories()) {
            boolean catEnabled = sp.getBoolean("enable_" + c.key, true);
            String html = get(c.url, c.url);
            List<Boss> parsed = parseBosses(html, c, catEnabled);
            all.addAll(parsed);
            append("INFO", c.label + " scan: " + parsed.size() + " boss cards");
        }

        // ── Direct monsters — fetch state individually from battle.php ──────────
        all.addAll(fetchDirectMonsters());
        // ────────────────────────────────────────────────────────────────────────

        // Fetch battle page for alive bosses to get accurate auto-die timer.
        // Direct monsters already had their timer fetched in fetchDirectMonsters() —
        // re-fetching here would overwrite a valid timer with a failed parse.
        for (Boss b : all) {
            if (!b.alive) continue;
            if (b.isDirectMonster) continue; // timer already set in fetchDirectMonsters()
            if (empty(b.monsterId)) {
                append("WARN", b.name + " alive but monsterId empty — skipping timer fetch");
                continue;
            }
            try {
                String battleHtml = get(BATTLE_URL + "?id=" + enc(b.monsterId), BATTLE_URL);

                // Strategy 1: id="nodmgCountdown" — <strong id="nodmgCountdown">22:11:40</strong>
                String countdown = first(battleHtml,
                    "(?is)id=[\"']nodmgCountdown[\"'][^>]*>\\s*([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?)");
                if (!empty(countdown)) {
                    try {
                        String[] tp = countdown.trim().split(":");
                        long secs = tp.length == 3
                            ? Long.parseLong(tp[0])*3600 + Long.parseLong(tp[1])*60 + Long.parseLong(tp[2])
                            : Long.parseLong(tp[0])*60   + Long.parseLong(tp[1]);
                        if (secs > 0) b.timer = "Auto dies in " + formatSecs(secs);
                        else b.timer = "Auto dies soon";
                    } catch (NumberFormatException ignored) {}
                }

                // Strategy 2: window.AUTO_DIE_CFG = { nextDieMs: ... }
                if (empty(b.timer)) {
                    String nextDieMsStr = first(battleHtml,
                        "(?i)AUTO_DIE_CFG\\s*=\\s*\\{[^}]*nextDieMs\\s*:\\s*([0-9]+)");
                    if (!empty(nextDieMsStr)) {
                        try {
                            long epochMs  = Long.parseLong(nextDieMsStr.trim());
                            long secsLeft = (epochMs - System.currentTimeMillis()) / 1000;
                            if (secsLeft > 0 && secsLeft < 86400L * 7)
                                b.timer = "Auto dies in " + formatSecs(secsLeft);
                            else if (secsLeft <= 0)
                                b.timer = "Auto dies soon";
                        } catch (NumberFormatException ignored) {}
                    }
                }

                // Strategy 3: id="autoDieTimer"
                if (empty(b.timer)) {
                    String timerBlock = first(battleHtml,
                        "(?is)id=[\"']autoDieTimer[\"'][^>]*>(.*?)</(?:div|span|p|td)");
                    String rawTimer = empty(timerBlock) ? ""
                        : first(timerBlock.replaceAll("<[^>]+>", " "),
                            "([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?)");
                    if (!empty(rawTimer)) {
                        try {
                            String[] tp = rawTimer.trim().split(":");
                            long secs = tp.length == 3
                                ? Long.parseLong(tp[0])*3600 + Long.parseLong(tp[1])*60 + Long.parseLong(tp[2])
                                : Long.parseLong(tp[0])*60   + Long.parseLong(tp[1]);
                            b.timer = "Auto dies in " + formatSecs(secs);
                        } catch (NumberFormatException ignored) {}
                    }
                }

                // Strategy 4: visible text "AUTO DIES AFTER: 00:50:13"
                if (empty(b.timer)) {
                    String rawTimer = first(battleHtml,
                        "(?i)AUTO\\s+DIES\\s+AFTER[^0-9]{0,30}([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?)");
                    if (!empty(rawTimer)) {
                        try {
                            String[] tp = rawTimer.trim().split(":");
                            long secs = tp.length == 3
                                ? Long.parseLong(tp[0])*3600 + Long.parseLong(tp[1])*60 + Long.parseLong(tp[2])
                                : Long.parseLong(tp[0])*60   + Long.parseLong(tp[1]);
                            b.timer = "Auto dies in " + formatSecs(secs);
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (empty(b.timer))
                    append("WARN", b.name + " auto-die: timer not found (id=" + b.monsterId + ")");

            } catch (Exception e) {
                append("ERROR", b.name + " auto-die fetch: " + e.getMessage());
            }
        }
        return all;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  DIRECT MONSTERS — fetch state from battle.php by stored monster ID
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Loads all saved direct monsters from SharedPreferences and fetches
     * their current state (alive/dead, name, damage, HP) from battle.php.
     *
     * Storage format (pipe-delimited, one entry per line in PREF_DIRECT_MONSTERS):
     *   prefKey|displayName|monsterId|cap
     *
     * Each entry is turned into a Boss object with isDirectMonster=true.
     * The bot's selectTarget() and processTarget() handle them identically
     * to wave-parsed bosses — no special casing needed there.
     */
    private List<Boss> fetchDirectMonsters() {
        List<Boss> list = new ArrayList<>();
        String raw = sp.getString(PREF_DIRECT_MONSTERS, "");
        if (raw == null || raw.trim().isEmpty()) return list;

        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length < 3) continue;

            String prefKey   = parts[0].trim();
            String savedName = parts.length > 1 ? parts[1].trim() : "Direct Monster";
            String monsterId = parts[2].trim();
            String capRaw    = parts.length > 3 ? parts[3].trim() : "0";

            if (empty(monsterId)) continue;
            boolean enabled = sp.getBoolean("monster_enabled_" + prefKey, false);

            Boss b = new Boss();
            b.isDirectMonster = true;
            b.categoryKey     = "monsters";
            b.categoryLabel   = "Monsters";
            b.monsterId       = monsterId;
            // Use prefKey as the stable key — never changes even when real name is parsed
            b.key             = "monsters:" + prefKey;
            b.name            = savedName;
            b.alive           = false;
            b.status          = "CHECKING";
            b.enabled         = enabled;
            b.cap             = parseCap(sp.getString("monster_cap_" + prefKey, capRaw));
            b.damage          = 0;

            try {
                String battleUrl = BATTLE_URL + "?id=" + enc(monsterId);
                String html = get(battleUrl, battleUrl);

                // ── NAME ─────────────────────────────────────────────────────
                // Real HTML: <div class="card-title">🧟 Grathmor, First Wallbreaker</div>
                // Strip leading emoji/whitespace after extracting.
                String parsedName = firstNonEmpty(
                    first(html, "(?is)class=[\"']card-title[\"'][^>]*>([^<]+)"),
                    first(html, "(?is)<div[^>]+class=[\"'][^\"']*card-title[^\"']*[\"'][^>]*>([^<]+)"),
                    savedName
                );
                // Remove leading emoji characters and whitespace
                parsedName = parsedName.replaceAll("^[\\s\\p{So}\\p{Sm}\\p{Sk}\\p{Sc}\\p{Cn}]+", "").trim();
                if (!parsedName.isEmpty() && parsedName.length() > 2) b.name = parsedName;

                // ── ALIVE / DEAD ──────────────────────────────────────────────
                String lowerHtml = html.toLowerCase(Locale.US);

                // Signal 1: nodmgCountdown timer value (static HTML)
                String countdown = first(html,
                    "(?is)id=[\"']nodmgCountdown[\"'][^>]*>\\s*([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?)");
                // Also treat the mere presence of nodmgCountdown element as alive —
                // the timer value may be JS-injected so the raw HTTP response can
                // have an empty element: <strong id="nodmgCountdown"></strong>
                boolean hasTimer = !empty(countdown)
                    || html.contains("id=\"nodmgCountdown\"")
                    || html.contains("id='nodmgCountdown'");

                // Signal 2: attack buttons (both quote styles)
                boolean hasAttackBtn = html.contains("class=\"attack-btn\"")
                                    || html.contains("class='attack-btn'")
                                    || html.contains("data-skill-id=");

                // Signal 3: defeat message — strip <script> blocks first!
                // The live page embeds defeat phrases inside JavaScript string
                // literals (message templates, error handlers). A plain
                // html.contains() hits those JS strings even on a 100% alive page.
                // Stripping scripts before searching prevents this false positive.
                String htmlNoScript = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
                String lowerNoScript = htmlNoScript.toLowerCase(Locale.US);
                boolean defeatedMsg = lowerNoScript.contains("has been defeated")
                                   || lowerNoScript.contains("monster is dead")
                                   || lowerNoScript.contains("already defeated")
                                   || lowerNoScript.contains("monster died");
                // Extra guard: if ANY alive signal is present, ignore defeatedMsg.
                // A defeat phrase in visible text alongside attack buttons is a UI
                // quirk (battle history log), not a real death signal.
                if (hasTimer || hasAttackBtn) defeatedMsg = false;

                // Signal 4: HP bar at 0%
                String hpFillStyle = first(html,
                    "(?is)id=[\"']hpFill[\"'][^>]*style=[\"']([^\"']+)[\"']");
                if (empty(hpFillStyle))
                    hpFillStyle = first(html,
                        "(?is)class=[\"'][^\"']*hp-fill[^\"']*[\"'][^>]*style=[\"']([^\"']+)[\"']");
                boolean hpZero = !empty(hpFillStyle)
                    && hpFillStyle.replaceAll("\\s","").contains("width:0%");

                // Signal 5: hpText shows "0 / MAX HP"
                String hpText = first(html, "(?is)id=[\"']hpText[\"'][^>]*>([^<]+)");
                if (!empty(hpText)) {
                    String cur = first(hpText, "([0-9][0-9,]*)\\s*/");
                    if (!empty(cur) && parseLong(cur) == 0) hpZero = true;
                }

                // Page must have actual battle content — guards against login/session-
                // expired redirect pages which also have no timer and no attack buttons.
                boolean pageHasBattleContent = lowerHtml.contains("hpfill")
                    || lowerHtml.contains("attack-btn")
                    || lowerHtml.contains("nodmgcountdown")
                    || lowerHtml.contains("yourdamagevalue")
                    || lowerHtml.contains("card-title");

                boolean isDead = defeatedMsg || hpZero
                    || (!hasTimer && !hasAttackBtn && pageHasBattleContent);
                b.alive  = !isDead;
                b.status = isDead ? "DEAD" : "ALIVE";

                // ── DIAGNOSTIC LOG ────────────────────────────────────────────
                // Every scan logs which signals fired so future false-DEAD events
                // can be diagnosed directly from the log.
                {
                    String hpFillShort = empty(hpFillStyle) ? "n/a"
                        : hpFillStyle.replaceAll("\\s", "");
                    String diag = "hasTimer=" + hasTimer
                        + " hasAttackBtn=" + hasAttackBtn
                        + " defeatedMsg=" + defeatedMsg
                        + " hpZero=" + hpZero
                        + " hpFill=" + hpFillShort
                        + " pageOk=" + pageHasBattleContent;
                    if (isDead) {
                        List<String> triggers = new ArrayList<>();
                        if (defeatedMsg) triggers.add("defeatedMsg");
                        if (hpZero)      triggers.add("hpZero");
                        if (!hasTimer && !hasAttackBtn && pageHasBattleContent)
                                         triggers.add("noTimerAndNoBtn");
                        append("DIAG", "Direct monster [" + b.name + "] id=" + monsterId
                            + " \u2192 DEAD triggered by: "
                            + (triggers.isEmpty() ? "none?" : android.text.TextUtils.join("+", triggers))
                            + " | " + diag);
                    } else {
                        append("DIAG", "Direct monster [" + b.name + "] id=" + monsterId
                            + " \u2192 ALIVE | " + diag);
                    }
                }

                // ── AUTO-DIE TIMER ────────────────────────────────────────────
                // Strategy 1: data-epoch / data-nodmg-ts / data-die-ms attributes
                // These are static server-rendered HTML attributes — most reliable.
                // The page uses Unix epoch seconds (not ms) for data-epoch.
                // Selector evidence: data-epoch="1780461037" data-tzoff="19800"
                if (empty(b.timer)) {
                    String epochStr = first(html,
                        "(?is)data-(?:epoch|nodmg-ts|die-ms|die-at|end-time|killtime|nodmg)=[\"']([0-9]{9,13})[\"']");
                    if (!empty(epochStr)) {
                        try {
                            long raw = Long.parseLong(epochStr.trim());
                            // Distinguish epoch-seconds (10 digits ~2001-2286) from epoch-ms (13 digits)
                            long epochMs = raw > 9_999_999_999L ? raw : raw * 1000L;
                            long secsLeft = (epochMs - System.currentTimeMillis()) / 1000;
                            if (secsLeft > 0 && secsLeft < 86400L * 7)
                                b.timer = "Auto dies in " + formatSecs(secsLeft);
                            else if (secsLeft <= 0)
                                b.timer = "Auto dies soon";
                        } catch (NumberFormatException ignored) {}
                    }
                }
                // Strategy 2: static value in nodmgCountdown element text content
                if (empty(b.timer) && !empty(countdown)) {
                    try {
                        String[] tp = countdown.trim().split(":");
                        long secs = tp.length == 3
                            ? Long.parseLong(tp[0])*3600 + Long.parseLong(tp[1])*60 + Long.parseLong(tp[2])
                            : Long.parseLong(tp[0])*60   + Long.parseLong(tp[1]);
                        b.timer = secs > 0 ? "Auto dies in " + formatSecs(secs) : "Auto dies soon";
                    } catch (NumberFormatException ignored) {}
                }
                // Strategy 3: JS config object — window.AUTO_DIE_CFG = { nextDieMs: ... }
                if (empty(b.timer)) {
                    String nextDieMsStr = first(html,
                        "(?i)AUTO_DIE_CFG\\s*=\\s*\\{[^}]*nextDieMs\\s*:\\s*([0-9]+)");
                    if (!empty(nextDieMsStr)) {
                        try {
                            long epochMs  = Long.parseLong(nextDieMsStr.trim());
                            long secsLeft = (epochMs - System.currentTimeMillis()) / 1000;
                            if (secsLeft > 0 && secsLeft < 86400L * 7)
                                b.timer = "Auto dies in " + formatSecs(secsLeft);
                            else if (secsLeft <= 0)
                                b.timer = "Auto dies soon";
                        } catch (NumberFormatException ignored) {}
                    }
                }
                // Strategy 4: visible text "AUTO DIES AFTER: 00:50:13"
                if (empty(b.timer)) {
                    String rawTimer = first(html,
                        "(?i)AUTO\\s+DIES\\s+AFTER[^0-9]{0,30}([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?)");
                    if (!empty(rawTimer)) {
                        try {
                            String[] tp = rawTimer.trim().split(":");
                            long secs = tp.length == 3
                                ? Long.parseLong(tp[0])*3600 + Long.parseLong(tp[1])*60 + Long.parseLong(tp[2])
                                : Long.parseLong(tp[0])*60   + Long.parseLong(tp[1]);
                            b.timer = "Auto dies in " + formatSecs(secs);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                // Strategy 5: "will Auto die in" phrase — spans HTML tags, widened to 500 chars
                // Structure: <strong>will Auto die</strong> " in " <strong id="nodmgCountdown">16:51:47</strong>
                if (empty(b.timer)) {
                    String rawTimer = first(html,
                        "(?is)will\\s+auto\\s+die\\b.{0,500}?([0-9]{1,3}:[0-9]{2}(?::[0-9]{2})?)");
                    if (!empty(rawTimer)) {
                        try {
                            String[] tp = rawTimer.trim().split(":");
                            long secs = tp.length == 3
                                ? Long.parseLong(tp[0])*3600 + Long.parseLong(tp[1])*60 + Long.parseLong(tp[2])
                                : Long.parseLong(tp[0])*60   + Long.parseLong(tp[1]);
                            b.timer = "Auto dies in " + formatSecs(secs);
                        } catch (NumberFormatException ignored) {}
                    }
                }

                // ── DAMAGE ────────────────────────────────────────────────────
                // <span id="yourDamageValue">6,100,819,740</span>
                String dmgStr = first(html, "(?is)id=[\"']yourDamageValue[\"'][^>]*>([0-9,]+)");
                if (!empty(dmgStr)) b.damage = parseLong(dmgStr);

                // ── AUTO-UPDATE SAVED NAME ────────────────────────────────────
                if (!b.name.equals(savedName)) {
                    updateDirectMonsterName(prefKey, b.name);
                    append("INFO", "Direct monster name updated: \"" + savedName
                        + "\" \u2192 \"" + b.name + "\"");
                }

                // ── RESTORE DAMAGE FROM SESSION MEMORY ───────────────────────
                // Only restore when alive — never paste stale damage from a
                // previous life onto a dead monster (causes misleading log entries).
                // When dead, clear the memory so it doesn't bleed into next spawn.
                if (b.alive) {
                    Long mem = aliveDamageByBoss.get(b.key);
                    if (mem != null && mem > b.damage) b.damage = mem;
                } else {
                    aliveDamageByBoss.remove(b.key);
                }

                append("INFO", "Direct monster [" + b.name + "] id=" + monsterId
                    + " status=" + b.status + " dmg=" + fmtn(b.damage)
                    + (empty(b.timer) ? "" : " | " + b.timer));

            } catch (Exception e) {
                append("ERROR", "Direct monster fetch [" + monsterId + "]: " + e.getMessage());
                b.status = "ERROR";
            }

            list.add(b);
        }
        return list;
    }

    private void updateDirectMonsterName(String prefKey, String newName) {
        String raw = sp.getString(PREF_DIRECT_MONSTERS, "");
        if (empty(raw)) return;
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length >= 1 && parts[0].trim().equals(prefKey) && parts.length > 1) {
                parts[1] = newName;
                if (sb.length() > 0) sb.append("\n");
                sb.append(String.join("|", parts));
            } else {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
        }
        sp.edit().putString(PREF_DIRECT_MONSTERS, sb.toString()).apply();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  BOSS PARSING  ← phase detection integrated here
    // ═══════════════════════════════════════════════════════════════════════════
    private List<Boss> parseBosses(String html, Category category, boolean categoryEnabled) {
        List<Boss> list = new ArrayList<>();

        // Index all live monster-cards by root key.
        // If two cards share a root (phase 1 + phase 3 both map to "artemis"),
        // prefer the data-boss="1" flagged card.
        Map<String, String> liveByName    = new HashMap<>();
        Map<String, String> liveFullNames = new HashMap<>(); // root → full card name

        for (String card : extractClassBlocks(html, "monster-card")) {
            if ("1".equals(attr(card, "data-dead"))) continue;
            String name = firstNonEmpty(
                attr(card, "data-name"),
                cleanHtml(first(card, "(?is)<h[1-6][^>]*>(.*?)</h[1-6]>")),
                cleanHtml(first(card, "(?is)<strong[^>]*>(.*?)</strong>")),
                cleanHtml(first(card, "(?is)<div[^>]+class=[\"'][^\"']*(?:name|title)[^\"']*[\"'][^>]*>(.*?)</div>"))
            );
            String key = bossRootKey(name);
            if (empty(key)) continue;
            boolean isBossFlagged = "1".equals(attr(card, "data-boss"));
            if (liveByName.get(key) == null || isBossFlagged) {
                liveByName.put(key, card);
                liveFullNames.put(key, name.replaceAll("(\\.{2,}|\u2026+)$", "").trim());
            }
        }

        for (String summon : extractClassBlocks(html, "auto-summon-card")) {
            Boss b = new Boss();
            b.categoryKey   = category.key;
            b.categoryLabel = category.label;
            b.name = firstNonEmpty(
                attr(summon, "data-name"),
                attr(summon, "data-boss-name"),
                cleanHtml(first(summon, "(?is)<h[1-6][^>]*>(.*?)</h[1-6]>")),
                cleanHtml(first(summon, "(?is)<strong[^>]*>(.*?)</strong>")),
                cleanHtml(first(summon, "(?is)<div[^>]+class=[\"'][^\"']*(?:name|title)[^\"']*[\"'][^>]*>(.*?)</div>"))
            );
            if (empty(b.name)) continue;
            b.name = b.name.replaceAll("(\\.{2,}|\u2026+)$", "").trim();

            String rootKey = bossRootKey(b.name);
            String live    = liveByName.get(rootKey);
            b.key = category.key + ":" + rootKey;

            String lowerSummon = summon.toLowerCase(Locale.US);
            String aliveAttr   = firstNonEmpty(attr(summon,"data-alive"), attr(summon,"data_alive"));
            boolean saysDead   = "1".equals(attr(summon,"data-dead")) || "0".equals(aliveAttr)
                                 || lowerSummon.contains("dead");
            b.alive  = "1".equals(aliveAttr) && !saysDead;
            b.loot   = !b.alive && (lowerSummon.contains("loot") || lowerSummon.contains("claim"));
            b.status = b.alive ? "ALIVE" : "DEAD";
            b.image  = firstNonEmpty(attr(summon,"data-image"),
                        first(summon,"<img[^>]+src=[\"']([^\"']+)[\"']"));

            if (b.alive && live != null) {
                // ── Phase detection ────────────────────────────────────────────
                // If the live monster-card has a different name than the
                // auto-summon-card for the same root, the boss has changed phases.
                String liveCardName = liveFullNames.getOrDefault(rootKey, "");
                if (!empty(liveCardName) && !liveCardName.equalsIgnoreCase(b.name)) {
                    b.currentPhaseName = liveCardName;
                    autoLearnPhase(rootKey, b.name, liveCardName, category);
                    append("INFO", "⚡ [" + rootKey + "] phase change detected: "
                        + b.name + " → " + liveCardName);
                }
                // ──────────────────────────────────────────────────────────────

                b.monsterId = firstNonEmpty(
                    attr(live,"data-monster-id"), attr(live,"data-id"),
                    first(live,"battle\\.php\\?(?:[^\"'<>]*?&)?id=([0-9]+)"),
                    first(live,"[?&]id=([0-9]+)"),
                    first(live,"(?:monster_id|monsterId)[^0-9]{0,20}(\\d+)")
                );
                b.battleId = firstNonEmpty(
                    attr(live,"data-battle-id"),
                    first(live,"(?:battle_id|battleId)[^0-9]{0,20}(\\d+)")
                );
                b.damage = parseLong(firstNonEmpty(
                    attr(live,"data-userdmg"), attr(live,"data-user-dmg"), attr(live,"data-damage"),
                    first(live,"(?i)(?:damage|dealt)[^0-9]{0,30}([0-9,]+)")
                ));
                if (empty(b.image)) b.image = first(live,"<img[^>]+src=[\"']([^\"']+)[\"']");
            }

            if (b.alive && empty(b.monsterId))
                b.monsterId = firstNonEmpty(
                    first(summon,"battle\\.php\\?(?:[^\"'<>]*?&)?id=([0-9]+)"),
                    first(summon,"[?&]id=([0-9]+)"),
                    attr(summon,"data-monster-id"), attr(summon,"data-id")
                );

            b.timer   = parseTimerFromCards(summon, live != null ? live : "");
            b.cap     = capForBoss(b.name, category.key);
            b.enabled = categoryEnabled && sp.getBoolean("boss_enabled_" + b.key, false);
            list.add(b);
        }

        // Last-resort: data-boss=1 monster cards only
        if (list.isEmpty()) {
            for (String live : liveByName.values()) {
                if (!"1".equals(attr(live,"data-boss"))) continue;
                Boss b = new Boss();
                b.categoryKey   = category.key;
                b.categoryLabel = category.label;
                b.name = firstNonEmpty(attr(live,"data-name"),
                    cleanHtml(first(live,"(?is)<h[1-6][^>]*>(.*?)</h[1-6]>")));
                if (empty(b.name)) continue;
                b.name = b.name.replaceAll("(\\.{2,}|\u2026+)$","").trim();
                String rootKey = bossRootKey(b.name);
                b.key       = category.key + ":" + rootKey;
                b.alive     = !"1".equals(attr(live,"data-dead"));
                b.status    = b.alive ? "ALIVE" : "DEAD";
                b.monsterId = firstNonEmpty(attr(live,"data-monster-id"),attr(live,"data-id"),
                    first(live,"battle\\.php\\?(?:[^\"'<>]*?&)?id=([0-9]+)"),
                    first(live,"[?&]id=([0-9]+)"));
                b.battleId  = attr(live,"data-battle-id");
                b.image     = first(live,"<img[^>]+src=[\"']([^\"']+)[\"']");
                b.damage    = parseLong(firstNonEmpty(attr(live,"data-userdmg"),
                    attr(live,"data-user-dmg"),attr(live,"data-damage")));
                b.timer     = parseTimerFromCards(live, "");
                b.cap       = capForBoss(b.name, category.key);
                b.enabled   = categoryEnabled && sp.getBoolean("boss_enabled_" + b.key, false);
                list.add(b);
            }
        }

        if (list.isEmpty()) {
            Boss b = new Boss();
            b.categoryKey = category.key; b.categoryLabel = category.label;
            b.name    = category.label + " scan placeholder";
            b.key     = category.key + ":placeholder";
            b.enabled = categoryEnabled;
            b.status  = "WAITING";
            b.cap     = capForBoss(b.name, category.key);
            list.add(b);
        }
        return list;
    }

    // ── Target selection ← PvP phase skip added ────────────────────────────────
    private Boss selectTarget(List<Boss> bosses) {
        for (Boss b : bosses) if (b.enabled && b.loot) return b;
        for (Boss b : bosses) {
            if (!b.enabled || !b.alive || empty(b.monsterId)) continue;
            if (b.cap > 0 && b.damage >= b.cap) continue;
            if (cappedBossSkipKeys.contains(b.key)) continue;
            // ── Skip if boss is currently in a PvP phase ──────────────────────
            if (isBossInPvpPhase(b.name)) {
                append("INFO", b.name + " is in a PvP phase — skipping this target.");
                continue;
            }
            return b;
        }
        return null;
    }

    // ── Direct-monster-safe enabled check ─────────────────────────────────────
    // The attack while loop checks "boss_enabled_" + b.key, but direct monsters
    // store their enabled state under "monster_enabled_<prefKey>" (without the
    // "monsters:" prefix). This helper resolves the correct key for each type.
    private boolean isBossEnabledForAttack(Boss b) {
        if (b.isDirectMonster) {
            String prefKey = b.key.startsWith("monsters:") ? b.key.substring(9) : b.key;
            return sp.getBoolean("monster_enabled_" + prefKey, false);
        }
        return sp.getBoolean("boss_enabled_" + b.key, false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ATTACK SEQUENCE  ← Artemis Mark + PvP mid-session check integrated
    // ═══════════════════════════════════════════════════════════════════════════
    private void processTarget(Boss b) throws Exception {
        if (b.loot) { append("INFO", b.name + ": loot detected"); return; }
        if (attacking) return;
        attacking = true;
        try {
            String userId = cookieValue("demon");
            if (empty(userId)) { append("ERROR","No demon cookie. Please reconnect/login first."); return; }

            // For direct monsters b.cap is set from "monster_cap_<prefKey>" in fetchDirectMonsters().
            // capForBoss() looks up "cap_monsters_<root>" — a different key — so it returns 0
            // for direct monsters, causing "no damage cap set" even when a cap IS configured.
            long damageCap = (b.isDirectMonster && b.cap > 0)
                ? b.cap
                : capForBoss(b.name, b.categoryKey);
            if (damageCap <= 0 && b.cap > 0) damageCap = b.cap; // final fallback
            // Last-resort: re-read directly from SharedPrefs in case b.cap was lost in transit
            if (damageCap <= 0 && b.isDirectMonster) {
                String prefKeyDirect = b.key.startsWith("monsters:") ? b.key.substring(9) : b.key;
                String capStored = sp.getString("monster_cap_" + prefKeyDirect, "0");
                long capReread = parseCap(capStored);
                if (capReread > 0) {
                    append("DIAG", "Cap re-read from prefs for [" + b.name + "]: \""
                        + capStored + "\" → " + fmtn(capReread) + " (b.cap was 0)");
                    damageCap = capReread;
                }
            }
            append("DIAG", "Cap check [" + b.name + "] b.cap=" + fmtn(b.cap)
                + " damageCap=" + fmtn(damageCap)
                + " isDirectMonster=" + b.isDirectMonster
                + " key=" + b.key);
            notifBossName = b.name; notifCap = damageCap; notifDamage = Math.max(0, b.damage);
            update("CRN Boss Bot • TARGET", notificationBody("Target: " + b.name), false);
            if (damageCap <= 0) { append("INFO","Skipping " + b.name + ": no damage cap set."); return; }

            long totalDamage  = b.damage > 0 ? b.damage : fetchDamage(b);
            long lastApiTotal = totalDamage;
            int  zeroDamageHits = 0;
            b.damage = totalDamage;
            damageByBoss.put(b.key, totalDamage);
            aliveDamageByBoss.put(b.key, totalDamage);
            notifDamage = totalDamage;
            saveBossesForUi(Collections.singletonList(b));
            broadcastUiOnly("DAMAGE", b.name + " " + fmtn(totalDamage) + " / " + fmtn(damageCap));
            update("CRN Boss Bot • ATTACK", notificationBody("Target: " + b.name), false);

            long remaining = Math.max(0, damageCap - totalDamage);
            append("INFO","Pre-damage: " + fmtn(totalDamage) + " / " + fmtn(damageCap)
                + "  remaining: " + fmtn(remaining));

            if (totalDamage >= damageCap) {
                sleep(800);
                long recheck = fetchDamage(b);
                if (recheck >= damageCap) {
                    b.damage = recheck;
                    damageByBoss.put(b.key, recheck);
                    aliveDamageByBoss.put(b.key, recheck);
                    cappedBossSkipKeys.add(b.key);
                    saveBossesForUi(Collections.singletonList(b));
                    append("INFO","Already capped. Skipping: " + b.name);
                    return;
                }
                totalDamage = recheck; lastApiTotal = totalDamage;
            }

            disableAutoFarmIfRunning();
            append("INFO","Joining battle: [" + b.categoryLabel + "] " + b.name);
            joinBattle(b.monsterId, userId);
            sleep(700 + new Random().nextInt(600));

            // ── Artemis Mark state ─────────────────────────────────────────────
            // Detects "artemis marked you for N turns" in attack responses.
            // Forces SLASH for the duration, bypassing all normal skill logic.
            // The counter is declared in the outer scope so it survives
            // stamina-potion `continue` cycles naturally.
            boolean isArtemis        = b.name.toLowerCase(Locale.US).contains("artemis");
            int     artemisMarkedTurns = 0;
            // ──────────────────────────────────────────────────────────────────

            int hitCount = 0;
            while (running && sp.getBoolean("global_enabled",false) && totalDamage < damageCap
                           && b.enabled) {

                // ── PvP phase mid-session guard ────────────────────────────────
                // Boss can transition to PvP phase during an active attack session.
                // Check on every hit and bail out cleanly.
                if (isBossInPvpPhase(b.name)) {
                    append("INFO", b.name + " transitioned to PvP phase mid-session — stopping.");
                    break;
                }
                // ── Direct monster mid-session disable guard ───────────────────
                // Re-reads the correct pref key so the user can toggle OFF during
                // an active attack and have it respected on the next hit.
                if (b.isDirectMonster) {
                    String dk = b.key.startsWith("monsters:") ? b.key.substring(9) : b.key;
                    if (!sp.getBoolean("monster_enabled_" + dk, false)) {
                        append("INFO", b.name + " disabled mid-session — stopping.");
                        break;
                    }
                } else if (!sp.getBoolean("boss_enabled_" + b.key, false)) {
                    append("INFO", b.name + " disabled mid-session — stopping.");
                    break;
                }
                // ──────────────────────────────────────────────────────────────

                // ── Skill selection — Artemis Mark overrides everything ─────────
                Skill configuredSkill = skillFromId(parseInt(sp.getString("skill_id","0"),0));
                Skill skill;

                if (isArtemis && artemisMarkedTurns > 0) {
                    // Force Slash (index SKILLS.length-1, skill_id=0, cost=1)
                    skill = SKILLS[SKILLS.length - 1];
                    artemisMarkedTurns--;
                    append("INFO","🛡️ Artemis Mark — forcing SLASH ("
                        + artemisMarkedTurns + " turns remain)");
                } else {
                    skill = configuredSkill;

                    int staminaNow = fetchStaminaForBattle(b.monsterId);
                    if (sp.getBoolean("enable_asterion",false) && staminaNow >= 0) {
                        int threshold = parseInt(sp.getString("asterion_stamina_threshold","0"),0);
                        if (threshold > 0 && staminaNow > 0 && staminaNow <= threshold) {
                            append("INFO","Asterion: stamina "+staminaNow+" <= "+threshold+". Trying potion.");
                            if (handleStaminaLogic(b.monsterId)) { sleep(900+new Random().nextInt(600)); continue; }
                        }
                    }

                    if (staminaNow >= 0 && staminaNow < effectiveCostForSkill(skill)) {
                        if (staminaNow <= 0) {
                            append("INFO","Stamina 0. Trying potion for "+b.name+".");
                            if (handleStaminaLogic(b.monsterId)) { sleep(900+new Random().nextInt(600)); continue; }
                            append("INFO","No potion available. Pausing "+b.name+"."); break;
                        }
                        Skill staminaSkill = selectAffordableSkill(staminaNow);
                        if (staminaSkill != null) {
                            if (effectiveCostForSkill(staminaSkill) != effectiveCostForSkill(skill))
                                append("INFO","Stamina control: "+skill.name+" -> "+staminaSkill.name);
                            skill = staminaSkill;
                        } else if (canUseStaminaPotionAtStamina(staminaNow)) {
                            append("INFO","No affordable skill for stamina "+staminaNow+". Trying potion.");
                            if (handleStaminaLogic(b.monsterId)) { sleep(900+new Random().nextInt(600)); continue; }
                            break;
                        } else {
                            append("INFO","No affordable skill, potion blocked by threshold. Waiting."); break;
                        }
                    }
                    // staminaNow is local — re-read below for remaining calc
                    if (skill == null) { append("INFO","No skill after guards. Stopping safely."); break; }
                }
                // ──────────────────────────────────────────────────────────────

                if (skill == null) { append("INFO","No skill after guards. Stopping safely."); break; }
                int effectiveCost = effectiveCostForSkill(skill);
                int staminaNow    = sp.getInt("live_stamina", -1); // best available value at this point
                if (configuredSkill != null && skill.skill_id != configuredSkill.skill_id
                        && !(isArtemis && artemisMarkedTurns >= 0))
                    append("INFO","Downshifting "+configuredSkill.name+" -> "+skill.name);
                append("INFO","Skill "+skill.name+" - Damage: "+fmtn(totalDamage)+" / "+fmtn(damageCap));

                AttackResult res = attackWithRetry(b.monsterId, skill, 3);
                if (res != null && res.stamina >= 0) saveLiveStamina(res.stamina);
                else if (staminaNow >= 0) saveLiveStamina(Math.max(0, staminaNow - effectiveCost));
                if (res == null || !res.hasJson) { append("INFO","Boss attack stopped: HTTP "+(res==null?0:res.status)); break; }

                String lowerMsg = nullToEmpty(res.message).toLowerCase(Locale.US);

                // ── Artemis Mark detection ─────────────────────────────────────
                // Runs on every hit response when fighting Artemis.
                // Regex is case-insensitive; captures turn count directly from server message.
                if (isArtemis) {
                    Matcher markMatcher = Pattern.compile(
                        "artemis marked you for (\\d+) turns?",
                        Pattern.CASE_INSENSITIVE
                    ).matcher(nullToEmpty(res.message));
                    if (markMatcher.find()) {
                        artemisMarkedTurns = Integer.parseInt(markMatcher.group(1));
                        append("INFO","⚠️ Artemis Mark detected! Forcing SLASH for next "
                            + artemisMarkedTurns + " turns.");
                    }
                }
                // ──────────────────────────────────────────────────────────────

                if (lowerMsg.contains("you are dead")) {
                    append("INFO","You are dead."); if (handleHpPotion()) { sleep(900+new Random().nextInt(600)); continue; }
                }
                if (lowerMsg.contains("auto farm is on")) {
                    append("INFO","Auto Farm blocked — disabling and retrying."); disableAutoFarmIfRunning(); sleep(2000); continue;
                }
                if (lowerMsg.contains("attack couldn't pierce") && lowerMsg.contains("divine shield")) {
                    append("INFO","Divine shield — disabling boss."); sp.edit().putBoolean("boss_enabled_"+b.key,false).apply(); break;
                }

                long hit = 0;
                long apiTotal = Math.max(res.totalDamage, res.leaderboardTotal);
                if (res.hitDamage > 0) hit = res.hitDamage;
                else if (res.logDamage > 0) hit = res.logDamage;
                else if (apiTotal > lastApiTotal) hit = apiTotal - lastApiTotal;
                lastApiTotal = Math.max(lastApiTotal, apiTotal);

                if (hit <= 0) {
                    append("INFO","[DBG] hitDmg="+res.hitDamage+" logDmg="+res.logDamage
                        +" apiTotal="+res.totalDamage+" msg=\""+compact(res.message)+"\"");
                    long after = fetchDamage(b);
                    if (after > totalDamage) { hit = after - totalDamage; append("INFO","Damage delta from post-hit check: +"+fmtn(hit)); }
                }

                totalDamage += Math.max(0, hit);
                if (hit <= 0) zeroDamageHits++; else { zeroDamageHits = 0; hitCount++; }
                if (hitCount > 0 && hitCount % 5 == 0) {
                    long serverDmg = fetchDamage(b);
                    if (serverDmg > totalDamage) {
                        append("INFO","Server sync: local="+fmtn(totalDamage)+" server="+fmtn(serverDmg)+" — correcting.");
                        totalDamage = serverDmg; b.damage = totalDamage;
                        damageByBoss.put(b.key, totalDamage); aliveDamageByBoss.put(b.key, totalDamage);
                        notifDamage = totalDamage;
                    }
                }
                b.damage = totalDamage;
                damageByBoss.put(b.key, totalDamage);
                aliveDamageByBoss.put(b.key, totalDamage);
                notifBossName = b.name; notifDamage = totalDamage; notifCap = damageCap;
                saveBossesForUi(Collections.singletonList(b));
                broadcastUiOnly("DAMAGE", b.name+" "+fmtn(totalDamage)+" / "+fmtn(damageCap));
                update("CRN Boss Bot • ATTACK", notificationBody("STA "+sp.getInt("live_stamina",-1)), false);
                append("INFO",(hit>0 ? skill.name+" dealt +"+fmtn(hit)+" damage" : "Attack failed...")+" Total: "+fmtn(totalDamage)+" / "+fmtn(damageCap));

                if (zeroDamageHits >= 3) { append("INFO","3 zero-damage hits. Stopping to prevent stamina burn."); break; }
                if (totalDamage >= damageCap) { append("INFO","Cap reached: "+fmtn(totalDamage)+" / "+fmtn(damageCap)); cappedBossSkipKeys.add(b.key); break; }

                int remainingStamina = res.stamina >= 0 ? res.stamina
                    : (staminaNow >= 0 ? Math.max(0, staminaNow - effectiveCost) : -1);
                if (remainingStamina >= 0) saveLiveStamina(remainingStamina);
                if (remainingStamina == 0) {
                    append("INFO","Stamina 0. Trying potion...");
                    if (!handleStaminaLogic(b.monsterId)) { append("INFO","No usable potion. Pausing attack."); break; }
                    continue;
                }
                sleep(1200 + new Random().nextInt(1800));
            }
        } finally { attacking = false; }
    }

    // ── Stamina ────────────────────────────────────────────────────────────────
    private int fetchStamina() {
        try { String r=get(AUTO_STATUS_URL+"?_="+System.currentTimeMillis(),BASE+"/game_dash.php"); int v=parseLiveStamina(r); if(v>=0){saveLiveStamina(v);return v;} } catch(Exception e){append("ERROR","Stamina status: "+e.getMessage());}
        try { String r=get(BASE+"/game_dash.php?_="+System.currentTimeMillis(),BASE+"/game_dash.php"); int v=parseLiveStamina(r); if(v>=0){saveLiveStamina(v);return v;} } catch(Exception e){append("ERROR","Stamina dash: "+e.getMessage());}
        return sp.getInt("live_stamina",-1);
    }
    private int parseLiveStamina(String r) {
        if (r==null) return -1;
        String val = firstNonEmpty(
            first(r,"(?is)<span[^>]+id=[\"']stamina_span[\"'][^>]*>\\s*([0-9,]+)"),
            first(r,"(?i)data-current-stamina=[\"']?([0-9,]+)"),
            first(r,"(?i)data-stamina=[\"']?([0-9,]+)"),
            first(r,"(?i)\"current_?stamina\"\\s*:\\s*\"?([0-9,]+)"),
            first(r,"(?i)\"stamina\"\\s*:\\s*\"?([0-9,]+)"),
            first(r,"(?i)stamina[^0-9]{0,40}([0-9,]+)\\s*/\\s*[0-9,]+")
        );
        if (!empty(val)) return (int)parseLong(val);
        return -1;
    }
    private int fetchStaminaForBattle(String monsterId) {
        try { String r=get(BATTLE_URL+"?id="+enc(monsterId)+"&_="+System.currentTimeMillis(),BATTLE_URL+"?id="+enc(monsterId)); int v=parseLiveStamina(r); if(v>=0){saveLiveStamina(v);return v;} } catch(Exception e){append("ERROR","Battle stamina: "+e.getMessage());}
        return fetchStamina();
    }
    private void saveLiveStamina(int stamina) {
        int clean = Math.max(0,stamina);
        sp.edit().putInt("live_stamina",clean).apply();
        broadcastUiOnly("STAMINA","STA "+clean);
        if (!empty(notifBossName)&&notifCap>0) update("CRN Boss Bot • RUNNING",notificationBody("STA "+clean),false);
    }

    // ── Potions ────────────────────────────────────────────────────────────────
    private boolean canUseStaminaPotionAtStamina(int stamina) {
        if (!sp.getBoolean("auto_potion",true)) return false;
        if (stamina<=0) return true;
        if (!sp.getBoolean("enable_asterion",false)) return false;
        int threshold=parseInt(sp.getString("asterion_stamina_threshold","0"),0);
        return threshold>0 && stamina<=threshold;
    }
    private boolean handleStaminaLogic(String monsterId) {
        int live=fetchStaminaForBattle(monsterId);
        if (!canUseStaminaPotionAtStamina(live)) { append("INFO","Potion skipped: live stamina "+live+" above threshold."); return false; }
        PotionSet ps=fetchPotions();
        if (ps.large==null&&ps.full==null) { append("INFO","No stamina potion IDs after re-scan."); return false; }
        if (ps.large!=null && usedLsp<parseInt(sp.getString("lsp_limit","999"),999)) {
            Object ok=tryPotionWithRetries("LSP",ps.large,3,monsterId);
            if (Boolean.TRUE.equals(ok)){usedLsp++;sp.edit().putInt("used_lsp",usedLsp).apply();append("INFO","LSP used ("+usedLsp+"/"+sp.getString("lsp_limit","999")+").");return true;}
            if ("SKIPPED_STAMINA_OK".equals(ok)) return true;
        }
        if (ps.full!=null && usedFsp<parseInt(sp.getString("fsp_limit","999"),999)) {
            Object ok=tryPotionWithRetries("FSP",ps.full,2,monsterId);
            if (Boolean.TRUE.equals(ok)){usedFsp++;sp.edit().putInt("used_fsp",usedFsp).apply();append("INFO","FSP used ("+usedFsp+"/"+sp.getString("fsp_limit","999")+").");return true;}
            if ("SKIPPED_STAMINA_OK".equals(ok)) return true;
        }
        append("INFO","No usable potion within limits. LSP "+usedLsp+"/"+sp.getString("lsp_limit","999")+" FSP "+usedFsp+"/"+sp.getString("fsp_limit","999"));
        return false;
    }
    private Object tryPotionWithRetries(String type, PotionInfo potion, int maxAttempts, String monsterId) {
        for (int attempt=1; running&&attempt<=maxAttempts&&potion!=null&&!empty(potion.invId); attempt++) {
            String label=("LSP".equals(type)?"Large Stamina Potion":"Full Stamina Potion")+(attempt==1?"":" retry "+attempt+"/"+maxAttempts);
            Object ok=useStaminaPotionByInvId(potion.invId,label,monsterId);
            if (Boolean.TRUE.equals(ok)||"SKIPPED_STAMINA_OK".equals(ok)) return ok;
            if (attempt<maxAttempts){append("INFO",type+" failed. Re-syncing before retry "+(attempt+1)+"…"); PotionSet fresh=fetchPotions(); potion="LSP".equals(type)?fresh.large:fresh.full; try{sleep(900+new Random().nextInt(900));}catch(Exception ignored){}}
        }
        append("INFO",type+" failed after "+maxAttempts+" attempt(s)."); return false;
    }
    private Object useStaminaPotionByInvId(String invId, String label, String monsterId) {
        int liveBefore=fetchStaminaForBattle(monsterId);
        if (!canUseStaminaPotionAtStamina(liveBefore)){append("INFO",label+" cancelled: stamina already ok."); return "SKIPPED_STAMINA_OK";}
        try {
            append("INFO","Using "+label+" inv_id="+invId);
            String text=post(USE_ITEM_URL,"inv_id="+enc(invId),BASE+"/game_dash.php");
            String lower=text.toLowerCase(Locale.US);
            boolean failed=lower.contains("item not found")||lower.contains("not usable")||lower.contains("no item")||lower.contains("invalid")||lower.contains("already full")||lower.contains("cannot use")||lower.contains("failed")||lower.contains("error");
            if (failed){append("INFO",label+" use failed.");return false;}
            int liveAfter=fetchStaminaForBattle(monsterId); if(liveAfter>=0) saveLiveStamina(liveAfter);
            append("INFO",label+" used."); return true;
        } catch(Exception e){append("ERROR",label+" request failed: "+e.getMessage()); return false;}
    }
    private boolean handleHpPotion() {
        int limit=parseInt(sp.getString("hp_potion_limit",sp.getString("hp_limit","999")),999);
        if (usedHp>=limit){append("INFO","HP potion limit reached ("+usedHp+"/"+limit+").");return false;}
        PotionSet ps=fetchPotions();
        if (ps.hp==null){append("INFO","No HP potion ID.");return false;}
        try {
            append("INFO","Using Full HP Potion inv_id="+ps.hp.invId);
            String text=post(HP_POTION_URL,"inv_id="+enc(ps.hp.invId),BATTLE_URL);
            String lower=text.toLowerCase(Locale.US);
            boolean failed=lower.contains("item not found")||lower.contains("not usable")||lower.contains("invalid")||lower.contains("failed")||lower.contains("error");
            if (failed){append("INFO","HP Potion use failed.");return false;}
            usedHp++; sp.edit().putInt("used_hp",usedHp).apply();
            append("INFO","HP Potion used ("+usedHp+"/"+limit+")."); return true;
        } catch(Exception e){append("ERROR","HP potion request failed: "+e.getMessage()); return false;}
    }
    private PotionSet fetchPotions() {
        PotionSet ps=new PotionSet();
        try { parsePotionsInto(ps,get(BATTLE_URL,BASE+"/game_dash.php")); } catch(Exception e){append("ERROR","Battle potion sync: "+e.getMessage());}
        if (ps.large==null&&ps.full==null&&ps.hp==null) {
            try { parsePotionsInto(ps,get(BASE+"/game_dash.php",BASE+"/game_dash.php")); } catch(Exception e){append("ERROR","Potion fallback sync: "+e.getMessage());}
        }
        if (ps.large!=null||ps.full!=null||ps.hp!=null) {
            List<String> ids=new ArrayList<>();
            if(ps.large!=null) ids.add("LSP="+ps.large.invId);
            if(ps.full!=null)  ids.add("FSP="+ps.full.invId);
            if(ps.hp!=null)    ids.add("HP="+ps.hp.invId);
            append("INFO","Potion IDs: "+joinStrings(ids,", "));
        }
        return ps;
    }
    private void parsePotionsInto(PotionSet ps, String html) {
        String src=html==null?"":html;
        Matcher cards=Pattern.compile("(?is)<[^>]*class=[\"'][^\"']*potion-card[^\"']*[\"'][^>]*>.*?(?=<[^>]*class=[\"'][^\"']*potion-card[^\"']*[\"'][^>]*>|</body>|$)").matcher(src);
        while(cards.find()) parsePotionCard(ps,cards.group());
        Matcher data=Pattern.compile("(?is)<[^>]*(?:data-inv-id|data-inv_id|data-invId)=[\"']?(\\d+)[\"']?[^>]*>.{0,1600}?(?=<[^>]*(?:data-inv-id|data-inv_id|data-invId)=|</body>|$)").matcher(src);
        while(data.find()) classifyPotion(ps,data.group(1),readablePotionName(data.group()),data.group());
    }
    private void parsePotionCard(PotionSet ps, String cardHtml) {
        if(empty(cardHtml)) return;
        String inv=first(cardHtml,"(?is)(?:data-inv-id|data-inv_id|data-invId)=[\"']?(\\d+)[\"']?");
        if(empty(inv)) return;
        classifyPotion(ps,inv,readablePotionName(cardHtml),cardHtml);
    }
    private String readablePotionName(String block) {
        String name=first(block,"(?is)<[^>]*class=[\"'][^\"']*potion-name[^\"']*[\"'][^>]*>\\s*(?:<span[^>]*>)?\\s*([^<]+)");
        if(empty(name)) name=first(block,"(?is)<img[^>]*(?:alt|title)=[\"']([^\"']+)[\"']");
        if(empty(name)) name=stripTags(block);
        return name==null?"":name.trim();
    }
    private void classifyPotion(PotionSet ps, String inv, String name, String block) {
        if(empty(inv)) return;
        String lower=((name==null?"":name)+" "+stripTags(block)).toLowerCase(Locale.US);
        if(lower.contains("large stamina potion"))  ps.large=new PotionInfo(inv,"Large Stamina Potion");
        else if(lower.contains("full stamina potion")) ps.full=new PotionInfo(inv,"Full Stamina Potion");
        else if(lower.contains("full hp potion")||lower.contains("full hp")) ps.hp=new PotionInfo(inv,"Full HP Potion");
    }

    // ── Attack ─────────────────────────────────────────────────────────────────
    private AttackResult attackWithRetry(String monsterId, Skill skill, int maxRetries) {
        AttackResult last=null;
        for (int i=1; running&&i<=maxRetries; i++) {
            last=performAttack(monsterId,skill);
            if (last!=null&&last.hasJson) return last;
            append("INFO","Attack failed ("+i+"/"+maxRetries+"): HTTP "+(last==null?0:last.status));
            try{sleep(1200+new Random().nextInt(1300));}catch(Exception ignored){}
        }
        return last;
    }
    private AttackResult performAttack(String monsterId, Skill skill) {
        AttackResult ar=new AttackResult();
        if(skill==null||skill.stamina_cost<=0){append("INFO","Attack blocked: invalid skill payload");return ar;}
        try {
            String body="monster_id="+enc(monsterId)+"&skill_id="+enc(String.valueOf(skill.skill_id))+"&stamina_cost="+enc(String.valueOf(skill.stamina_cost));
            HttpResult hr=postRaw(DMG_URL,body,BATTLE_URL+"?id="+enc(monsterId));
            ar.status=hr.status;
            String r=hr.text;
            ar.hasJson=(hr.status==200||hr.status==400)&&looksJson(r);
            ar.message=unescapeJson(first(r,"(?is)\"message\"\\s*:\\s*\"(.*?)\"")); if(empty(ar.message)) ar.message=compact(r);
            ar.hitDamage=parseLong(first(r,"(?i)\"hitDmg\"\\s*:\\s*([0-9,]+)")); if(ar.hitDamage==0) ar.hitDamage=parseLong(first(ar.message,"(?i)([0-9,]+)\\s*damage"));
            ar.logDamage=parseLong(first(r,"(?i)\"DAMAGE\"\\s*:\\s*\"?([0-9,]+)"));
            ar.totalDamage=parseLong(first(r,"(?i)\"totaldmg(?:e)?dealt\"\\s*:\\s*\"?([0-9,]+)"));
            ar.leaderboardTotal=parseLong(first(r,"(?i)\"DAMAGE_DEALT\"\\s*:\\s*\"?([0-9,]+)"));
            ar.stamina=(int)parseLong(first(r,"(?i)\"stamina\"\\s*:\\s*\"?([0-9,]+)")); if(ar.stamina==0&&!r.matches("(?is).*\"stamina\"\\s*:.*")) ar.stamina=-1;
            return ar;
        } catch(Exception e){append("ERROR","Attack request failed: "+e.getMessage()); return ar;}
    }
    private boolean joinBattle(String monsterId, String userId) {
        try { String r=post(JOIN_URL,"monster_id="+enc(monsterId)+"&user_id="+enc(userId),BASE+"/active_wave.php"); String l=r.toLowerCase(Locale.US); boolean ok=l.contains("successfully joined")||l.contains("already joined")||l.contains("already in"); append("INFO",ok?"Join battle OK":"Join battle response: "+compact(r)); return ok; } catch(Exception e){append("ERROR","Join battle failed: "+e.getMessage()); return false;}
    }
    private void disableAutoFarmIfRunning() {
        try { String st=get(AUTO_STATUS_URL,BASE+"/game_dash.php"); if(st.toLowerCase(Locale.US).contains("on")||st.contains("1")||st.toLowerCase(Locale.US).contains("true")){post(AUTO_ACTIONS_URL,"action=toggle&enabled=0",BASE+"/game_dash.php");append("INFO","Auto Farm disabled.");}} catch(Exception e){append("ERROR","Auto Farm toggle failed: "+e.getMessage());}
    }
    private long fetchDamage(Boss b) {
        try { String r=get(BATTLE_URL+"?id="+enc(b.monsterId),BATTLE_URL+"?id="+enc(b.monsterId)); long v=parseLong(firstNonEmpty(first(r,"(?i)data-userdmg=[\\\"']?([0-9,]+)"),first(r,"(?i)data-user-dmg=[\\\"']?([0-9,]+)"),first(r,"(?i)data-damage=[\\\"']?([0-9,]+)"),first(r,"(?i)\"totaldmg(?:e)?dealt\"\\s*:\\s*\"?([0-9,]+)"),first(r,"(?i)\"DAMAGE_DEALT\"\\s*:\\s*\"?([0-9,]+)"))); return v>0?v:b.damage; } catch(Exception e){append("ERROR",b.name+" damage recheck: "+e.getMessage()); return b.damage;}
    }

    private String parseTimerFromCards(String card1, String card2) {
        String summon=card1==null?"":card1; String live=card2==null?"":card2; String combined=summon+live;
        String aliveVal=firstNonEmpty(attr(summon,"data-alive"),attr(summon,"data_alive"));
        boolean isAlive="1".equals(aliveVal);
        if (!isAlive) {
            String nextTs=firstNonEmpty(attr(summon,"data-next-ts"),attr(combined,"data-next-ts"));
            if (!empty(nextTs)){long ts=parseLong(nextTs);if(ts>0){long nowSec=System.currentTimeMillis()/1000;long diff=ts-nowSec;if(diff>0&&diff<86400L*30) return "Spawns in "+formatSecs(diff);}}
            String spanTimer=first(combined,"(?is)class=[\"'][^\"']*auto-summon-timer[^\"']*[\"'][^>]*>\\s*([^<]+)");
            if (!empty(spanTimer)) return "Spawns in "+spanTimer.trim();
            String raw=firstNonEmpty(attr(combined,"data-timer"),attr(combined,"data-respawn"),attr(combined,"data-respawn-time"),attr(combined,"data-countdown"),attr(combined,"data-time-remaining"));
            if (!empty(raw)){if(raw.matches("[0-9]{1,3}:[0-9]{2}:[0-9]{2}")||raw.matches("[0-9]{1,2}:[0-9]{2}")) return "Spawns in "+raw; long secs=parseLong(raw);if(secs>0&&secs<86400L*7) return "Spawns in "+formatSecs(secs);}
        }
        String dieTimer=first(combined,"(?is)id=[\"']autoDieTimer[\"'][^>]*>\\s*([0-9:]+)");
        if (!empty(dieTimer)){try{String[] tp=dieTimer.trim().split(":");long secs=tp.length==3?Long.parseLong(tp[0])*3600+Long.parseLong(tp[1])*60+Long.parseLong(tp[2]):Long.parseLong(tp[0])*60+Long.parseLong(tp[1]);return "Auto dies in "+formatSecs(secs);}catch(Exception ignored){return "Auto dies in "+dieTimer.trim();}}
        return "";
    }
    private String formatSecs(long secs){if(secs<=0)return "";long h=secs/3600,m=(secs%3600)/60,s=secs%60;if(h>0)return h+"h "+m+"m";if(m>0)return m+"m "+s+"s";return s+"s";}

    // ── Skill helpers ──────────────────────────────────────────────────────────
    private Skill selectAffordableSkill(int stamina) { for(Skill s:SKILLS) if(effectiveCostForSkill(s)<=stamina) return s; return null; }
    private Skill skillFromId(int id) { for(Skill s:SKILLS) if(s.skill_id==id) return s; return SKILLS[SKILLS.length-1]; }
    private int effectiveCostForSkill(Skill skill) { int base=skill==null?0:skill.stamina_cost; return sp.getBoolean("enable_asterion",false)?Math.max(1,(int)Math.round(base*2.5d)):base; }

    // ── Cap helpers ────────────────────────────────────────────────────────────
    private long parseCap(String v) {
        if(v==null) return 0;
        String s=v.trim().toLowerCase(Locale.US).replace(",","");
        try { double mult=1; if(s.endsWith("k")){mult=1_000d;s=s.substring(0,s.length()-1);} else if(s.endsWith("m")){mult=1_000_000d;s=s.substring(0,s.length()-1);} else if(s.endsWith("b")){mult=1_000_000_000d;s=s.substring(0,s.length()-1);} return (long)(Double.parseDouble(s)*mult); } catch(Exception e){return 0;}
    }
    private static String bossRootKey(String name) {
        if(name==null||name.isEmpty()) return "";
        String[] parts=name.split("[,\\-]",2);
        return norm(parts[0].trim());
    }

    // ── UI broadcast ── saveBossesForUi now saves slot 9 = currentPhaseName ───
    private void saveBossesForUi(List<Boss> bosses) {
        LinkedHashMap<String,String[]> merged=new LinkedHashMap<>();
        String existing=sp.getString("last_bosses","");
        if (!empty(existing)) {
            for(String line:existing.split("\n")) {
                String[] p=line.split("\\|",-1);
                if(p.length>=6) merged.put((p[0]+":"+norm(p[1])).toLowerCase(Locale.US),p);
            }
        }
        for (Boss b:bosses) {
            // Slot layout: 0=catLabel 1=name 2=status 3=damage 4=cap 5=enabled
            //              6=image   7=catKey  8=timer  9=currentPhaseName
            //              10=isDirectMonster
            String[] row=new String[]{
                b.categoryLabel, b.name, b.status,
                String.valueOf(b.damage), String.valueOf(b.cap), String.valueOf(b.enabled),
                nullToEmpty(b.image), nullToEmpty(b.categoryKey),
                nullToEmpty(b.timer), nullToEmpty(b.currentPhaseName),
                b.isDirectMonster ? "1" : "0"
            };
            merged.put((b.categoryLabel+":"+norm(b.name)).toLowerCase(Locale.US),row);
        }
        StringBuilder sb=new StringBuilder();
        for (String[] row:merged.values()) {
            if(sb.length()>0) sb.append("\n");
            sb.append(row[0]).append("|").append(row[1]).append("|").append(row[2])
              .append("|").append(row[3]).append("|").append(row[4]).append("|").append(row[5])
              .append("|").append(row.length>6?row[6]:"")
              .append("|").append(row.length>7?row[7]:"")
              .append("|").append(row.length>8?row[8]:"")
              .append("|").append(row.length>9?row[9]:"")
              .append("|").append(row.length>10?row[10]:"0");
        }
        sp.edit().putString("last_bosses",sb.toString()).apply();
    }
    private void broadcastUiOnly(String state, String msg){Intent i=new Intent(ACTION_STATUS);i.setPackage(getPackageName());i.putExtra("state",state);i.putExtra("message",msg==null?"":msg);i.putExtra("logs",joinLogs());sendBroadcast(i);}
    private void sendStatus(String state,String msg,List<Boss> bosses){update("CRN Boss Bot • "+state,notificationBody(msg),state.equals("TARGET"));sp.edit().putString("ui_state",state).putString("ui_message",msg==null?"":msg).apply();Intent i=new Intent(ACTION_STATUS);i.setPackage(getPackageName());i.putExtra("state",state);i.putExtra("message",msg);i.putExtra("logs",joinLogs());if(bosses!=null)saveBossesForUi(bosses);sendBroadcast(i);}
    private void append(String type,String msg){String line=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+" ["+type+"] "+msg;synchronized(logs){logs.add(line);while(logs.size()>500)logs.remove(0);}sp.edit().putString("logs",joinLogs()).apply();Intent i=new Intent(ACTION_STATUS);i.setPackage(getPackageName());i.putExtra("state","LOG");i.putExtra("message",msg);i.putExtra("logs",joinLogs());sendBroadcast(i);}
    private void clearLogs(){synchronized(logs){logs.clear();}sp.edit().putString("logs","").apply();Intent i=new Intent(ACTION_STATUS);i.setPackage(getPackageName());i.putExtra("state","LOG");i.putExtra("message","Logs cleared");i.putExtra("logs","");sendBroadcast(i);}
    private String notificationBody(String msg){StringBuilder b=new StringBuilder();if(!empty(msg))b.append(msg);int sta=sp==null?-1:sp.getInt("live_stamina",-1);if(sta>=0){if(b.length()>0)b.append("\n");b.append("STA: ").append(sta);}if(!empty(notifBossName)&&notifCap>0){if(b.length()>0)b.append("\n");b.append(shortText(notifBossName,36)).append(": ").append(fmtn(notifDamage)).append(" / ").append(fmtn(notifCap));}return b.length()==0?"Service running":b.toString();}
    private String joinLogs(){synchronized(logs){return android.text.TextUtils.join("\n",logs);}}
    private String fmtn(long n){return String.format(Locale.US,"%,d",n);}
    private String shortText(String s,int max){return s==null?"":(s.length()<=max?s:s.substring(0,Math.max(0,max-1))+"…");}

    // ── HTTP ───────────────────────────────────────────────────────────────────
    private String get(String url,String referer) throws Exception{return request("GET",url,null,referer).text;}
    private String post(String url,String body,String referer) throws Exception{return request("POST",url,body,referer).text;}
    private HttpResult postRaw(String url,String body,String referer) throws Exception{return request("POST",url,body,referer);}
    private HttpResult request(String method,String url,String body,String referer) throws Exception {
        String currentUrl=url,currentMethod=method,currentBody=body;
        for(int redirects=0;redirects<=5;redirects++){
            HttpURLConnection c=(HttpURLConnection)new URL(currentUrl).openConnection();c.setInstanceFollowRedirects(false);c.setRequestMethod(currentMethod);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Accept","*/*");c.setRequestProperty("Accept-Language","en-US,en;q=0.9");c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");c.setRequestProperty("Referer",referer==null?BASE+"/":referer);String cookie=CookieManager.getInstance().getCookie(BASE);if(!empty(cookie))c.setRequestProperty("Cookie",cookie);if(currentBody!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");try(OutputStream os=c.getOutputStream()){os.write(currentBody.getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();String setCookie=c.getHeaderField("Set-Cookie");if(!empty(setCookie)){CookieManager.getInstance().setCookie(BASE,setCookie);CookieManager.getInstance().flush();}
            if(code==301||code==302||code==303||code==307||code==308){String location=c.getHeaderField("Location");if(empty(location))throw new IOException("HTTP "+code+" redirect without Location");URL next=new URL(new URL(currentUrl),location);append("INFO","HTTP redirect "+code+" -> "+next.getPath());currentUrl=next.toString();if(code==303||((code==301||code==302)&&"POST".equals(currentMethod))){currentMethod="GET";currentBody=null;}continue;}
            InputStream is=code>=400?c.getErrorStream():c.getInputStream();String text=readAll(is);if(code==429){append("ERROR","429 cooldown");sleep(5000);}
            if(code>=400&&!("POST".equals(currentMethod)&&DMG_URL.equals(currentUrl)&&code==400&&looksJson(text)))throw new IOException("HTTP "+code+" "+compact(text));
            return new HttpResult(code,text);
        }
        throw new IOException("Too many redirects: "+url);
    }

    // ── Notifications ──────────────────────────────────────────────────────────
    private Notification notification(String title,String body,boolean alert){Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Intent stop=new Intent(this,BotForegroundService.class).setAction(ACTION_STOP);PendingIntent stopPi=PendingIntent.getService(this,2,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,alert?CH_ALERT:CH_MAIN):new Notification.Builder(this);b.setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body)).setSmallIcon(R.drawable.ic_stat_crn).setContentIntent(pi).setOngoing(!alert).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Stop",stopPi);b.setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE).setShowWhen(true).setOnlyAlertOnce(!alert).setPriority(alert?Notification.PRIORITY_HIGH:Notification.PRIORITY_DEFAULT);if(Build.VERSION.SDK_INT>=31)b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);return b.build();}
    private void update(String title,String body,boolean alert){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(alert?101:NOTIF_ID,notification(title,body,alert));if(alert&&sp.getBoolean("alerts",true))maybeAlert();}
    private void maybeAlert(){long now=System.currentTimeMillis();if(now-lastAlertMs<60000)return;lastAlertMs=now;try{Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE);if(v!=null)v.vibrate(VibrationEffect.createOneShot(450,VibrationEffect.DEFAULT_AMPLITUDE));RingtoneManager.getRingtone(getApplicationContext(),android.provider.Settings.System.DEFAULT_NOTIFICATION_URI).play();}catch(Exception ignored){}}
    private void createChannels(){if(Build.VERSION.SDK_INT<26)return;NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);NotificationChannel mainCh=new NotificationChannel(CH_MAIN,"CRN Boss Bot Running",NotificationManager.IMPORTANCE_DEFAULT);mainCh.setSound(null,null);mainCh.setShowBadge(false);mainCh.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);nm.createNotificationChannel(mainCh);NotificationChannel alertCh=new NotificationChannel(CH_ALERT,"CRN Boss Alerts",NotificationManager.IMPORTANCE_HIGH);alertCh.enableVibration(true);alertCh.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);alertCh.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());nm.createNotificationChannel(alertCh);}
    private void releaseLocks(){try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){}try{if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}catch(Exception ignored){}wakeLock=null;wifiLock=null;}
    private long nextDelayMs(){long baseMs=Math.max(10,parseInt(sp.getString("scan_interval","60"),60))*1000L;if(!sp.getBoolean("smart_delay",true))return baseMs;return baseMs+1500+new Random().nextInt(2500);}

    // ── HTML parsing utils ─────────────────────────────────────────────────────
    private static List<String> extractClassBlocks(String html,String className){List<String> out=new ArrayList<>();if(html==null)return out;Pattern startPat=Pattern.compile("(?is)<div\\b(?=[^>]*class=[\"'][^\"']*"+Pattern.quote(className)+"[^\"']*[\"'])[^>]*>");Matcher m=startPat.matcher(html);while(m.find()){int start=m.start(),pos=m.end(),depth=1;Matcher tags=Pattern.compile("(?is)</?div\\b[^>]*>").matcher(html);tags.region(pos,html.length());int end=-1;while(tags.find()){String tag=tags.group().toLowerCase(Locale.US);if(tag.startsWith("</"))depth--;else depth++;if(depth==0){end=tags.end();break;}}if(end>start)out.add(html.substring(start,end));}return out;}
    private static String attr(String html,String name){if(html==null||name==null)return "";Matcher m=Pattern.compile("(?is)\\b"+Pattern.quote(name)+"\\s*=\\s*([\"'])(.*?)\\1").matcher(html);if(m.find())return cleanHtml(m.group(2));m=Pattern.compile("(?is)\\b"+Pattern.quote(name)+"\\s*=\\s*([^\\s>]+)").matcher(html);return m.find()?cleanHtml(m.group(1)):"";}
    private static String firstNonEmpty(String... values){if(values==null)return "";for(String v:values)if(!empty(v))return v.trim();return "";}
    private static String first(String s,String re){Matcher m=Pattern.compile(re).matcher(s==null?"":s);return m.find()?m.group(1):"";}
    private static String cleanHtml(String s){return(s==null?"":s).replaceAll("(?is)<script.*?</script>","").replaceAll("<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").trim();}
    private static String norm(String s){return(s==null?"":s).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");}
    private static String stripTags(String s){if(s==null)return "";return s.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").trim();}
    private static String joinStrings(List<String> items,String sep){StringBuilder sb=new StringBuilder();for(String item:items){if(sb.length()>0)sb.append(sep);sb.append(item);}return sb.toString();}
    private static long parseLong(String s){try{return Long.parseLong((s==null?"0":s).replaceAll("[^0-9]",""));}catch(Exception e){return 0;}}
    private static int parseInt(String s,int d){try{return Integer.parseInt(s.trim());}catch(Exception e){return d;}}
    private static boolean empty(String s){return s==null||s.trim().isEmpty();}
    private static String enc(String s)throws UnsupportedEncodingException{return URLEncoder.encode(s==null?"":s,"UTF-8");}
    private static String compact(String s){String x=(s==null?"":s).replaceAll("\\s+"," ").trim();return x.length()>100?x.substring(0,100):x;}
    private static String nullToEmpty(String s){return s==null?"":s;}
    private static boolean looksJson(String s){String x=s==null?"":s.trim();return x.startsWith("{")||x.startsWith("[");}
    private static String unescapeJson(String s){return s==null?"":s.replace("\\n","\n").replace("\\\"","\"").replace("\\/","/" );}
    private static String readAll(InputStream is)throws IOException{if(is==null)return "";ByteArrayOutputStream out=new ByteArrayOutputStream();byte[]b=new byte[8192];int n;while((n=is.read(b))>0)out.write(b,0,n);return out.toString("UTF-8");}
    private static void sleep(long ms)throws InterruptedException{Thread.sleep(ms);}
    private String cookieValue(String name){String cookie=CookieManager.getInstance().getCookie(BASE);if(cookie==null)return "";for(String part:cookie.split(";")){String[]kv=part.trim().split("=",2);if(kv.length==2&&kv[0].equals(name))return kv[1];}return "";}
}
