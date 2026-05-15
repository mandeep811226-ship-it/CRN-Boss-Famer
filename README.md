# CRN Boss Bot v9.1 — Full Fix

Standalone Android foreground-service app. WebView is used only for login/cookie capture; the bot engine is Java HTTP logic.

## v9.1 fixes
- Boss dashboard now uses `.auto-summon-card` as the authoritative boss list.
- `.monster-card` is only used to attach live `monster_id`, `battle_id`, damage, and image to an already-known boss.
- Normal monsters are no longer shown as bosses.
- Fallback live parsing only accepts `data-boss="1"` cards.
- Individual cap rows are generated from the boss-only dashboard list, so cap keys are per category + normalized boss name.
- Skill model remains exactly `Skill { name, skill_id, stamina_cost }`.
- Default selected skill is Ultimate Slash (`skill_id=-4`, `stamina_cost=200`), with automatic downshift when stamina is insufficient.
- Attack payload posts base `skill_id` and base `stamina_cost`; Asterion cost stays local for stamina planning only.
- Manual redirect handling preserved to prevent too-many-follow-up redirect crashes.

## Build
Open in Android Studio, sync Gradle, then Build > Build APK(s).
