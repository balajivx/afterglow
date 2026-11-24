# Pixel Controls — Pending Items & Roadmap

> **Last Updated:** 2026-11-23
> **Status:** Current build stable; items below are planned enhancements

---

## 🔴 HIGH PRIORITY

### 1. Daemon Auto-Start Without Shizuku
**Problem:** Shizuku breaks some banking apps. Need daemon auto-start on boot without Shizuku.

**Root Cause:** Android security model prevents apps from running shell commands (`cmd`, `svc`, `settings`, `cmd netpolicy`). Device Owner can change *settings* via `DevicePolicyManager` but **cannot execute shell commands** (require shell UID).

**Possible Approaches:**
| Approach | Feasibility | Notes |
|----------|-------------|-------|
| **Device Owner + Foreground Service** | ❌ Blocked | Can't run `sh /data/local/tmp/start_daemon.sh` from app UID |
| **Termux + Termux:Boot** | ⚠️ Limited | Termux runs as app UID; `cmd`/`svc` still fail |
| **Root (Magisk)** | ✅ Works | Breaks SafetyNet, voids warranty |
| **Custom ROM / System App** | ✅ Works | Not practical for end users |

**Current approach (2.7):** LADB on the phone (app's Start button copies the command and opens LADB) or `./daemon.sh start` from the Mac. No Shizuku. Stop from the app.

**Research Needed:** Check if `DevicePolicyManager` can grant `android.permission.RUN_SHELL_COMMANDS` (requires system signature).

---

### 2. Battery Saver+ Section (Device Owner APIs — No Shell/Shizuku Needed)

**Goal:** Real battery savings using only Device Owner `Settings.Global` / `DevicePolicyManager` APIs — **no shell, no Shizuku, no root**.

#### Phase 1: Quick Wins (Settings.Global toggles)
| Feature | Setting | Battery Impact | Implementation |
|---------|---------|----------------|----------------|
| **Force LTE-only** | `preferred_network_mode = "9,9"` | ~15% on weak 5G | `Settings.Global.putString()` |
| **Restore 5G** | `preferred_network_mode = "27,27"` | — | `Settings.Global.putString()` |
| **Data Saver ON** | `restrict_background = 1` | ~10-20% | `Settings.Global.putInt()` |
| **Data Saver OFF** | `restrict_background = 0` | — | `Settings.Global.putInt()` |
| **Aggressive Doze** | `device_idle_constants` | ~5-10% | `Settings.Global.putString()` |
| **App Standby** | `app_standby_enabled = 1` | ~5% | `Settings.Global.putInt()` |
| **Auto-sync OFF** | `auto_sync = 0` | ~3-5% | `ContentResolver.setSyncAutomatically()` |
| **Location Mode** | `location_mode = 0` (OFF) | ~5-10% | `Settings.Secure.putInt()` |

#### Phase 2: Smart Automation
| Feature | Trigger | Action | Battery Save |
|---------|---------|--------|--------------|
| **Smart Low Signal** | Signal < -100 dBm → LTE-only + Data Saver | Auto | ~20% on weak signal |
| | Signal > -85 dBm → Restore 5G | Auto | — |
| **Auto Data Saver** | Screen OFF + not charging → Data Saver ON | Auto | ~10% |
| | Screen ON → Data Saver OFF | Auto | — |
| **Aggressive Doze** | Screen OFF > 5 min → Force Doze | Auto | ~5% |
| **Battery < 20%** | Disable auto-sync, lower brightness floor | Auto | ~5% |
| **Charging** | Restore all settings | Auto | — |

#### Phase 3: Battery Dashboard
| UI Element | Purpose |
|------------|---------|
| **Battery Saver+ Master Toggle** | One-tap enables all Phase 1 features |
| **Smart Low Signal Toggle** | Auto-manages based on signal strength |
| **Current Signal Strength** | Real-time dBm display |
| **Battery Usage Breakdown** | Screen, Cell, Wi-Fi, Apps, Idle |
| **Estimated Time Remaining** | Based on current drain rate |
| **Savings Log** | "Saved X% today via Smart Low Signal" |

#### Implementation Notes
- All via `Settings.Global` / `Settings.Secure` / `DevicePolicyManager` — **no shell**
- Requires `WRITE_SECURE_SETTINGS` (already granted via Device Owner)
- `TelephonyManager.listen()` for signal strength changes
- `BroadcastReceiver` for `ACTION_SCREEN_ON/OFF`, `ACTION_POWER_CONNECTED/DISCONNECTED`, `ACTION_BATTERY_LOW`
- Store user preferences in `SharedPreferences`

---

## 🟡 MEDIUM PRIORITY

### 3. Daemon Health Monitoring
- ~~App-side watchdog / Running-Stopped in UI~~ done in 2.7 (status card, polled every ~6 s while the app is open)
- ~~Log~~ done: /data/local/tmp/pc_daemon.log (`./daemon.sh log`), unexpected ends logged
- **Auto-reconnect:** If daemon dies, show persistent "Start Daemon" button with pulsing animation
- **Log viewer:** "Daemon Logs" button → shows last 50 lines from `logcat -s PixelControls`

### 4. Network Quality Indicators
- Real-time signal strength (dBm) in status bar / app header
- "Network Health" score: Excellent / Good / Poor / Critical
- Per-app data usage (last 24h)

### 5. Export/Import Settings
- Backup all Pixel Controls settings to JSON file
- Restore on new device / after factory reset

---

## 🟢 LOW PRIORITY / NICE TO HAVE

### 6. Advanced Android Auto
- Auto-switch to wired when car Bluetooth connects (not just USB)
- Car display brightness sync with phone auto-brightness

### 7. Per-App Battery Rules
- "Restrict background" per app (Device Owner can do this)
- "Unrestricted" whitelist for critical apps

### 8. Theming / UI Polish
- Dark/Light/System theme toggle
- Compact mode (denser UI)
- Widget for quick toggles

---

## 📝 IMPLEMENTATION ORDER (When You Pick This Up)

1. **Battery Saver+ Section** — Phase 1 toggles (2-3 hours)
2. **Smart Low Signal** — Signal listener + auto-toggle (2 hours)
3. **Screen/Charging Receivers** — Auto Data Saver / Doze (1 hour)
4. **Battery Dashboard** — Stats UI (2 hours)
4. **Daemon Health Monitor** — App-side watchdog (1 hour)
5. **Export/Import Settings** — JSON backup (30 min)

---

## 🔧 TECHNICAL DEBT / CLEANUP

- [x] Remove unused `DaemonForegroundService` / `BootReceiver` code (2.7)
- [ ] Consolidate `AdaptiveBrightnessManager` + `SoundProfileManager` into `SettingsManager`
- [ ] Add unit tests for `SettingsManager`
- [ ] Migrate to Kotlin (optional)
- [ ] Add CI/CD for automated builds

---

## 📌 KEY DECISIONS LOG

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-11-22 | Reverted Foreground Service | Android 14+ restricts FGS types; crashes on mediaPlayback/connectedDevice |
| 2026-11-22 | Kept Shizuku for daemon | Only reliable way to run shell commands from app |
| 2026-11-22 | Device Owner for settings | Works for `Settings.Global` but NOT shell commands |
| 2026-11-22 | Battery Saver+ via DeviceOwner | No shell needed; real battery savings |
| 2026-11-23 | AA wired/wireless moved into the app (from AA Switch), daemon no longer touches Wi-Fi/USB | Daemon's own Wi-Fi off / USB reset restarted adbd and killed the daemon mid-switch |
| 2026-11-23 | targetSdk 28 | Lets the app switch Wi-Fi itself (as AA Switch); FGS-type crashes no longer apply |
| 2026-11-23 | Daemon start via LADB, stop in app (SHUTDOWN), script bundled in APK | Start/stop at will without a computer; script always matches app |

---

## 📂 FILES TO MODIFY (When Resuming)

| File | Purpose |
|------|---------|
| `app/src/main/java/com/pixelcontrols/MainActivity.java` | Add Battery Saver+ section |
| `app/src/main/java/com/pixelcontrols/BatteryManager.java` (new) | Core battery logic |
| `app/src/main/java/com/pixelcontrols/SignalMonitor.java` (new) | Signal strength listener |
| `app/src/main/java/com/pixelcontrols/BatteryReceiver.java` (new) | Screen/Charging/Battery broadcasts |
| `app/src/main/java/com/pixelcontrols/SettingsManager.java` (new) | Centralized Settings.Global wrapper |
| `app/src/main/AndroidManifest.xml` | Add receivers/permissions |

---

> **To Resume:** Open this file, pick the next unchecked item, start coding.
> Current build is stable — all existing features work.