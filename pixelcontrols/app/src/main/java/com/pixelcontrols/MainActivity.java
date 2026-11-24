package com.pixelcontrols;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.pixelcontrols.adblock.Adblock;
import com.pixelcontrols.adblock.AdblockVpnService;
import com.pixelcontrols.adblock.DnsFilterEngine;
import com.pixelcontrols.brightness.AdaptiveBrightnessManager;
import com.pixelcontrols.photo.EnhanceActivity;
import com.pixelcontrols.sound.SoundProfileManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private Switch adblockSwitch, videoSwitch;
    private RadioButton modeVpn, modeDns;
    private TextView adblockStatus, daemonStatus, doctor;
    private Button doctorFix;
    private Button pauseBtn;
    private TextView aaModeStatus, aaLog;
    private Switch aaAutoSwitch;
    private TextView daemonIcon, daemonHeadline;
    private Button daemonStartBtn, daemonStopBtn;
    private volatile Boolean daemonUp;   // null = not checked yet
    private int tick;
    private static final String MAC_START = "cd ~/Afterglow/pixel-controls && ./daemon.sh start";
    private TextView soundStatus;
    private TextView phoneBrightnessStatus;
    private Switch phoneAdaptiveSwitch;
    private SeekBar phoneBrightnessBar;
    private Button permBtn;
    private boolean updating;
    private volatile String daemonLine = "Checking helper daemon…";

    // Collapsible section state
    private final Map<String, Boolean> sectionExpanded = new LinkedHashMap<>();
    private static final int TAG_CHEVRON = 0x7f010000;
    private static final int TAG_CONTENT = 0x7f010001;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(24));
        root.setBackgroundColor(0xFFFAFAFA);
        scroll.addView(root);
        setContentView(scroll);

        // Initialize all sections as expanded by default
        sectionExpanded.put("Adblock", true);
        sectionExpanded.put("DSLR", true);
        sectionExpanded.put("Android Auto", true);
        sectionExpanded.put("Sound Profile", true);
        sectionExpanded.put("Phone Brightness", true);
        sectionExpanded.put("Network", true);
        sectionExpanded.put("Battery watch", true);

        header();

        // Network doctor: always shows the internet / Private DNS state under the title; "Fix now" when broken
        doctor = body("");
        root.addView(doctor, fullWidth());
        doctorFix = button("Fix now: set Private DNS to Automatic", click(new Runnable() { public void run() { fixDns(); } }));
        doctorFix.setVisibility(View.GONE);
        root.addView(doctorFix, fullWidth());

        // All sections
        buildAdblockSection();
        buildDslrSection();
        buildAndroidAutoSection();
        buildSoundProfileSection();
        buildPhoneBrightnessSection();
        buildNetworkSection();
        buildBatteryWatchSection();
        com.pixelcontrols.battery.BatteryWatch.schedule(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }

        if (SecureSettings.strictByUs(this)) com.pixelcontrols.adblock.LiteDnsWatch.register(this);
    }

    private void header() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));

        TextView title = new TextView(this);
        title.setText("Pixel Controls");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1A1A1A);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = new TextView(this);
        String vn = "";
        try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        version.setText("v" + vn);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        version.setTextColor(0xFF888888);
        version.setPadding(dp(8), dp(2), dp(8), dp(2));
        version.setBackgroundResource(android.R.drawable.editbox_background);
        header.addView(version);

        root.addView(header, fullWidth());
    }

    private LinearLayout createSection(String title, String key) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundColor(0xFFFFFFFF);
        section.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Section header with expand/collapse
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOnClickListener(v -> toggleSection(key, section));

        ImageView chevron = new ImageView(this);
        chevron.setImageResource(android.R.drawable.arrow_down_float);
        chevron.setColorFilter(0xFF666666);
        chevron.setPadding(dp(4), dp(4), dp(12), dp(4));
        header.addView(chevron);
        section.setTag(TAG_CHEVRON, chevron);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(0xFF1A1A1A);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        section.addView(header, fullWidth());

        // Content container
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setId(View.generateViewId());
        content.setPadding(0, dp(12), 0, 0);
        section.setTag(TAG_CONTENT, content);
        section.addView(content, fullWidth());

        root.addView(section, fullWidthWithMargin());

        return content;
    }

    private void toggleSection(String key, LinearLayout section) {
        LinearLayout content = (LinearLayout) section.getTag(TAG_CONTENT);
        ImageView chevron = (ImageView) section.getTag(TAG_CHEVRON);
        Boolean expanded = sectionExpanded.get(key);
        boolean newState = expanded == null || !expanded;
        sectionExpanded.put(key, newState);

        content.setVisibility(newState ? View.VISIBLE : View.GONE);
        chevron.setImageResource(newState ? android.R.drawable.arrow_down_float : android.R.drawable.arrow_up_float);
    }

    private void buildAdblockSection() {
        LinearLayout content = createSection("Adblock", "Adblock");

        // Main toggle
        adblockSwitch = new Switch(this);
        adblockSwitch.setText("Block ads, pop-ups & trackers");
        adblockSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        adblockSwitch.setTextColor(0xFF1A1A1A);
        adblockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { if (!updating) setAdblock(on); }
        });
        content.addView(adblockSwitch, fullWidth());
        adblockStatus = bodySmall("");
        content.addView(adblockStatus, fullWidth());

        // Mode selection
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);

        modeVpn = new RadioButton(this);
        modeVpn.setId(View.generateViewId());
        modeVpn.setText("Full filter");
        modeVpn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        modeDns = new RadioButton(this);
        modeDns.setId(View.generateViewId());
        modeDns.setText("Lite (DNS)");
        modeDns.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.addView(modeVpn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modes.addView(modeDns, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        modes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                if (updating) return;
                Intent consent = Adblock.setMode(MainActivity.this, id == modeDns.getId() ? Adblock.MODE_DNS : Adblock.MODE_VPN);
                if (consent != null) startActivityForResult(consent, REQ_VPN);
                refresh();
            }
        });

        TextView modeLabel = new TextView(this);
        modeLabel.setText("Mode:");
        modeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        modeLabel.setTextColor(0xFF666666);
        modeLabel.setPadding(0, 0, dp(12), 0);

        modeRow.addView(modeLabel);
        modeRow.addView(modes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(modeRow, fullWidth());

        // Mode descriptions
        TextView modeDesc = new TextView(this);
        modeDesc.setText("Full: filter on the phone with per-app bypass, OTT packs and a live log (VPN icon)\nLite: Private DNS → AdGuard DNS (no VPN icon, no per-app control)");
        modeDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        modeDesc.setTextColor(0xFF888888);
        modeDesc.setPadding(0, dp(4), 0, dp(8));
        content.addView(modeDesc, fullWidth());

        // Video blocking
        videoSwitch = new Switch(this);
        videoSwitch.setText("Maximum OTT ad blocking");
        videoSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        videoSwitch.setTextColor(0xFF1A1A1A);
        videoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { if (!updating) Adblock.setVideoCompat(MainActivity.this, !on); }
        });
        content.addView(videoSwitch, fullWidth());

        // Action buttons row
        LinearLayout actions1 = buttonRow();
        rowButton(actions1, "OTT packs", click(new Runnable() { public void run() { showPacks(); } }));
        pauseBtn = rowButton(actions1, "Pause 10 min", new View.OnClickListener() {
            @Override public void onClick(View v) {
                AdblockVpnService s = instanceOrNull();
                if (s == null) return;
                if (s.isPaused()) s.resumeNow(); else s.pause();
                Toast.makeText(MainActivity.this, s.isPaused() ? "Paused 10 min" : "Resumed", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        content.addView(actions1, fullWidth());

        LinearLayout actions2 = buttonRow();
        rowButton(actions2, "Blocked", click(new Runnable() { public void run() { showRecent(); } }));
        rowButton(actions2, "Lookups", click(new Runnable() { public void run() { showLookups(); } }));
        content.addView(actions2, fullWidth());

        LinearLayout actions3 = buttonRow();
        rowButton(actions3, "My blocks", click(new Runnable() { public void run() { showUserBlocklist(); } }));
        rowButton(actions3, "Bypass apps", click(new Runnable() { public void run() { showExcludedApps(); } }));
        content.addView(actions3, fullWidth());

        LinearLayout actions4 = buttonRow();
        rowButton(actions4, "Allowlist", click(new Runnable() { public void run() { showAllowlist(); } }));
        rowButton(actions4, "Update list", click(new Runnable() { public void run() { updateList(); } }));
        content.addView(actions4, fullWidth());

        TextView tip = bodySmall("Tip: Add 'Adblock' tile to Quick Settings. Long-press app icon for 'Toggle Adblock' shortcut.");
        content.addView(tip, fullWidth());
    }

    private void buildDslrSection() {
        LinearLayout content = createSection("DSLR", "DSLR");

        TextView desc = body("Just took a photo? Swipe down and tap the DSLR tile — it edits your latest photo and saves the copy. "
                + "For any other photo: in your gallery use Share → DSLR, Edit in → DSLR, or Open with → DSLR. "
                + "Each photo gets its own settings. You get a copy with correct exposure, white balance, and detail; the original is never changed.");
        content.addView(desc, fullWidth());

        button(content, "Edit a photo…", click(new Runnable() { public void run() { startActivity(new Intent(MainActivity.this, EnhanceActivity.class)); } }));
    }

    private void buildAndroidAutoSection() {
        LinearLayout content = createSection("Android Auto", "Android Auto");

        // Wired/Wireless Mode (no helper daemon: the app switches Wi-Fi itself, see auto/Car)
        TextView modeTitle = new TextView(this);
        modeTitle.setText("Wired ↔ Wireless");
        modeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        modeTitle.setTypeface(Typeface.DEFAULT_BOLD);
        modeTitle.setTextColor(0xFF1A1A1A);
        modeTitle.setPadding(0, 0, 0, dp(8));
        content.addView(modeTitle, fullWidth());

        aaModeStatus = bodySmall("");
        content.addView(aaModeStatus, fullWidth());

        aaAutoSwitch = new Switch(this);
        aaAutoSwitch.setText("Switch to wired automatically when I plug in the cable in the car");
        aaAutoSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        aaAutoSwitch.setTextColor(0xFF1A1A1A);
        aaAutoSwitch.setChecked(com.pixelcontrols.auto.Car.auto(this));
        aaAutoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                com.pixelcontrols.auto.Car.prefs(MainActivity.this).edit().putBoolean("auto", on).apply();
            }
        });
        content.addView(aaAutoSwitch, fullWidth());

        LinearLayout modeRow1 = buttonRow();
        rowButton(modeRow1, "Wired now", click(new Runnable() { public void run() {
            com.pixelcontrols.auto.Car.log(MainActivity.this, "app: Wired now · " + com.pixelcontrols.auto.Car.state(MainActivity.this));
            if (!com.pixelcontrols.auto.Car.plugged(MainActivity.this))
                Toast.makeText(MainActivity.this, "Plug the cable into the car's Android Auto port first", Toast.LENGTH_SHORT).show();
            else if (com.pixelcontrols.auto.Car.port(MainActivity.this) == com.pixelcontrols.auto.Car.Port.CHARGING)
                Toast.makeText(MainActivity.this, "This is the charging port: use the car's Android Auto port for wired", Toast.LENGTH_LONG).show();
            else if (!com.pixelcontrols.auto.Car.wifiOn(MainActivity.this))
                Toast.makeText(MainActivity.this, "Wi-Fi is already off: unplug and plug the cable back in for wired", Toast.LENGTH_LONG).show();
            else if (com.pixelcontrols.auto.Car.goWired(MainActivity.this))
                Toast.makeText(MainActivity.this, "Wi-Fi off: wired Android Auto starts over the cable", Toast.LENGTH_SHORT).show();
            else
                Toast.makeText(MainActivity.this, "Android didn't let Pixel Controls turn Wi-Fi off", Toast.LENGTH_LONG).show();
            updateAaModeUi();
        } }));
        rowButton(modeRow1, "Wireless now", click(new Runnable() { public void run() {
            com.pixelcontrols.auto.Car.goWireless(MainActivity.this, true);
            updateAaModeUi();
        } }));
        content.addView(modeRow1, fullWidth());
        button(content, "Forget car ports", click(new Runnable() { public void run() {
            com.pixelcontrols.auto.Car.forgetPorts(MainActivity.this);
            Toast.makeText(MainActivity.this, "Forgotten: you'll be asked again the next time you plug into a new port", Toast.LENGTH_LONG).show();
            updateAaModeUi();
        } }));

        aaLog = bodySmall("");
        aaLog.setTypeface(Typeface.MONOSPACE);
        aaLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        content.addView(aaLog, fullWidth());

        content.addView(bodySmall("When the phone connects to the car's Bluetooth, Pixel Controls waits for the cable. Once the car "
                + "takes Android Auto over USB, Wi-Fi goes off (wireless Android Auto needs it), so the car uses the cable. "
                + "Unplug or leave the car and Wi-Fi comes back (a re-plug within 15 s stays wired). The car's charging port is left "
                + "alone: a port without a data link is never switched, and the first time you use a new data port you're asked once. "
                + "No helper daemon needed; works after a restart. Also via the 'Android Auto' Quick Settings tile (lit = wired)."), fullWidth());
    }

    private void buildSoundProfileSection() {
        LinearLayout content = createSection("Sound Profile", "Sound Profile");

        soundStatus = bodySmall("");
        content.addView(soundStatus, fullWidth());

        // Permission warning
        if (!SoundProfileManager.hasNotificationPolicyAccess(this)) {
            LinearLayout permRow = new LinearLayout(this);
            permRow.setOrientation(LinearLayout.HORIZONTAL);
            permRow.setGravity(Gravity.CENTER_VERTICAL);
            permRow.setBackgroundColor(0xFFFFF3E0);
            permRow.setPadding(dp(12), dp(8), dp(12), dp(8));

            TextView warn = new TextView(this);
            warn.setText("⚠ Allow Do Not Disturb access to change sound profiles");
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            warn.setTextColor(0xFFE65100);
            permRow.addView(warn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button grant = new Button(this);
            grant.setText("Grant");
            grant.setAllCaps(false);
            grant.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            grant.setOnClickListener(v -> SoundProfileManager.requestNotificationPolicyAccess(this));
            permRow.addView(grant);

            content.addView(permRow, fullWidth());
        }

        LinearLayout soundRow = buttonRow();
        rowButton(soundRow, "Normal", click(new Runnable() {
            public void run() {
                if (!SoundProfileManager.hasNotificationPolicyAccess(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Allow Do Not Disturb access first", Toast.LENGTH_SHORT).show();
                    SoundProfileManager.requestNotificationPolicyAccess(MainActivity.this);
                    return;
                }
                soundProfile(SoundProfileManager.PROFILE_NORMAL);
                updateSoundUi();
            }
        }));
        rowButton(soundRow, "Silent", click(new Runnable() {
            public void run() {
                if (!SoundProfileManager.hasNotificationPolicyAccess(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Allow Do Not Disturb access first", Toast.LENGTH_SHORT).show();
                    SoundProfileManager.requestNotificationPolicyAccess(MainActivity.this);
                    return;
                }
                soundProfile(SoundProfileManager.PROFILE_SILENT);
                updateSoundUi();
            }
        }));
        rowButton(soundRow, "Night", click(new Runnable() {
            public void run() {
                if (!SoundProfileManager.hasNotificationPolicyAccess(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Allow Do Not Disturb access first", Toast.LENGTH_SHORT).show();
                    SoundProfileManager.requestNotificationPolicyAccess(MainActivity.this);
                    return;
                }
                soundProfile(SoundProfileManager.PROFILE_NIGHT);
                updateSoundUi();
            }
        }));
        content.addView(soundRow, fullWidth());

        content.addView(bodySmall("Normal: restores volumes. Silent: vibration only, media muted. Night: ring 40%, notif/media 0%. Also via 'Sound Profile' Quick Settings tile."), fullWidth());
    }

    private void buildPhoneBrightnessSection() {
        LinearLayout content = createSection("Phone Brightness", "Phone Brightness");

        permBtn = button(content, "Grant Modify System Settings Permission", click(new Runnable() {
            public void run() {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }));
        permBtn.setVisibility(AdaptiveBrightnessManager.canWriteSettings(this) ? View.GONE : View.VISIBLE);

        phoneBrightnessStatus = bodySmall("");
        content.addView(phoneBrightnessStatus, fullWidth());

        phoneAdaptiveSwitch = new Switch(this);
        phoneAdaptiveSwitch.setText("Adaptive Brightness (Auto)");
        phoneAdaptiveSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        phoneAdaptiveSwitch.setTextColor(0xFF1A1A1A);
        phoneAdaptiveSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!updating) {
                    if (on) AdaptiveBrightnessManager.setAdaptive(MainActivity.this, true);
                    else AdaptiveBrightnessManager.restoreManualPreset(MainActivity.this);
                    updatePhoneBrightnessUi();
                }
            }
        });
        content.addView(phoneAdaptiveSwitch, fullWidth());

        phoneBrightnessBar = new SeekBar(this);
        phoneBrightnessBar.setMax(100);
        phoneBrightnessBar.setProgress(AdaptiveBrightnessManager.getCurrentBrightnessPercent(this));
        phoneBrightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    AdaptiveBrightnessManager.setBrightnessPercent(MainActivity.this, progress);
                    updatePhoneBrightnessUi();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        content.addView(phoneBrightnessBar, fullWidth());

        LinearLayout brRow = buttonRow();
        rowButton(brRow, "Save as M Preset", click(new Runnable() {
            public void run() {
                int pct = phoneBrightnessBar.getProgress();
                AdaptiveBrightnessManager.saveManualPreset(MainActivity.this, pct);
                Toast.makeText(MainActivity.this, "Saved " + pct + "% as M preset", Toast.LENGTH_SHORT).show();
                updatePhoneBrightnessUi();
            }
        }));
        rowButton(brRow, "Restore M Preset", click(new Runnable() {
            public void run() {
                AdaptiveBrightnessManager.restoreManualPreset(MainActivity.this);
                updatePhoneBrightnessUi();
            }
        }));
        content.addView(brRow, fullWidth());

        content.addView(bodySmall("Adjust phone display brightness directly. 'Brightness Mode' Quick Settings tile toggles between Auto and saved 'M' preset."), fullWidth());
    }

    private void buildNetworkSection() {
        LinearLayout content = createSection("Low Signal Boost", "Network");

        // Low signal (the app does all of this itself)
        LinearLayout row1 = buttonRow();
        rowButton(row1, "Low Signal ON", click(new Runnable() { public void run() { daemon("ON", true); } }));
        rowButton(row1, "Low Signal OFF", click(new Runnable() { public void run() { daemon("OFF", true); } }));
        content.addView(row1, fullWidth());

        content.addView(bodySmall("Low Signal: the app you have open gets the weak connection. Background apps can't start new "
                + "downloads (on battery and on the charger), Battery & Data Saver on battery, mobile data kept ready, quicker Wi-Fi "
                + "handover, Private DNS → Automatic, DNS accelerator. OFF restores every setting exactly."), fullWidth());

        // Optional helper (shell-only extras)
        TextView helperTitle = new TextView(this);
        helperTitle.setText("Optional helper (from the Mac)");
        helperTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        helperTitle.setTypeface(Typeface.DEFAULT_BOLD);
        helperTitle.setTextColor(0xFF1A1A1A);
        helperTitle.setPadding(0, dp(12), 0, dp(4));
        content.addView(helperTitle, fullWidth());

        // Daemon status card
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setBackgroundColor(0xFFF5F5F5);
        statusRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        daemonIcon = new TextView(this);
        daemonIcon.setText("⚪");
        daemonIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        daemonIcon.setPadding(0, 0, dp(10), 0);
        statusRow.addView(daemonIcon);
        daemonHeadline = new TextView(this);
        daemonHeadline.setText("Helper daemon: checking…");
        daemonHeadline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        daemonHeadline.setTypeface(Typeface.DEFAULT_BOLD);
        daemonHeadline.setTextColor(0xFF1A1A1A);
        statusRow.addView(daemonHeadline, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(statusRow, fullWidth());

        daemonStatus = bodySmall("");
        content.addView(daemonStatus, fullWidth());

        LinearLayout daemonBtnRow = buttonRow();
        daemonStartBtn = new Button(this);
        daemonStartBtn.setText("▶ Start…");
        daemonStartBtn.setAllCaps(false);
        daemonStartBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        daemonStartBtn.setOnClickListener(v -> showStartOptions());
        daemonBtnRow.addView(daemonStartBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        daemonStopBtn = new Button(this);
        daemonStopBtn.setText("■ Stop");
        daemonStopBtn.setAllCaps(false);
        daemonStopBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        daemonStopBtn.setOnClickListener(v -> confirmStopDaemon());
        daemonBtnRow.addView(daemonStopBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(daemonBtnRow, fullWidth());

        content.addView(bodySmall("Adds two Low Signal extras Android only allows from a computer: the 4G lock (no hunting for "
                + "weak 5G) and Data Saver while charging. Not needed for anything else. Start it from your Mac over USB; it keeps "
                + "running after you unplug, until the phone restarts. Stop undoes its extras and ends it."), fullWidth());

        // Command + copy (manual fallback)
        LinearLayout cmdRow = new LinearLayout(this);
        cmdRow.setOrientation(LinearLayout.HORIZONTAL);
        cmdRow.setGravity(Gravity.CENTER_VERTICAL);
        cmdRow.setBackgroundColor(0xFF263238);
        cmdRow.setPadding(dp(12), dp(8), dp(8), dp(8));
        TextView cmdText = new TextView(this);
        cmdText.setText(MAC_START);
        cmdText.setTextColor(0xFF8BC34A);
        cmdText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cmdText.setTypeface(Typeface.MONOSPACE);
        cmdText.setTextIsSelectable(true);
        cmdRow.addView(cmdText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button copyBtn = new Button(this);
        copyBtn.setText("Copy");
        copyBtn.setAllCaps(false);
        copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        copyBtn.setOnClickListener(v -> copyToClipboard(MAC_START, "Mac command copied"));
        cmdRow.addView(copyBtn);
        content.addView(cmdRow, fullWidth());

    }

    private TextView batteryWatchStatus;

    private void buildBatteryWatchSection() {
        LinearLayout content = createSection("Battery watch", "Battery watch");
        final Switch on = new Switch(this);
        on.setText("Tell me when an app is busy in the background");
        on.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        on.setTextColor(0xFF1A1A1A);
        on.setChecked(com.pixelcontrols.battery.BatteryWatch.enabled(this));
        on.setOnCheckedChangeListener((b, v) -> com.pixelcontrols.battery.BatteryWatch.setEnabled(MainActivity.this, v));
        content.addView(on, fullWidth());
        batteryWatchStatus = bodySmall("");
        content.addView(batteryWatchStatus, fullWidth());
        LinearLayout row = buttonRow();
        rowButton(row, "Check now", click(new Runnable() { public void run() { batteryWatchNow(); } }));
        rowButton(row, "Exempt apps", click(new Runnable() { public void run() { showExemptApps(); } }));
        content.addView(row, fullWidth());
        content.addView(bodySmall("About once an hour on battery, Pixel Controls looks at the last 2 hours. An app that ran 30 min "
                + "or more in the background (not on screen), or used 50 MB or more of data there, gets a notification: open its "
                + "page to Force stop it or set Battery → Restricted, or tap It's fine to exempt it. Exempt apps (e.g. work apps) "
                + "are never tracked; choose them under Exempt apps. At most 3 a day. "
                + "Android doesn't let apps read other apps' exact battery use, so running time and data stand in for it."), fullWidth());
        updateBatteryWatchUi();
    }

    private void updateBatteryWatchUi() {
        if (batteryWatchStatus == null) return;
        android.content.SharedPreferences p = getSharedPreferences("battery_watch", MODE_PRIVATE);
        long at = p.getLong("lastAt", 0);
        String last = p.getString("last", "");
        batteryWatchStatus.setText(at == 0 ? "No check yet." : "Busiest in the background (checked "
                + DateUtils.getRelativeTimeSpanString(at) + "):\n" + (last.isEmpty() ? "nothing worth mentioning" : last));
    }

    /** Measure now and show it (no notifications: those come from the hourly check). */
    private void batteryWatchNow() {
        new Thread(() -> {
            java.util.Map<String, com.pixelcontrols.battery.BatteryWatch.Usage> m = com.pixelcontrols.battery.BatteryWatch.measure(this);
            List<com.pixelcontrols.battery.BatteryWatch.Usage> top = new ArrayList<>(m.values());
            top.sort((a, b) -> a.bgRunMs != b.bgRunMs ? Long.compare(b.bgRunMs, a.bgRunMs) : Long.compare(b.bgBytes, a.bgBytes));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < Math.min(5, top.size()); i++) {
                com.pixelcontrols.battery.BatteryWatch.Usage u = top.get(i);
                if (u.bgRunMs < 60_000 && u.bgBytes < 1_000_000) break;
                b.append(com.pixelcontrols.battery.BatteryWatch.labelOf(this, u.pkg)).append(": ")
                        .append(com.pixelcontrols.battery.BatteryWatch.describeUsage(u)).append('\n');
            }
            getSharedPreferences("battery_watch", MODE_PRIVATE).edit().putString("last", b.toString().trim())
                    .putLong("lastAt", System.currentTimeMillis()).apply();
            main.post(this::updateBatteryWatchUi);
        }).start();
    }

    /** Battery watch's exempt apps: never tracked (work apps and the like). Ticked ones first. */
    private void showExemptApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final Map<String, String> apps = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.put(pkg, ri.loadLabel(pm).toString());
        }
        final Set<String> exempt = com.pixelcontrols.battery.BatteryWatch.muted(this);
        for (String p : exempt) if (!apps.containsKey(p)) apps.put(p, com.pixelcontrols.battery.BatteryWatch.labelOf(this, p));
        final List<String> pkgs = new ArrayList<>(apps.keySet());
        Collections.sort(pkgs, (a, b) -> {
            boolean ea = exempt.contains(a), eb = exempt.contains(b);
            if (ea != eb) return ea ? -1 : 1;
            return apps.get(a).compareToIgnoreCase(apps.get(b));
        });
        String[] labels = new String[pkgs.size()];
        boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = apps.get(pkgs.get(i));
            checked[i] = exempt.contains(pkgs.get(i));
        }
        final Set<String> result = new HashSet<>(exempt);
        new AlertDialog.Builder(this)
                .setTitle("Exempt from Battery watch")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    if (isChecked) result.add(pkgs.get(which)); else result.remove(pkgs.get(which));
                })
                .setPositiveButton("Save", (d, w) -> {
                    com.pixelcontrols.battery.BatteryWatch.setExempt(MainActivity.this, result);
                    Toast.makeText(MainActivity.this, result.size() + " app(s) exempt", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- View helpers ---

    private TextView body(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTextColor(0xFF333333);
        v.setLineSpacing(dp(2), 1.3f);
        return v;
    }

    private TextView bodySmall(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTextColor(0xFF666666);
        v.setLineSpacing(dp(1), 1.4f);
        return v;
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackgroundResource(android.R.drawable.btn_default);
        b.setOnClickListener(listener);
        parent.addView(b, fullWidth());
        return b;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackgroundResource(android.R.drawable.btn_default);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout buttonRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button rowButton(LinearLayout row, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setBackgroundResource(android.R.drawable.btn_default);
        b.setOnClickListener(listener);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private LinearLayout.LayoutParams fullWidthWithMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(4);
        return lp;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static View.OnClickListener click(final Runnable r) {
        return new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } };
    }

    // --- Logic methods (unchanged) ---

    @Override
    protected void onResume() {
        super.onResume();
        main.post(ticker);
        pollDaemon();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            if (++tick % 4 == 0) pollDaemon();
            main.postDelayed(this, 1500);
        }
    };

    private static AdblockVpnService instanceOrNull() {
        return AdblockVpnService.isRunning() ? AdblockVpnService.get() : null;
    }

    private void setAdblock(boolean on) {
        if (!on) {
            Adblock.stop(this);
        } else {
            Intent consent = Adblock.consentIntent(this);
            if (consent != null) startActivityForResult(consent, REQ_VPN);
            else Adblock.start(this);
        }
        main.postDelayed(new Runnable() { public void run() { refresh(); } }, 600);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) Adblock.start(this);
            else Toast.makeText(this, "Adblock needs VPN permission", Toast.LENGTH_LONG).show();
            refresh();
        }
    }

    private boolean privateDnsBroken() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            android.net.LinkProperties lp = cm.getLinkProperties(n);
            return lp != null && nc != null && lp.getPrivateDnsServerName() != null
                    && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && !nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void fixDns() {
        new Thread(new Runnable() { public void run() {
            final boolean ok = Adblock.privateDnsFix(MainActivity.this);
            main.post(new Runnable() { public void run() {
                if (ok) {
                    Toast.makeText(MainActivity.this, "Private DNS set to Automatic", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Tap Private DNS → Automatic → Save", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));
                }
            } });
        } }).start();
    }

    /** One line for the Network doctor: connection type, validated or not, Private DNS mode. */
    private String networkSummary() {
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return "No connection right now";
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            String type = nc == null ? "Connected" : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                    : nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ? "Mobile data" : "Connected";
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) type += " (adblock filter on)";
            boolean ok = nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            String mode = android.provider.Settings.Global.getString(getContentResolver(), "private_dns_mode");
            String dns = "hostname".equals(mode) ? "Private DNS: " + android.provider.Settings.Global.getString(getContentResolver(), "private_dns_specifier")
                    : "off".equals(mode) ? "Private DNS: off" : "Private DNS: Automatic";
            return type + (ok ? ", internet OK" : ", checking internet…") + " · " + dns;
        } catch (RuntimeException e) {
            return "Internet OK";
        }
    }

    private void refresh() {
        boolean broken = privateDnsBroken();
        if (doctor != null) {
            if (broken) {
                doctor.setText("Network doctor: ⚠ No internet. Your Private DNS server can't be reached, and Android won't fall back to normal DNS.");
                doctor.setTextColor(0xFFD93025);
            } else {
                doctor.setText("Network doctor: ✓ " + networkSummary());
                doctor.setTextColor(0xFF2E7D32);
            }
        }
        if (doctorFix != null) {
            doctorFix.setVisibility(broken ? View.VISIBLE : View.GONE);
        }
        updating = true;
        boolean active = Adblock.isActive(this);
        boolean vpnMode = Adblock.MODE_VPN.equals(Adblock.mode(this));
        if (adblockSwitch != null) adblockSwitch.setChecked(active);
        if (modeVpn != null) modeVpn.setChecked(vpnMode);
        if (modeDns != null) modeDns.setChecked(!vpnMode);
        if (videoSwitch != null) {
            videoSwitch.setChecked(!Adblock.videoCompat(this));
            videoSwitch.setEnabled(vpnMode);
        }
        if (adblockStatus != null) {
            // Will be set later in the method
        }
        boolean paused = Adblock.isPaused();
        if (pauseBtn != null) {
            pauseBtn.setText(paused ? "Resume now" : "Pause 10 min");
            pauseBtn.setEnabled(active && vpnMode);
        }
        updating = false;

        StringBuilder sb = new StringBuilder();
        if (!active) {
            sb.append("Off.");
        } else if (!vpnMode) {
            sb.append("On (Lite): all DNS → AdGuard DNS");
        } else {
            DnsFilterEngine e = AdblockVpnService.currentEngine();
            if (e == null) sb.append("Starting…");
            else {
                sb.append(paused ? "Paused. " : "On. ");
                sb.append(String.format(Locale.US, "%,d/%,d blocked", e.blockedQueries.get(), e.totalQueries.get()));
                int n = AdblockVpnService.listSize();
                if (n > 0) sb.append(String.format(Locale.US, " · %,d domains", n));
            }
            sb.append(String.format(Locale.US, " · %d bypass", Adblock.excludedApps(this).size()));
        }
        long upd = Adblock.listUpdatedAt(this);
        sb.append("\nBlocklist: HaGeZi Multi PRO, ")
                .append(upd == 0 ? "bundled" : "updated " + DateUtils.getRelativeTimeSpanString(upd));
        if (adblockStatus != null) adblockStatus.setText(sb);
        if (daemonStatus != null) daemonStatus.setText(daemonLine);
        Boolean up = daemonUp;
        if (daemonIcon != null) daemonIcon.setText(up == null ? "⚪" : up ? "🟢" : "🔴");
        if (daemonHeadline != null) daemonHeadline.setText(up == null ? "Helper daemon: checking…"
                : up ? "Helper daemon: running" : "Helper daemon: stopped");
        if (daemonStartBtn != null) daemonStartBtn.setEnabled(up == null || !up);
        if (daemonStopBtn != null) daemonStopBtn.setEnabled(up == null || up);
        updateAaModeUi();
        updateSoundUi();
        updatePhoneBrightnessUi();
    }

    private void soundProfile(int profile) {
        SoundProfileManager.Result r = SoundProfileManager.applyProfile(this, profile);
        if (r != SoundProfileManager.Result.OK) Toast.makeText(this, SoundProfileManager.message(r, profile), Toast.LENGTH_LONG).show();
    }

    private void updateSoundUi() {
        if (soundStatus == null) return;
        int profile = SoundProfileManager.getActiveProfile(this);
        soundStatus.setText("Active: " + SoundProfileManager.getProfileName(profile)
                + " — " + SoundProfileManager.getProfileDescription(profile));
    }

    private void updatePhoneBrightnessUi() {
        if (phoneBrightnessStatus == null) return;
        boolean canWrite = AdaptiveBrightnessManager.canWriteSettings(this);
        if (permBtn != null) {
            permBtn.setVisibility(canWrite ? View.GONE : View.VISIBLE);
        }
        boolean isAuto = AdaptiveBrightnessManager.isAdaptive(this);
        int curPct = AdaptiveBrightnessManager.getCurrentBrightnessPercent(this);
        int mPct = AdaptiveBrightnessManager.getManualBrightnessPercent(this);
        phoneBrightnessStatus.setText(isAuto
                ? "Phone: Auto (Current: " + curPct + "%)"
                : "Phone: Manual (" + curPct + "% · M: " + mPct + "%)");
        if (phoneAdaptiveSwitch != null && !updating) {
            phoneAdaptiveSwitch.setChecked(isAuto);
        }
        if (phoneBrightnessBar != null && !phoneBrightnessBar.isPressed()) {
            phoneBrightnessBar.setProgress(curPct);
        }
    }

    private void showRecent() {
        DnsFilterEngine e = AdblockVpnService.currentEngine();
        if (e == null) { Toast.makeText(this, "Enable Full filter first", Toast.LENGTH_SHORT).show(); return; }
        final List<DnsFilterEngine.BlockedEntry> list = e.recentBlocked();
        if (list.isEmpty()) { Toast.makeText(this, "Nothing blocked yet", Toast.LENGTH_SHORT).show(); return; }
        String[] items = new String[list.size()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.length; i++) {
            DnsFilterEngine.BlockedEntry b = list.get(i);
            items[i] = b.domain + "\n   " + (b.app != null ? b.app + " · " : "")
                    + DateUtils.getRelativeTimeSpanString(b.time, now, DateUtils.SECOND_IN_MILLIS);
        }
        new AlertDialog.Builder(this)
                .setTitle("Recently blocked")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final String dom = list.get(which).domain;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Allow " + dom + "?")
                                .setMessage("Lookups for this domain (and subdomains) will no longer be blocked.")
                                .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w2) {
                                        Adblock.allowDomain(MainActivity.this, dom);
                                        Toast.makeText(MainActivity.this, dom + " allowed", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showLookups() {
        DnsFilterEngine e = AdblockVpnService.currentEngine();
        if (e == null) { Toast.makeText(this, "Enable Full filter first", Toast.LENGTH_SHORT).show(); return; }
        final List<DnsFilterEngine.BlockedEntry> list = e.recentLookups();
        if (list.isEmpty()) { Toast.makeText(this, "No lookups yet", Toast.LENGTH_SHORT).show(); return; }
        String[] items = new String[list.size()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.length; i++) {
            DnsFilterEngine.BlockedEntry b = list.get(i);
            items[i] = (b.blocked ? "⛔ " : "✓ ") + b.domain + "\n   " + (b.app != null ? b.app : "unknown") + " · "
                    + DateUtils.getRelativeTimeSpanString(b.time, now, DateUtils.SECOND_IN_MILLIS);
        }
        new AlertDialog.Builder(this)
                .setTitle("Recent lookups (⛔ = blocked)")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final DnsFilterEngine.BlockedEntry b = list.get(which);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle((b.blocked ? "Allow " : "Block ") + b.domain + "?")
                                .setMessage(b.blocked ? "Will no longer be blocked (including subdomains)."
                                        : "Will be blocked. If app breaks, come back and allow it.")
                                .setPositiveButton(b.blocked ? "Allow" : "Block", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d2, int w2) {
                                        if (b.blocked) Adblock.allowDomain(MainActivity.this, b.domain);
                                        else Adblock.blockDomain(MainActivity.this, b.domain);
                                        Toast.makeText(MainActivity.this, b.domain + (b.blocked ? " allowed" : " blocked"), Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showPacks() {
        final List<String> names = new ArrayList<>(com.pixelcontrols.adblock.AdblockDefaults.OTT_PACKS.keySet());
        final Set<String> off = Adblock.disabledPacks(this);
        String[] labels = new String[names.size()];
        boolean[] on = new boolean[names.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = names.get(i) + "  (" + com.pixelcontrols.adblock.AdblockDefaults.OTT_PACKS.get(names.get(i)).size() + " servers)";
            on[i] = !off.contains(names.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("OTT ad packs")
                .setMultiChoiceItems(labels, on, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean checked) {
                        if (checked) off.remove(names.get(which)); else off.add(names.get(which));
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Adblock.setDisabledPacks(MainActivity.this, off);
                        Toast.makeText(MainActivity.this, Adblock.videoCompat(MainActivity.this)
                                ? "Saved. Packs apply when 'Maximum OTT ad blocking' is on." : "Saved", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Note", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        new AlertDialog.Builder(MainActivity.this).setTitle("What packs do")
                                .setMessage("Packs block each app's separate ad/tracking servers. Turn off if app stops playing.\n\n"
                                        + "No YouTube/YouTube Music pack: ads from same servers as content. Live sports ads stitched into stream stay. Spotify/Gaana audio ads partly from main servers.")
                                .setPositiveButton("OK", null).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showUserBlocklist() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setHint("one domain per line, e.g.\nads.example.com");
        input.setText(Adblock.userBlocklistText(this));
        new AlertDialog.Builder(this)
                .setTitle("My blocked domains")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Adblock.setUserBlocklistText(MainActivity.this, input.getText().toString()); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showExcludedApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final Map<String, String> apps = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            apps.put(pkg, ri.loadLabel(pm).toString());
        }
        final Set<String> excluded = Adblock.excludedApps(this);
        final List<String> pkgs = new ArrayList<>(apps.keySet());
        Collections.sort(pkgs, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                boolean ea = excluded.contains(a), eb = excluded.contains(b);
                if (ea != eb) return ea ? -1 : 1;
                return apps.get(a).compareToIgnoreCase(apps.get(b));
            }
        });
        String[] labels = new String[pkgs.size()];
        boolean[] checked = new boolean[pkgs.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = apps.get(pkgs.get(i));
            checked[i] = excluded.contains(pkgs.get(i));
        }
        final Set<String> result = new HashSet<>(excluded);
        new AlertDialog.Builder(this)
                .setTitle("Apps that bypass the filter")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        if (isChecked) result.add(pkgs.get(which)); else result.remove(pkgs.get(which));
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Adblock.setExcludedApps(MainActivity.this, result);
                        Toast.makeText(MainActivity.this, "Saved. Bypassing apps can't detect the VPN.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAllowlist() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(4);
        input.setHint("one domain per line, e.g.\nexample.com");
        input.setText(Adblock.allowlistText(this));
        new AlertDialog.Builder(this)
                .setTitle("Always-allowed domains")
                .setView(input)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Adblock.setAllowlistText(MainActivity.this, input.getText().toString()); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateList() {
        Toast.makeText(this, "Downloading latest blocklist…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { public void run() {
            String msg;
            try {
                msg = String.format(Locale.US, "Blocklist updated: %,d domains", Adblock.updateBlocklist(MainActivity.this));
            } catch (IOException e) {
                msg = "Update failed: " + e.getMessage();
            }
            final String m = msg;
            main.post(new Runnable() { public void run() { Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show(); refresh(); } });
        } }).start();
    }

    private void daemon(final String cmd, final boolean touchesDns) {
        new Thread(new Runnable() { public void run() {
            final Intent ask;
            final String r;
            if ("ON".equals(cmd) || "OFF".equals(cmd)) {
                boolean on = "ON".equals(cmd);
                LowSignal.Result res = on ? LowSignal.turnOn(MainActivity.this) : LowSignal.turnOff(MainActivity.this);
                ask = res.consent;
                r = LowSignal.describe(on, res);
            } else {
                String reply;
                boolean daemonOk;
                try {
                    reply = DaemonClient.send(cmd);
                    daemonOk = reply.startsWith("OK:");
                } catch (IOException e) {
                    reply = null;
                    daemonOk = false;
                }
                ask = null;
                r = reply != null ? reply : "Helper daemon isn't running.";
            }
            main.post(new Runnable() { public void run() {
                Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
                if (ask != null) startActivityForResult(ask, REQ_VPN);
                refresh();
            } });
            pollDaemon();
        } }).start();
    }

    private void pollDaemon() {
        new Thread(new Runnable() { public void run() {
            String line;
            try {
                String s = DaemonClient.send("STATUS");
                daemonUp = s.startsWith("STATUS:");
                if (daemonUp) LowSignal.flushPendingOff(MainActivity.this);
                line = DaemonClient.describeStatus(s);
            } catch (IOException e) {
                daemonUp = false;
                line = "Not running. Only its extras are off: 4G lock and Data Saver while charging."
                        + (LowSignal.isActive(MainActivity.this) ? "\nLow signal ON (app's own settings + DNS accelerator)" : "")
                        + (LowSignal.extrasPendingOff(MainActivity.this) ? "\n⚠ Data Saver + 4G lock from Low signal are still on: they're undone as soon as the helper runs." : "");
            }
            com.pixelcontrols.adblock.DnsFilterEngine eng = AdblockVpnService.currentEngine();
            if (eng != null && (eng.cacheHits.get() > 0 || eng.staleServed.get() > 0)) {
                line += String.format(Locale.US, "\nDNS accelerator: %,d cache hits, %,d stale served", eng.cacheHits.get(), eng.staleServed.get());
            }
            daemonLine = line;

            main.post(new Runnable() {
                public void run() {
                    refresh();
                }
            });
        } }).start();
    }

    private void copyToClipboard(String text, String toast) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("pixelcontrols", text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private void showStartOptions() {
        new AlertDialog.Builder(this)
                .setTitle("Start the helper")
                .setMessage("Plug the phone into your Mac with USB and run, in a Terminal:\n\n" + MAC_START + "\n\n"
                        + "It keeps running after you unplug, until the phone restarts. It only adds the 4G lock and Data Saver "
                        + "while charging; everything else works without it.")
                .setPositiveButton("Copy command", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { copyToClipboard(MAC_START, "Mac command copied"); }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void confirmStopDaemon() {
        new AlertDialog.Builder(this)
                .setTitle("Stop the helper daemon?")
                .setMessage("Its extras are undone (4G lock, Data Saver while charging). "
                        + "Everything else keeps working, including Low signal's own settings.")
                .setPositiveButton("Stop", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { stopHelperDaemon(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void stopHelperDaemon() {
        new Thread(new Runnable() { public void run() {
            String reply;
            try { reply = DaemonClient.send("SHUTDOWN"); } catch (IOException e) { reply = null; }
            final boolean ok = reply != null && reply.startsWith("OK:");
            main.post(new Runnable() { public void run() {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(MainActivity.this, ok ? "Stopping helper daemon…" : "Helper daemon isn't running", Toast.LENGTH_SHORT).show();
            } });
            main.postDelayed(new Runnable() { public void run() { pollDaemon(); } }, 2500);
            main.postDelayed(new Runnable() { public void run() { pollDaemon(); } }, 5000);
        } }).start();
    }

    private void updateAaModeUi() {
        if (aaModeStatus == null) return;
        boolean wifi = com.pixelcontrols.auto.Car.wifiOn(this), plugged = com.pixelcontrols.auto.Car.plugged(this);
        String car = com.pixelcontrols.auto.Car.prefs(this).getString("car", null);
        if (aaLog != null) {
            String[] lines = com.pixelcontrols.auto.Car.logText(this).split("\n");
            StringBuilder b = new StringBuilder("Last car events:");
            for (int i = 0; i < lines.length && i < 8; i++) if (!lines[i].isEmpty()) b.append('\n').append(lines[i]);
            aaLog.setText(lines.length == 0 || lines[0].isEmpty() ? "Last car events: none yet" : b.toString());
        }
        aaModeStatus.setText(com.pixelcontrols.auto.Car.describe(this)
                + "\nCable: " + (plugged ? "plugged in" : "not plugged in") + " · Wi-Fi: " + (wifi ? "on" : "off")
                + (car == null ? " · no car seen yet" : " · last car: " + car));
    }
}
