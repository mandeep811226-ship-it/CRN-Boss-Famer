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

public class BotForegroundService extends Service {
    public static final String ACTION_START      = "START";
    public static final String ACTION_STOP       = "STOP";
    public static final String ACTION_STATUS     = "com.crn.bossbot.STATUS";
    public static final String ACTION_CLEAR_LOGS = "CLEAR_LOGS";
    public static final String ACTION_SCAN_ONCE  = "SCAN_ONCE";
    public static final String ACTION_RELOAD_WAVES = "RELOAD_WAVES";

    private static final String PREF_CUSTOM_WAVES = "custom_waves_json";

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
        new Category("grakthar", "Grakthar",  BASE + "/active_wave.php?gate=3&wave=8"),
        new Category("olympus",  "Olympus",   BASE + "/active_wave.php?gate=5&wave=9"),
        new Category("hermes", "Hermes",  BASE + "/active_wave.php?gate=5&wave=10"),
    };

    /**
     * Builds the full category list: builtin + user-added custom waves.
     * Called fresh on every scan loop so new waves added by the user
     * are picked up immediately without restarting the service.
     */
    private Category[] buildCategories() {
        List<Category> list = new ArrayList<>(Arrays.asList(BUILTIN_CATEGORIES));
        String raw = sp.getString(PREF_CUSTOM_WAVES, "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                String[] parts = line.split("\\|", -1);
                // parts: prefKey | label | url | emoji
                if (parts.length >= 3 && !empty(parts[2])) {
                    list.add(new Category(parts[0], parts[1], parts[2]));
                }
            }
        }
        return list.toArray(new Category[0]);
    }

    // ── Data classes ───────────────────────────────────────────────────────────
    static class Boss {
        String categoryKey, categoryLabel, name, image, monsterId, battleId, key, status;
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
        if (ACTION_RELOAD_WAVES.equals(action)) {
            // Custom waves are read fresh on every scan via buildCategories() — nothing extra needed
            append("INFO", "Wave list reloaded — will take effect on next scan.");
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

    // ── Fetch & parse ──────────────────────────────────────────────────────────
    private List<Boss> fetchBosses() throws Exception {
        List<Boss> all = new ArrayList<>();
        // buildCategories() is called fresh every scan — picks up user-added waves immediately
        for (Category c : buildCategories()) {
            boolean catEnabled = sp.getBoolean("enable_" + c.key, true);
            String html = get(c.url, c.url);
            List<Boss> parsed = parseBosses(html, c, catEnabled);
            all.addAll(parsed);
            append("INFO", c.label + " scan: " + parsed.size() + " boss cards");
        }
        return all;
    }

    private List<Boss> parseBosses(String html, Category category, boolean categoryEnabled) {
        List<Boss> list = new ArrayList<>();

        Map<String, String> liveByName = new HashMap<>();
        for (String card : extractClassBlocks(html, "monster-card")) {
            if ("1".equals(attr(card, "data-dead"))) continue;
            String name = firstNonEmpty(
                attr(card, "data-name"),
                cleanHtml(first(card, "(?is)<h[1-6][^>]*>(.*?)</h[1-6]>")),
                cleanHtml(first(card, "(?is)<strong[^>]*>(.*?)</strong>")),
                cleanHtml(first(card, "(?is)<div[^>]+class=[\"'][^\"']*(?:name|title)[^\"']*[\"'][^>]*>(.*?)</div>"))
            );
            // ── phase-safe key: use root word before first comma or dash ──────
            String key = bossRootKey(name);
            if (empty(key)) continue;
            String existing = liveByName.get(key);
            boolean isBossFlagged = "1".equals(attr(card, "data-boss"));
            if (existing == null || isBossFlagged) liveByName.put(key, card);
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

            // ── live card match uses root key so phases map to the same slot ──
            String rootKey = bossRootKey(b.name);
            String live    = liveByName.get(rootKey);

            // pref key also uses root key — same across all phases of a boss
            b.key = category.key + ":" + rootKey;

            String lowerSummon = summon.toLowerCase(Locale.US);
            String aliveAttr   = firstNonEmpty(attr(summon,"data-alive"), attr(summon,"data_alive"));
            boolean saysDead   = "1".equals(attr(summon,"data-dead")) || "0".equals(aliveAttr) || lowerSummon.contains("dead");
            b.alive  = "1".equals(aliveAttr) && !saysDead;
            b.loot   = !b.alive && (lowerSummon.contains("loot") || lowerSummon.contains("claim"));
            b.status = b.alive ? "ALIVE" : "DEAD";
            b.image  = firstNonEmpty(attr(summon,"data-image"), first(summon,"<img[^>]+src=[\"']([^\"']+)[\"']"));

            if (b.alive && live != null) {
                b.monsterId = firstNonEmpty(
                    attr(live,"data-monster-id"), attr(live,"data-id"),
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

            b.cap     = capForBoss(b.name, category.key);
            b.enabled = categoryEnabled && sp.getBoolean("boss_enabled_" + b.key, true);
            list.add(b);
        }

        // Last-resort: data-boss=1 monster cards only
        if (list.isEmpty()) {
            for (String live : liveByName.values()) {
                if (!"1".equals(attr(live,"data-boss"))) continue;
                Boss b = new Boss();
                b.categoryKey   = category.key;
                b.categoryLabel = category.label;
                b.name = firstNonEmpty(attr(live,"data-name"), cleanHtml(first(live,"(?is)<h[1-6][^>]*>(.*?)</h[1-6]>")));
                if (empty(b.name)) continue;
                String rootKey = bossRootKey(b.name);
                b.key     = category.key + ":" + rootKey;
                b.alive   = !"1".equals(attr(live,"data-dead"));
                b.status  = b.alive ? "ALIVE" : "DEAD";
                b.monsterId = firstNonEmpty(attr(live,"data-monster-id"), attr(live,"data-id"));
                b.battleId  = attr(live,"data-battle-id");
                b.image     = first(live,"<img[^>]+src=[\"']([^\"']+)[\"']");
                b.damage    = parseLong(firstNonEmpty(attr(live,"data-userdmg"),attr(live,"data-user-dmg"),attr(live,"data-damage")));
                b.cap       = capForBoss(b.name, category.key);
                b.enabled   = categoryEnabled && sp.getBoolean("boss_enabled_" + b.key, true);
                list.add(b);
            }
        }

        if (list.isEmpty()) {
            Boss b = new Boss();
            b.categoryKey   = category.key;
            b.categoryLabel = category.label;
            b.name    = category.label + " scan placeholder";
            b.key     = category.key + ":placeholder";
            b.enabled = categoryEnabled;
            b.status  = "WAITING";
            b.cap     = capForBoss(b.name, category.key);
            list.add(b);
        }
        return list;
    }

    // ── Target selection ───────────────────────────────────────────────────────
    private Boss selectTarget(List<Boss> bosses) {
        for (Boss b : bosses) if (b.enabled && b.loot) return b;
        for (Boss b : bosses) {
            if (!b.enabled || !b.alive || empty(b.monsterId)) continue;
            if (b.cap > 0 && b.damage >= b.cap) continue;
            if (cappedBossSkipKeys.contains(b.key)) continue;
            return b;
        }
        return null;
    }

    // ── Attack sequence ────────────────────────────────────────────────────────
    private void processTarget(Boss b) throws Exception {
        if (b.loot) { append("INFO", b.name + ": loot detected"); return; }
        if (attacking) return;
        attacking = true;
        try {
            String userId = cookieValue("demon");
            if (empty(userId)) { append("ERROR", "No demon cookie. Please reconnect/login first."); return; }

            long damageCap = capForBoss(b.name, b.categoryKey);
            notifBossName = b.name; notifCap = damageCap; notifDamage = Math.max(0, b.damage);
            update("CRN Boss Bot • TARGET", notificationBody("Target: " + b.name), false);
            if (damageCap <= 0) { append("INFO", "Skipping " + b.name + ": no damage cap set."); return; }

            long totalDamage = b.damage > 0 ? b.damage : fetchDamage(b);
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
            append("INFO", "Pre-damage: " + fmtn(totalDamage) + " / " + fmtn(damageCap) + "  remaining: " + fmtn(remaining));

            if (totalDamage >= damageCap) {
                sleep(800);
                long recheck = fetchDamage(b);
                if (recheck >= damageCap) {
                    b.damage = recheck;
                    damageByBoss.put(b.key, recheck);
                    aliveDamageByBoss.put(b.key, recheck);
                    cappedBossSkipKeys.add(b.key);
                    saveBossesForUi(Collections.singletonList(b));
                    append("INFO", "Already capped. Skipping: " + b.name);
                    return;
                }
                totalDamage = recheck; lastApiTotal = totalDamage;
            }

            disableAutoFarmIfRunning();
            append("INFO", "Joining battle: [" + b.categoryLabel + "] " + b.name);
            joinBattle(b.monsterId, userId);
            sleep(700 + new Random().nextInt(600));

            while (running && sp.getBoolean("global_enabled",false) && totalDamage < damageCap
                           && sp.getBoolean("boss_enabled_" + b.key, true)) {

                Skill configuredSkill = skillFromId(parseInt(sp.getString("skill_id","0"),0));
                Skill skill = configuredSkill;

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
                        append("INFO","No potion available. Pausing "+b.name+".");
                        break;
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
                        append("INFO","No affordable skill, potion blocked by threshold. Waiting.");
                        break;
                    }
                }

                if (skill == null) { append("INFO","No skill after guards. Stopping safely."); break; }
                int effectiveCost = effectiveCostForSkill(skill);
                if (configuredSkill != null && skill.skill_id != configuredSkill.skill_id)
                    append("INFO","Downshifting "+configuredSkill.name+" -> "+skill.name);
                append("INFO","Skill "+skill.name+" - Damage: "+fmtn(totalDamage)+" / "+fmtn(damageCap));

                AttackResult res = attackWithRetry(b.monsterId, skill, 3);
                if (res != null && res.stamina >= 0) saveLiveStamina(res.stamina);
                else if (staminaNow >= 0) saveLiveStamina(Math.max(0, staminaNow - effectiveCost));
                if (res == null || !res.hasJson) { append("INFO","Boss attack stopped: HTTP "+(res==null?0:res.status)); break; }

                String lowerMsg = nullToEmpty(res.message).toLowerCase(Locale.US);
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
                    append("INFO","[DBG] hitDmg="+res.hitDamage+" logDmg="+res.logDamage+" apiTotal="+res.totalDamage+" msg=\""+compact(res.message)+"\"");
                    long after = fetchDamage(b);
                    if (after > totalDamage) { hit = after - totalDamage; append("INFO","Damage delta from post-hit check: +"+fmtn(hit)); }
                }

                totalDamage += Math.max(0,hit);
                if (hit <= 0) zeroDamageHits++; else zeroDamageHits = 0;
                b.damage = totalDamage;
                damageByBoss.put(b.key, totalDamage);
                aliveDamageByBoss.put(b.key, totalDamage);
                notifBossName = b.name; notifDamage = totalDamage; notifCap = damageCap;
                saveBossesForUi(Collections.singletonList(b));
                broadcastUiOnly("DAMAGE", b.name+" "+fmtn(totalDamage)+" / "+fmtn(damageCap));
                update("CRN Boss Bot • ATTACK", notificationBody("STA "+sp.getInt("live_stamina",-1)), false);
                append("INFO", (hit>0 ? skill.name+" dealt +"+fmtn(hit)+" damage" : "Attack failed...") + " Total: "+fmtn(totalDamage)+" / "+fmtn(damageCap));

                if (zeroDamageHits >= 3) { append("INFO","3 zero-damage hits. Stopping to prevent stamina burn."); break; }
                if (totalDamage >= damageCap) { append("INFO","Cap reached: "+fmtn(totalDamage)+" / "+fmtn(damageCap)); cappedBossSkipKeys.add(b.key); break; }

                int remainingStamina = res.stamina >= 0 ? res.stamina : (staminaNow>=0 ? Math.max(0,staminaNow-effectiveCost) : -1);
                if (remainingStamina >= 0) saveLiveStamina(remainingStamina);
                if (remainingStamina == 0) {
                    append("INFO","Stamina 0. Trying potion...");
                    if (!handleStaminaLogic(b.monsterId)) { append("INFO","No usable potion. Pausing attack."); break; }
                    continue;
                }
                sleep(1200+new Random().nextInt(1800));
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
            append("INFO","Potion IDs: "+ joinStrings(ids,", "));
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

    // ── Skill helpers ──────────────────────────────────────────────────────────
    private Skill selectAffordableSkill(int stamina) { for(Skill s:SKILLS) if(effectiveCostForSkill(s)<=stamina) return s; return null; }
    private Skill skillFromId(int id) { for(Skill s:SKILLS) if(s.skill_id==id) return s; return SKILLS[SKILLS.length-1]; }
    private int effectiveCostForSkill(Skill skill) { int base=skill==null?0:skill.stamina_cost; return sp.getBoolean("enable_asterion",false)?Math.max(1,(int)Math.round(base*2.5d)):base; }

    // ── Cap helpers ────────────────────────────────────────────────────────────
    /**
     * Cap key uses the root key (before comma/dash) so all phases of a boss
     * share the same user-configured cap.
     */
    private long capForBoss(String bossName, String categoryKey) {
        String root = bossRootKey(bossName);
        String raw  = sp.getString("cap_" + categoryKey + "_" + root, "");
        if (empty(raw)) raw = sp.getString("cap_default", "2000000");
        return parseCap(raw);
    }
    private long parseCap(String v) {
        if(v==null) return 0;
        String s=v.trim().toLowerCase(Locale.US).replace(",","");
        try { double mult=1; if(s.endsWith("k")){mult=1_000d;s=s.substring(0,s.length()-1);} else if(s.endsWith("m")){mult=1_000_000d;s=s.substring(0,s.length()-1);} else if(s.endsWith("b")){mult=1_000_000_000d;s=s.substring(0,s.length()-1);} return (long)(Double.parseDouble(s)*mult); } catch(Exception e){return 0;}
    }

    /**
     * Root key: everything before the first ',' or '-', normalised.
     * "Hermes, Ascended Herald…" → "hermes"
     * "Oceanus The Water Titan"  → "oceanus_the_water_titan"  (no comma/dash)
     */
    private static String bossRootKey(String name) {
        if (name == null || name.isEmpty()) return "";
        String[] parts = name.split("[,\\-]", 2);
        return norm(parts[0].trim());
    }

    // ── UI broadcast & notifications ───────────────────────────────────────────
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
            String[] row=new String[]{b.categoryLabel,b.name,b.status,String.valueOf(b.damage),String.valueOf(b.cap),String.valueOf(b.enabled),nullToEmpty(b.image)};
            merged.put((b.categoryLabel+":"+norm(b.name)).toLowerCase(Locale.US),row);
        }
        StringBuilder sb=new StringBuilder();
        for (String[] row:merged.values()) {
            if(sb.length()>0) sb.append("\n");
            sb.append(row[0]).append("|").append(row[1]).append("|").append(row[2]).append("|").append(row[3]).append("|").append(row[4]).append("|").append(row[5]).append("|").append(row.length>6?row[6]:"");
        }
        sp.edit().putString("last_bosses",sb.toString()).apply();
    }
    private void broadcastUiOnly(String state, String msg) {
        Intent i=new Intent(ACTION_STATUS); i.setPackage(getPackageName());
        i.putExtra("state",state); i.putExtra("message",msg==null?"":msg); i.putExtra("logs",joinLogs());
        sendBroadcast(i);
    }
    private void sendStatus(String state, String msg, List<Boss> bosses) {
        update("CRN Boss Bot • "+state, notificationBody(msg), state.equals("TARGET"));
        sp.edit().putString("ui_state",state).putString("ui_message",msg==null?"":msg).apply();
        Intent i=new Intent(ACTION_STATUS); i.setPackage(getPackageName());
        i.putExtra("state",state); i.putExtra("message",msg); i.putExtra("logs",joinLogs());
        if(bosses!=null) saveBossesForUi(bosses);
        sendBroadcast(i);
    }
    private void append(String type, String msg) {
        String line=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+" ["+type+"] "+msg;
        synchronized(logs){logs.add(line); while(logs.size()>500) logs.remove(0);}
        sp.edit().putString("logs",joinLogs()).apply();
        Intent i=new Intent(ACTION_STATUS); i.setPackage(getPackageName());
        i.putExtra("state","LOG"); i.putExtra("message",msg); i.putExtra("logs",joinLogs());
        sendBroadcast(i);
    }
    private void clearLogs() {
        synchronized(logs){logs.clear();}
        sp.edit().putString("logs","").apply();
        Intent i=new Intent(ACTION_STATUS); i.setPackage(getPackageName());
        i.putExtra("state","LOG"); i.putExtra("message","Logs cleared"); i.putExtra("logs","");
        sendBroadcast(i);
    }
    private String notificationBody(String msg) {
        StringBuilder b=new StringBuilder();
        if(!empty(msg)) b.append(msg);
        int sta=sp==null?-1:sp.getInt("live_stamina",-1);
        if(sta>=0){if(b.length()>0)b.append("\n");b.append("STA: ").append(sta);}
        if(!empty(notifBossName)&&notifCap>0){if(b.length()>0)b.append("\n");b.append(shortText(notifBossName,36)).append(": ").append(fmtn(notifDamage)).append(" / ").append(fmtn(notifCap));}
        return b.length()==0?"Service running":b.toString();
    }
    private String joinLogs(){synchronized(logs){return android.text.TextUtils.join("\n",logs);}}
    private String fmtn(long n){return String.format(Locale.US,"%,d",n);}
    private String shortText(String s,int max){return s==null?"":(s.length()<=max?s:s.substring(0,Math.max(0,max-1))+"…");}

    // ── HTTP ───────────────────────────────────────────────────────────────────
    private String get(String url, String referer) throws Exception { return request("GET",url,null,referer).text; }
    private String post(String url, String body, String referer) throws Exception { return request("POST",url,body,referer).text; }
    private HttpResult postRaw(String url, String body, String referer) throws Exception { return request("POST",url,body,referer); }
    private HttpResult request(String method, String url, String body, String referer) throws Exception {
        String currentUrl=url, currentMethod=method, currentBody=body;
        for (int redirects=0; redirects<=5; redirects++) {
            HttpURLConnection c=(HttpURLConnection)new URL(currentUrl).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(currentMethod);
            c.setConnectTimeout(20000); c.setReadTimeout(30000);
            c.setRequestProperty("Accept","*/*");
            c.setRequestProperty("Accept-Language","en-US,en;q=0.9");
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Referer",referer==null?BASE+"/":referer);
            String cookie=CookieManager.getInstance().getCookie(BASE);
            if(!empty(cookie)) c.setRequestProperty("Cookie",cookie);
            if(currentBody!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");try(OutputStream os=c.getOutputStream()){os.write(currentBody.getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();
            String setCookie=c.getHeaderField("Set-Cookie");
            if(!empty(setCookie)){CookieManager.getInstance().setCookie(BASE,setCookie);CookieManager.getInstance().flush();}
            if(code==301||code==302||code==303||code==307||code==308){
                String location=c.getHeaderField("Location");
                if(empty(location)) throw new IOException("HTTP "+code+" redirect without Location");
                URL next=new URL(new URL(currentUrl),location);
                append("INFO","HTTP redirect "+code+" -> "+next.getPath());
                currentUrl=next.toString();
                if(code==303||((code==301||code==302)&&"POST".equals(currentMethod))){currentMethod="GET";currentBody=null;}
                continue;
            }
            InputStream is=code>=400?c.getErrorStream():c.getInputStream();
            String text=readAll(is);
            if(code==429){append("ERROR","429 cooldown");sleep(5000);}
            if(code>=400&&!("POST".equals(currentMethod)&&DMG_URL.equals(currentUrl)&&code==400&&looksJson(text))) throw new IOException("HTTP "+code+" "+compact(text));
            return new HttpResult(code,text);
        }
        throw new IOException("Too many redirects: "+url);
    }

    // ── Notifications ──────────────────────────────────────────────────────────
    private Notification notification(String title, String body, boolean alert) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop=new Intent(this,BotForegroundService.class).setAction(ACTION_STOP);
        PendingIntent stopPi=PendingIntent.getService(this,2,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,alert?CH_ALERT:CH_MAIN):new Notification.Builder(this);
        b.setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
         .setSmallIcon(R.drawable.ic_stat_crn).setContentIntent(pi).setOngoing(!alert)
         .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Stop",stopPi);
        b.setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE)
         .setShowWhen(true).setOnlyAlertOnce(!alert).setPriority(alert?Notification.PRIORITY_HIGH:Notification.PRIORITY_DEFAULT);
        if(Build.VERSION.SDK_INT>=31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
    private void update(String title, String body, boolean alert) {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(alert?101:NOTIF_ID,notification(title,body,alert));
        if(alert&&sp.getBoolean("alerts",true)) maybeAlert();
    }
    private void maybeAlert() {
        long now=System.currentTimeMillis(); if(now-lastAlertMs<60000) return; lastAlertMs=now;
        try{Vibrator v=(Vibrator)getSystemService(VIBRATOR_SERVICE); if(v!=null) v.vibrate(VibrationEffect.createOneShot(450,VibrationEffect.DEFAULT_AMPLITUDE)); RingtoneManager.getRingtone(getApplicationContext(),android.provider.Settings.System.DEFAULT_NOTIFICATION_URI).play();}catch(Exception ignored){}
    }
    private void createChannels() {
        if(Build.VERSION.SDK_INT<26) return;
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel mainCh=new NotificationChannel(CH_MAIN,"CRN Boss Bot Running",NotificationManager.IMPORTANCE_DEFAULT);
        mainCh.setSound(null,null); mainCh.setShowBadge(false); mainCh.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); nm.createNotificationChannel(mainCh);
        NotificationChannel alertCh=new NotificationChannel(CH_ALERT,"CRN Boss Alerts",NotificationManager.IMPORTANCE_HIGH);
        alertCh.enableVibration(true); alertCh.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        alertCh.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
        nm.createNotificationChannel(alertCh);
    }
    private void releaseLocks() {
        try{if(wakeLock!=null&&wakeLock.isHeld()) wakeLock.release();}catch(Exception ignored){}
        try{if(wifiLock!=null&&wifiLock.isHeld()) wifiLock.release();}catch(Exception ignored){}
        wakeLock=null; wifiLock=null;
    }
    private long nextDelayMs() {
        long baseMs=Math.max(10,parseInt(sp.getString("scan_interval","60"),60))*1000L;
        if(!sp.getBoolean("smart_delay",true)) return baseMs;
        return baseMs+1500+new Random().nextInt(2500);
    }

    // ── HTML parsing utils ─────────────────────────────────────────────────────
    private static List<String> extractClassBlocks(String html, String className) {
        List<String> out=new ArrayList<>();
        if(html==null) return out;
        Pattern startPat=Pattern.compile("(?is)<div\\b(?=[^>]*class=[\"'][^\"']*"+Pattern.quote(className)+"[^\"']*[\"'])[^>]*>");
        Matcher m=startPat.matcher(html);
        while(m.find()){
            int start=m.start(),pos=m.end(),depth=1;
            Matcher tags=Pattern.compile("(?is)</?div\\b[^>]*>").matcher(html);
            tags.region(pos,html.length());
            int end=-1;
            while(tags.find()){String tag=tags.group().toLowerCase(Locale.US); if(tag.startsWith("</"))depth--;else depth++; if(depth==0){end=tags.end();break;}}
            if(end>start) out.add(html.substring(start,end));
        }
        return out;
    }
    private static String attr(String html, String name) {
        if(html==null||name==null) return "";
        Matcher m=Pattern.compile("(?is)\\b"+Pattern.quote(name)+"\\s*=\\s*([\"'])(.*?)\\1").matcher(html);
        if(m.find()) return cleanHtml(m.group(2));
        m=Pattern.compile("(?is)\\b"+Pattern.quote(name)+"\\s*=\\s*([^\\s>]+)").matcher(html);
        return m.find()?cleanHtml(m.group(1)):"";
    }
    private static String firstNonEmpty(String... values){if(values==null)return "";for(String v:values)if(!empty(v))return v.trim();return "";}
    private static String first(String s,String re){Matcher m=Pattern.compile(re).matcher(s==null?"":s);return m.find()?m.group(1):"";}
    private static String cleanHtml(String s){return(s==null?"":s).replaceAll("(?is)<script.*?</script>","").replaceAll("<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").trim();}
    private static String norm(String s){return(s==null?"":s).toLowerCase(Locale.US).replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");}
    private static String stripTags(String s){if(s==null)return "";return s.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("(?is)<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replaceAll("\\s+"," ").trim();}
    private static String joinStrings(List<String> items, String sep){StringBuilder sb=new StringBuilder();for(String item:items){if(sb.length()>0)sb.append(sep);sb.append(item);}return sb.toString();}
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
}
