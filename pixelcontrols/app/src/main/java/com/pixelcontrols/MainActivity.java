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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.pixelcontrols.adblock.Adblock;
import com.pixelcontrols.adblock.AdblockVpnService;
import com.pixelcontrols.adblock.DnsFilterEngine;
import com.pixelcontrols.photo.EnhanceActivity;

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
    private TextView adblockStatus, daemonStatus;
    private Button pauseBtn;
    private boolean updating;
    private volatile String daemonLine = "Checking helper daemon…";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        title("PixelControls", 26);

        // ---------------------------------------------------------- adblock
        section("Adblock");
        adblockSwitch = new Switch(this);
        adblockSwitch.setText("Block ads, pop-ups & trackers");
        adblockSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        adblockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { if (!updating) setAdblock(on); }
        });
        root.addView(adblockSwitch, fullWidth());
        adblockStatus = body("");

        RadioGroup modes = new RadioGroup(this);
        modeVpn = new RadioButton(this);
        modeVpn.setId(View.generateViewId());
        modeVpn.setText("Full filter (recommended): per-app exclusions, live log, works without the daemon");
        modeDns = new RadioButton(this);
        modeDns.setId(View.generateViewId());
        modeDns.setText("Lite: Private DNS → AdGuard DNS (no VPN icon; needs the helper daemon; no exclusions)");
        modes.addView(modeVpn);
        modes.addView(modeDns);
        modes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                if (updating) return;
                Intent consent = Adblock.setMode(MainActivity.this, id == modeDns.getId() ? Adblock.MODE_DNS : Adblock.MODE_VPN);
                if (consent != null) startActivityForResult(consent, REQ_VPN);
                refresh();
            }
        });
        root.addView(modes, fullWidth());

        videoSwitch = new Switch(this);
        videoSwitch.setText("Maximum OTT ad blocking");
        videoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) { if (!updating) Adblock.setVideoCompat(MainActivity.this, !on); }
        });
        root.addView(videoSwitch, fullWidth());
        small("Also blocks Google's video-ad servers used by many OTT apps and sites. Removes more pre-roll ads, but some players may stall or say \"ad blocker detected\"; if so, turn this off or exclude that app. "
                + "Ads stitched into the stream itself (YouTube app, most live sports) can't be removed by any network filter.");

        button("OTT ad packs (per app)…", click(new Runnable() { public void run() { showPacks(); } }));
        pauseBtn = button("Pause 10 minutes", new View.OnClickListener() {
            @Override public void onClick(View v) {
                AdblockVpnService s = instanceOrNull();
                if (s == null) return;
                if (s.isPaused()) s.resumeNow(); else s.pause();
                Toast.makeText(MainActivity.this, s.isPaused() ? "Paused for 10 minutes" : "Resumed", Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        small("If a site or app refuses to work with an ad blocker, pause, use it, and filtering resumes on its own.");
        button("Recently blocked (tap one to allow it)…", click(new Runnable() { public void run() { showRecent(); } }));
        button("Recent lookups by app (tap one to block it)…", click(new Runnable() { public void run() { showLookups(); } }));
        small("Hunting an app's ad server: open the app, wait for an ad, then come here. Look for ad-sounding servers the app contacted at that moment (ads, adserver, vast, imasdk, …) and tap to block. If the app then breaks, unblock it from the same list.");
        button("My blocked domains…", click(new Runnable() { public void run() { showUserBlocklist(); } }));
        button("Apps that bypass the filter…", click(new Runnable() { public void run() { showExcludedApps(); } }));
        button("Always-allowed domains…", click(new Runnable() { public void run() { showAllowlist(); } }));
        button("Update blocklist now", click(new Runnable() { public void run() { updateList(); } }));
        small("Tip: add the Adblock tile to Quick Settings: swipe down twice → pencil icon → drag \"Adblock\" up. "
                + "Long-press the app icon for a \"Toggle Adblock\" shortcut too.");

        // ---------------------------------------------------------- photo
        section("Pro Enhance (photos)");
        body("Open any photo in Google Photos → Share → Pro Enhance (or Edit → ⋮ → Edit in… → Pro Enhance). "
                + "You get a copy that looks shot with the right exposure, white balance, and detail; the original is never changed.");
        button("Enhance a photo…", click(new Runnable() { public void run() { startActivity(new Intent(MainActivity.this, EnhanceActivity.class)); } }));

        // ---------------------------------------------------------- daemon
        section("Network & display (helper daemon)");
        daemonStatus = body("");
        LinearLayout row1 = row();
        rowButton(row1, "Low signal ON", click(new Runnable() { public void run() { daemon("ON", true); } }));
        rowButton(row1, "Low signal OFF", click(new Runnable() { public void run() { daemon("OFF", true); } }));
        LinearLayout row2 = row();
        rowButton(row2, "DSLR colors ON", click(new Runnable() { public void run() { daemon("DSLR_ON", false); } }));
        rowButton(row2, "DSLR colors OFF", click(new Runnable() { public void run() { daemon("DSLR_OFF", false); } }));
        small("Low signal mode: 4G/3G only (no 5G hunting), Data Saver and Battery Saver on, and Private DNS set to Automatic, "
                + "which stays encrypted when the network allows it and falls back to normal DNS on weak signal so you never lose internet. "
                + "Turning it off restores your own Private DNS setting.");
        small("The daemon is started from a computer with: adb push start_daemon.sh /data/local/tmp/ && adb shell sh /data/local/tmp/start_daemon.sh");

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

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
            main.postDelayed(this, 1500);
        }
    };

    private static AdblockVpnService instanceOrNull() {
        return AdblockVpnService.isRunning() ? AdblockVpnService.get() : null;
    }

    // ------------------------------------------------------------------ adblock actions
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
            else Toast.makeText(this, "Adblock needs the VPN permission to filter ads", Toast.LENGTH_LONG).show();
            refresh();
        }
    }

    private void refresh() {
        updating = true;
        boolean active = Adblock.isActive(this);
        boolean vpnMode = Adblock.MODE_VPN.equals(Adblock.mode(this));
        adblockSwitch.setChecked(active);
        modeVpn.setChecked(vpnMode);
        modeDns.setChecked(!vpnMode);
        videoSwitch.setChecked(!Adblock.videoCompat(this));
        videoSwitch.setEnabled(vpnMode);
        boolean paused = Adblock.isPaused();
        pauseBtn.setText(paused ? "Resume now" : "Pause 10 minutes");
        pauseBtn.setEnabled(active && vpnMode);
        updating = false;

        StringBuilder sb = new StringBuilder();
        if (!active) {
            sb.append("Off.");
        } else if (!vpnMode) {
            sb.append("On (Lite): all DNS goes to AdGuard DNS, which blocks ads and trackers.");
        } else {
            DnsFilterEngine e = AdblockVpnService.currentEngine();
            if (e == null) sb.append("Starting…");
            else {
                sb.append(paused ? "Paused. " : "On. ");
                sb.append(String.format(Locale.US, "%,d of %,d lookups blocked", e.blockedQueries.get(), e.totalQueries.get()));
                int n = AdblockVpnService.listSize();
                if (n > 0) sb.append(String.format(Locale.US, " · %,d domains on the list", n));
            }
            sb.append(String.format(Locale.US, " · %d app(s) bypass", Adblock.excludedApps(this).size()));
        }
        long upd = Adblock.listUpdatedAt(this);
        sb.append("\nBlocklist: HaGeZi Multi PRO, ")
                .append(upd == 0 ? "bundled copy" : "updated " + DateUtils.getRelativeTimeSpanString(upd));
        adblockStatus.setText(sb);
        daemonStatus.setText(daemonLine);
    }

    private void showRecent() {
        DnsFilterEngine e = AdblockVpnService.currentEngine();
        if (e == null) { Toast.makeText(this, "Turn on the Full filter to see what gets blocked", Toast.LENGTH_SHORT).show(); return; }
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
                                .setMessage("Use this if an app or site broke. Lookups for this domain (and its subdomains) will no longer be blocked.")
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
        if (e == null) { Toast.makeText(this, "Turn on the Full filter to see lookups", Toast.LENGTH_SHORT).show(); return; }
        final List<DnsFilterEngine.BlockedEntry> list = e.recentLookups();
        if (list.isEmpty()) { Toast.makeText(this, "No lookups yet", Toast.LENGTH_SHORT).show(); return; }
        String[] items = new String[list.size()];
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.length; i++) {
            DnsFilterEngine.BlockedEntry b = list.get(i);
            items[i] = (b.blocked ? "⛔ " : "✓ ") + b.domain + "\n   " + (b.app != null ? b.app : "unknown app") + " · "
                    + DateUtils.getRelativeTimeSpanString(b.time, now, DateUtils.SECOND_IN_MILLIS);
        }
        new AlertDialog.Builder(this)
                .setTitle("Recent lookups (⛔ = blocked)")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final DnsFilterEngine.BlockedEntry b = list.get(which);
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle((b.blocked ? "Allow " : "Block ") + b.domain + "?")
                                .setMessage(b.blocked ? "It will no longer be blocked (including subdomains)."
                                        : "Lookups for this domain and its subdomains will be blocked. If the app stops working, come back and allow it again.")
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
                                ? "Saved. Packs apply when \"Maximum OTT ad blocking\" is on." : "Saved", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Note", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        new AlertDialog.Builder(MainActivity.this).setTitle("What packs can and can't do")
                                .setMessage("Packs block each app's separate ad and ad-tracking servers. Turn a pack off if that app stops playing.\n\n"
                                        + "There is no YouTube / YouTube Music pack: their ads come from the same servers as the videos and songs, "
                                        + "so no network blocker can remove them. Live-sports ads stitched into the stream also stay. "
                                        + "Spotify and Gaana audio ads are partly delivered through their main servers, so some may still play.")
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
                        Toast.makeText(MainActivity.this, "Saved. Bypassing apps also can't detect the VPN.", Toast.LENGTH_LONG).show();
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
        Toast.makeText(this, "Downloading the latest blocklist…", Toast.LENGTH_SHORT).show();
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

    // ------------------------------------------------------------------ daemon
    private void daemon(final String cmd, final boolean touchesDns) {
        new Thread(new Runnable() { public void run() {
            String reply;
            try {
                reply = DaemonClient.send(cmd);
                if (touchesDns) Adblock.reassertAfterDaemonChange(MainActivity.this);
            } catch (IOException e) {
                reply = "Helper daemon isn't running (start it with adb, see below).";
            }
            final String r = reply;
            main.post(new Runnable() { public void run() { Toast.makeText(MainActivity.this, r, Toast.LENGTH_SHORT).show(); } });
            pollDaemon();
        } }).start();
    }

    private void pollDaemon() {
        new Thread(new Runnable() { public void run() {
            String line;
            try {
                String s = DaemonClient.send("STATUS");
                line = "Daemon running · " + s.replace("STATUS:", "").replace(':', ' ');
                if (s.contains("dns_fallback=1")) {
                    line += "\n⚠ AdGuard's secure DNS couldn't be reached on this network, so DNS was switched to "
                            + "Automatic to keep you online (Lite adblock is paused). On weak signal, use the Full filter instead; it works on any network.";
                }
            } catch (IOException e) {
                line = "Daemon not running. Low signal, DSLR colors and Lite adblock need it; the Full filter and Pro Enhance don't.";
            }
            daemonLine = line;
        } }).start();
    }

    // ------------------------------------------------------------------ tiny view helpers
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private void title(String t, int sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(v, fullWidth());
    }

    private void section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = fullWidth();
        lp.topMargin = dp(28);
        root.addView(v, lp);
    }

    private TextView body(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(v, fullWidth());
        return v;
    }

    private void small(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setAlpha(0.7f);
        root.addView(v, fullWidth());
    }

    private static View.OnClickListener click(final Runnable r) {
        return new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } };
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b, fullWidth());
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        root.addView(r, fullWidth());
        return r;
    }

    private void rowButton(LinearLayout row, String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }
}
