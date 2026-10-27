package com.balaji.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: the clock card top left, "New on your
 * apps" under it, and every app in the dock along the bottom, over a slowly drifting background with
 * frosted glass behind the cards and one soft light below the dock in the focused app's colour.
 *
 * Network: Open-Meteo every 30 minutes and the shelf every 4 hours, both cached on disk so they show at once
 * after a reboot, and only while the home screen is in front. Nothing touches disk or network on the main
 * thread.
 */
public class MainActivity extends Activity {
    private static final long CHECK_MS = 5 * 60 * 1000L, RETRY_MS = 60 * 1000L, SHELF_EVERY_MS = 4 * 3600 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService weatherIo = Executors.newSingleThreadExecutor();
    private final ExecutorService shelfIo = Executors.newSingleThreadExecutor();
    private final ExecutorService pictureIo = Executors.newSingleThreadExecutor();
    private final ExecutorService coverIo = Executors.newSingleThreadExecutor();

    private Backdrop.SceneView sceneView;
    private BentoView bento;
    private Shelf.ShelfView shelf;
    private Shelf.SpotlightView spotlight;
    private DockView dock;
    private FrameLayout shelfSlot, spotlightSlot, dockSlot;
    private View content;
    private android.animation.ValueAnimator pageAnim;
    private final Bitmap[] heroes = new Bitmap[Shelf.COUNT];

    private Sports.CardView sports;
    private Sports.Source sportsSource;
    private Sports.Match match, cricket;
    private Sports.Race race;
    private Bitmap homeCrest, awayCrest, cricketHomeCrest, cricketAwayCrest;
    private boolean sportsBusy;

    private Weather.Source weatherSource;
    private Shelf.Source shelfSource;
    private Weather forecast;
    private Weather.Reading reading;
    private List<Shelf.Item> shelfItems, shelfPool;
    /** The titles' pages, page 1 the newest ([Shelf.pages]); [shelfSet] is the one showing. */
    private final List<List<Shelf.Item>> shelfSets = new ArrayList<>();
    private int shelfSet, shelfTicket;
    /** Inputs or Settings, whichever had focus last (Up from the sports card goes there). */
    private View lastQuick;
    private Shelf.ShelfButton controlButton;
    private ControlCenter controlCenter;
    private boolean weatherBusy, shelfBusy, resumed;
    private long shelfQuietUntil;

    private final Map<String, DockView.App> apps = new LinkedHashMap<>();
    private String signature = "";
    private long lastBack;

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        int effects = getResources().getInteger(R.integer.effects);

        sceneView = new Backdrop.SceneView(this, new Backdrop.Scene(effects));
        ((FrameLayout) findViewById(R.id.sceneSlot)).addView(sceneView, match());

        FrameLayout bentoSlot = findViewById(R.id.bentoSlot);
        bentoSlot.addView(new Backdrop.Glass(this, sceneView, BentoView.corner(this), 18f), match());
        bento = new BentoView(this, getString(R.string.weather_place), effects > 0);
        bentoSlot.addView(bento, match());

        FrameLayout sportsSlot = findViewById(R.id.sportsSlot);
        sportsSlot.addView(new Backdrop.Glass(this, sceneView, BentoView.corner(this), 18f), match());
        sports = new Sports.CardView(this, effects > 0);
        int rs = sports.ringSpacePx();
        ViewGroup.LayoutParams ss = sportsSlot.getLayoutParams();
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ss.width + 2 * rs, ss.height + 2 * rs);
        sl.leftMargin = -rs;
        sl.topMargin = -rs;
        sportsSlot.addView(sports, sl);
        sportsSource = new Sports.Source(getCacheDir());

        // Over the sports card: the Control Center's button (Inputs and Settings live in it). Up from the
        // card reaches it, Down goes back.
        FrameLayout quickSlot = findViewById(R.id.quickSlot);
        controlButton = new Shelf.ShelfButton(this, R.drawable.ph_sliders_horizontal_bold, "Control Center");
        int quick = Math.round(Shelf.ShelfButton.diameter(this) + 2 * controlButton.ringSpace);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(quick, quick);
        gp.leftMargin = Ui.dpi(this, 30f);
        quickSlot.addView(controlButton, gp);
        controlButton.onPress = this::openControlCenter;
        controlButton.setId(View.generateViewId());
        // Nowhere further left, right or up: stay put.
        controlButton.setNextFocusLeftId(controlButton.getId());
        controlButton.setNextFocusRightId(controlButton.getId());
        controlButton.setNextFocusUpId(controlButton.getId());
        lastQuick = controlButton;

        spotlight = new Shelf.SpotlightView(this);
        spotlightSlot = findViewById(R.id.spotlightSlot);
        spotlightSlot.addView(spotlight, match());
        spotlightSlot.setVisibility(View.INVISIBLE); // the shelf starts folded

        shelfSlot = findViewById(R.id.shelfSlot);
        shelf = new Shelf.ShelfView(this);
        ViewGroup.LayoutParams s = shelfSlot.getLayoutParams();
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(s.width + 2 * shelf.overhang, s.height + 2 * shelf.overhang);
        sp.leftMargin = -shelf.overhang;
        sp.topMargin = -shelf.overhang;
        shelfSlot.addView(shelf, sp);

        dockSlot = findViewById(R.id.dockSlot);
        dockSlot.addView(new Backdrop.Glass(this, sceneView, Ui.dp(this, Ui.R_TRAY), 24f), match());
        dock = new DockView(this, getResources().getInteger(R.integer.columns));
        dockSlot.addView(dock, match());
        content = findViewById(R.id.content);
        controlCenter = new ControlCenter(this, sceneView);
        ((FrameLayout) findViewById(R.id.root)).addView(controlCenter, match());
        controlCenter.onClose = () -> {
            ((ViewGroup) content).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            controlButton.requestFocus();
        };

        dock.above = shelf::entry;
        shelf.below = dock::lastFocused;
        shelf.above = () -> sports;
        sports.below = shelf::entry;
        sports.above = () -> lastQuick;
        sports.setId(View.generateViewId());
        controlButton.setNextFocusDownId(sports.getId());
        shelf.toggle.onPress = () -> foldShelf(!shelf.collapsed());
        shelf.prev.onPress = () -> showSet(shelfSet - 1);
        shelf.next.onPress = () -> showSet(shelfSet + 1);
        shelf.refresh.onPress = () -> loadShelf(true);
        sports.onFace = color -> sceneView.glow(color);
        dock.onFocus = t -> movePage(dock.rowOf(t));
        findViewById(R.id.root).getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> {
            if (now instanceof ArtView) sceneView.glow(((ArtView) now).glowColor);
            if (now == sports) {
                movePage(-1);
                sceneView.glow(sports.faceColor());
            }
            int i = shelf.posters.indexOf(now);
            if (i >= 0) {
                movePage(-1);
                sceneView.hero(heroes[i]);
                spotlight.show(shelf.posters.get(i).item);
            }
        });

        weatherSource = new Weather.Source(getCacheDir(), getString(R.string.weather_lat), getString(R.string.weather_lon));
        shelfSource = new Shelf.Source(getCacheDir(), BuildConfig.TMDB_TOKEN);
        showShelf(null, null);
        updateClock();

        loadApps();
        focusDock();

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * Shows the dock's [row] whole, with the page as still as it can be: the first row (or the shelf, -1)
     * needs no move; a lower row lifts the page just enough to sit 24 dp above the bottom edge.
     */
    private void movePage(int row) {
        float target = 0f;
        if (row > 0) {
            float bottom = dockSlot.getTop() + dock.rowBottom(row) + Ui.dp(this, Ui.S3 + Ui.S6);
            target = -Math.max(0f, bottom - findViewById(R.id.root).getHeight());
        }
        if (pageAnim != null) pageAnim.cancel();
        if (content.getTranslationY() == target) return;
        pageAnim = android.animation.ValueAnimator.ofFloat(content.getTranslationY(), target);
        pageAnim.setDuration(260);
        pageAnim.setInterpolator(Ui.EASE_OUT);
        pageAnim.addUpdateListener(a -> {
            content.setTranslationY((float) a.getAnimatedValue());
            sceneView.refresh(); // the glass behind the cards follows them
        });
        pageAnim.start();
    }

    private void focusDock() {
        DockView.Tile t = dock.lastFocused();
        if (t != null) {
            t.requestFocus();
            sceneView.glow(t.glowColor);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        keepHotkeysOn();
        sceneView.setRunning(true);
        if (reloadNeeded()) loadApps();
        if (getCurrentFocus() == null) focusDock();
        startClock();
        loadWeather();
        loadShelf();
        loadSports();
        handler.removeCallbacks(refresher);
        handler.postDelayed(refresher, CHECK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        controlCenter.close(); // back on Home, the page as it was
        sceneView.setRunning(false);
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(refresher);
        handler.removeCallbacks(retryWeather);
        handler.removeCallbacks(retryShelf);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(packageReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        try {
            if (cm != null) cm.unregisterNetworkCallback(networkCallback);
        } catch (IllegalArgumentException ignored) {
        }
        weatherIo.shutdownNow();
        shelfIo.shutdownNow();
        pictureIo.shutdownNow();
        coverIo.shutdownNow();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) Net.trim();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusDock(); // Home pressed while we are showing: back to the dock
    }

    @Override
    public void onBackPressed() {
        if (controlCenter.isOpen()) {
            controlCenter.close();
            return;
        }
        // Back twice within 1.5 s on Home starts the screensaver (as before the restyle)
        long now = SystemClock.uptimeMillis();
        if (now - lastBack < 1500) {
            lastBack = 0;
            startScreensaver();
        } else {
            lastBack = now;
        }
    }

    /** The Control Center over the page; the page takes no focus while it's open. */
    private void openControlCenter() {
        controlCenter.open();
        ((ViewGroup) content).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    /** The screensaver now (Control Center's StandBy, and Back twice on Home). */
    void startScreensaver() {
        try {
            startActivity(new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    /** Opens the first of [intents] this TV answers (for the Control Center). */
    void openFirstOf(Intent... intents) {
        openFirst(intents);
    }

    /** Sony's list of inputs (HDMI 1-4, the tuner). */
    void openInputs() {
        openFirst(new Intent("com.android.tv.action.VIEW_INPUTS"), new Intent("com.sony.dtv.tvsettings.intent.action.MANAGE_INPUTS"));
    }

    /**
     * Turns the picture keys (Hotkeys) back on if they're off: Android turns off an accessibility service
     * that keeps crashing, and Sony's updates can clear the list. Needs WRITE_SECURE_SETTINGS, which
     * install.sh grants; without it this does nothing.
     */
    private void keepHotkeysOn() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        String me = new android.content.ComponentName(this, Hotkeys.class).flattenToString();
        android.content.ContentResolver cr = getContentResolver();
        String on = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on != null && Arrays.asList(on.split(":")).contains(me)) return;
        try {
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    on == null || on.isEmpty() || on.equals("null") ? me : on + ":" + me);
            android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) {
        }
    }

    // ------------------------------------------------------------------ apps

    private List<ResolveInfo> query() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> l = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        if (l.isEmpty()) l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        return l;
    }

    private boolean reloadNeeded() {
        StringBuilder sb = new StringBuilder();
        for (ResolveInfo ri : query()) sb.append(ri.activityInfo.packageName).append('|');
        return !sb.toString().equals(signature);
    }

    /**
     * Every app, in order: config.xml's dock_apps, then grid_apps, then the rest A to Z; hidden_apps and
     * this launcher left out. Covers are drawn off the main thread and arrive together.
     */
    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = query();
        StringBuilder sig = new StringBuilder();
        for (ResolveInfo ri : found) sig.append(ri.activityInfo.packageName).append('|');
        signature = sig.toString();

        Set<String> hidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        Map<String, DockView.App> byPkg = new HashMap<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
            CharSequence lab = ri.loadLabel(pm);
            Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            DockView.App a = new DockView.App(pkg, lab == null ? pkg : lab.toString(), intent, ri);
            DockView.App old = apps.get(pkg);
            if (old != null) a.color = old.color;
            byPkg.put(pkg, a);
        }
        List<DockView.App> ordered = new ArrayList<>();
        for (int res : new int[]{R.array.dock_apps, R.array.grid_apps}) {
            for (String p : getResources().getStringArray(res)) {
                DockView.App a = byPkg.remove(p);
                if (a != null) ordered.add(a);
            }
        }
        List<DockView.App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        ordered.addAll(rest);

        apps.clear();
        for (DockView.App a : ordered) apps.put(a.pkg, a);
        boolean hadFocus = dock.hasFocus();
        dock.setApps(ordered, this::launch);
        ViewGroup.LayoutParams dl = dockSlot.getLayoutParams();
        dl.height = dock.trayHeight(dl.width);
        dockSlot.setLayoutParams(dl);
        if (hadFocus || getCurrentFocus() == null) focusDock();

        int[] size = dock.coverSize(dl.width);
        coverIo.execute(() -> {
            Map<String, Bitmap> covers = new HashMap<>();
            Map<String, Integer> colors = new HashMap<>();
            for (DockView.App a : ordered) {
                try {
                    Drawable icon = a.ri.loadIcon(pm);
                    Integer brand = DockView.BRANDS.get(a.pkg);
                    int color = brand != null ? brand : DockView.Covers.iconColor(icon);
                    colors.put(a.pkg, color);
                    covers.put(a.pkg, DockView.Covers.draw(this, icon, color, size[0], size[1]));
                } catch (RuntimeException ignored) {
                }
            }
            handler.post(() -> {
                for (DockView.Tile t : dock.tiles) {
                    Integer c = colors.get(t.app.pkg);
                    if (c != null) {
                        t.app.color = c;
                        t.glowColor = c;
                    }
                    t.setArt(covers.get(t.app.pkg));
                    if (t.isFocused()) sceneView.glow(t.glowColor);
                }
                if (shelfItems != null) showShelf(shelfItems, null);
            });
        });
    }

    private void launch(DockView.App a) {
        try {
            startActivity(new Intent(a.intent));
        } catch (Exception ignored) {
        }
    }

    /** Opens the first of [intents] something on this TV answers. */
    private void openFirst(Intent... intents) {
        for (Intent i : intents) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
                // Not on this TV: try the next.
            }
        }
    }

    private void openPkg(String pkg) {
        DockView.App a = apps.get(pkg);
        if (a != null) launch(a);
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (reloadNeeded()) loadApps();
        }
    };

    // ------------------------------------------------------------------ clock

    private final Calendar calendar = Calendar.getInstance();
    private long minuteStamp = -1;
    private int dayStamp = -1;
    private String hourKey = "";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 5);
        }
    };

    private void startClock() {
        handler.removeCallbacks(ticker);
        // Back from another app: the second hand goes straight to the time, and the zone is read again.
        bento.dial.reset();
        calendar.setTimeZone(TimeZone.getDefault());
        minuteStamp = -1;
        dayStamp = -1;
        handler.post(ticker);
    }

    private void updateClock() {
        long now = System.currentTimeMillis();
        calendar.setTimeInMillis(now);
        int h = calendar.get(Calendar.HOUR_OF_DAY), m = calendar.get(Calendar.MINUTE);
        bento.dial.tick(h, m, calendar.get(Calendar.SECOND));
        long minute = now / 60000;
        if (minute == minuteStamp) return;
        minuteStamp = minute;
        bento.time.setTime(h, m);
        showSports(now);
        boolean lineChanged = false;
        String key = Weather.hourKey(now);
        if (!key.equals(hourKey)) {
            // A new hour: the forecast already holds it, so "now" and "next" move on without a fetch.
            hourKey = key;
            reading = forecast != null ? forecast.readingAt(key) : null;
            bento.sky.setWeather(reading);
            lineChanged = true;
        }
        int day = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
        if (day != dayStamp) {
            dayStamp = day;
            lineChanged = true;
        }
        if (lineChanged) {
            SimpleDateFormat f = new SimpleDateFormat("EEE", Locale.UK);
            f.setTimeZone(calendar.getTimeZone());
            bento.line.setLine(f.format(now).toUpperCase(Locale.UK), calendar.get(Calendar.DAY_OF_MONTH), reading);
        }
    }

    // ------------------------------------------------------------------ weather

    private void showWeather(Weather w) {
        forecast = w;
        hourKey = "";
        minuteStamp = -1;
        updateClock();
    }

    private final Runnable retryWeather = this::loadWeather;

    private void loadWeather() {
        if (weatherBusy) return;
        weatherBusy = true;
        final boolean needCache = forecast == null, online = online();
        weatherIo.execute(() -> {
            Weather cached = needCache ? weatherSource.cached() : null;
            if (cached != null) handler.post(() -> showWeather(cached));
            Weather fresh = null;
            boolean failed = false;
            if (weatherSource.age(System.currentTimeMillis()) >= Weather.EVERY_MS && online) {
                try {
                    fresh = weatherSource.fetch();
                } catch (IOException e) {
                    failed = true;
                }
            }
            final Weather f = fresh;
            final boolean retry = failed;
            handler.post(() -> {
                weatherBusy = false;
                if (f != null) showWeather(f);
                // Offline or Open-Meteo hiccuped: keep what's shown; with nothing yet, try again soon.
                if (retry && reading == null && resumed) handler.postDelayed(retryWeather, RETRY_MS);
            });
        });
    }

    // ------------------------------------------------------------------ sports

    private void showSports(long now) {
        if (sports == null) return;
        sports.arsenal.set(match, match == null ? null : match.ourHome ? homeCrest : awayCrest,
                match == null ? null : match.ourHome ? awayCrest : homeCrest, now);
        sports.india.set(cricket, cricket == null ? null : cricket.ourHome ? cricketHomeCrest : cricketAwayCrest,
                cricket == null ? null : cricket.ourHome ? cricketAwayCrest : cricketHomeCrest, now);
        sports.f1.set(race, now);
    }

    /** From the cache at once, then the network when it's 3 hours old or a match or race is over. */
    private void loadSports() {
        if (sportsBusy) return;
        sportsBusy = true;
        final boolean online = online(), first = match == null && race == null;
        final long now = System.currentTimeMillis();
        final long over = 2 * 3600 * 1000L;
        final boolean finished = (match != null && match.start + over < now) || (race != null && race.start + over < now)
                || (cricket != null && cricket.start + Sports.cricketLength(cricket.competition) < now);
        final int crestPx = Ui.dpi(this, 72f);
        weatherIo.execute(() -> {
            org.json.JSONObject data = first ? sportsSource.cached() : null;
            if (data != null) postSports(data, crestPx, false);
            boolean stale = sportsSource.age(now) >= Sports.EVERY_MS || finished || (first && data == null)
                    || (data != null && !data.has("cricket")); // a cache from before the cricket face
            if (stale && online) {
                try {
                    postSports(sportsSource.fetch(), crestPx, true);
                } catch (IOException ignored) {
                    // Keep what's shown; the next check tries again.
                }
            }
            handler.post(() -> sportsBusy = false);
        });
    }

    private void postSports(org.json.JSONObject data, int crestPx, boolean online) {
        Sports.Match m = Sports.match(data, "match", Sports.ARSENAL_ID);
        Sports.Match c = Sports.match(data, "cricket", Sports.INDIA_ID);
        Sports.Race r = Sports.race(data);
        Bitmap home = m == null ? null : sportsSource.badge(m.homeBadge, m.homeBadgeUrl, crestPx, online);
        Bitmap away = m == null ? null : sportsSource.badge(m.awayBadge, m.awayBadgeUrl, crestPx, online);
        Bitmap cHome = c == null ? null : sportsSource.badge(c.homeBadge, c.homeBadgeUrl, crestPx, online);
        Bitmap cAway = c == null ? null : sportsSource.badge(c.awayBadge, c.awayBadgeUrl, crestPx, online);
        handler.post(() -> {
            match = m;
            cricket = c;
            race = r;
            homeCrest = home;
            awayCrest = away;
            cricketHomeCrest = cHome;
            cricketAwayCrest = cAway;
            long now = System.currentTimeMillis();
            showSports(now);
            if (!sports.flipped) {
                // Show whichever comes first (or is on now), until the viewer flips it themselves.
                long[] starts = {m == null ? Long.MAX_VALUE : m.start, c == null ? Long.MAX_VALUE : c.start, r == null ? Long.MAX_VALUE : r.start};
                int best = 0;
                for (int k = 1; k < 3; k++) if (starts[k] < starts[best]) best = k;
                sports.show(best);
            }
        });
    }

    // ------------------------------------------------------------------ shelf

    private final Runnable retryShelf = this::loadShelf;

    /** Shows [items] with their [pictures]; null items shows the calm empty state . */
    private void showShelf(List<Shelf.Item> items, List<Bitmap> pictures) {
        shelf.setHead(getString(R.string.shelf_head), items == null ? "" : getString(R.string.credit_tmdb));
        shelf.setPage(shelfSet, shelfSets.size());
        boolean folded = shelf.collapsed();
        for (int i = 0; i < Shelf.COUNT; i++) {
            Shelf.PosterView p = shelf.posters.get(i);
            Shelf.Item item = items != null && i < items.size() ? items.get(i) : null;
            // Folded, no posters; open, a short page leaves its empty places out of sight (and out of focus's way).
            p.setVisibility(folded || (item == null && items != null) ? View.INVISIBLE : View.VISIBLE);
            if (item == null) {
                p.setItem(null);
                p.onPress = null;
                if (pictures != null || items == null) p.setArt(null);
                p.glowColor = Colors.FALLBACK;
            } else {
                p.setItem(item);
                p.onPress = () -> openPkg(item.pkg);
                if (pictures != null) p.setArt(pictures.get(i));
                // The glow takes the colour of the app the poster opens.
                DockView.App a = apps.get(item.pkg);
                Integer brand = DockView.BRANDS.get(item.pkg);
                p.glowColor = a != null && a.color != Colors.FALLBACK ? a.color : brand != null ? brand : Colors.FALLBACK;
            }
            if (p.isFocused()) sceneView.glow(p.glowColor);
        }
        if (shelf.findFocus() instanceof Shelf.PosterView && shelf.findFocus().getVisibility() != View.VISIBLE) {
            shelf.entry().requestFocus();
        }
        // The spotlight tells the focused card's story, or the page's first while focus is elsewhere.
        int at = Math.max(0, shelf.posters.indexOf(getCurrentFocus()));
        spotlight.show(items == null || items.isEmpty() ? null : items.get(Math.min(at, items.size() - 1)));
    }

    /** Folds the shelf down to its head line over the dock (how it starts), or opens it out with its posters. */
    private void foldShelf(boolean fold) {
        shelf.setCollapsed(fold);
        spotlightSlot.setVisibility(fold ? View.INVISIBLE : View.VISIBLE);
        showShelf(shelfItems, null);
        if (fold) {
            shelf.toggle.requestFocus();
            sceneView.hero(null);
        } else {
            shelf.lastFocused().requestFocus(); // brings its story and backdrop
        }
    }

    private int[][] posterSizes() {
        ViewGroup.LayoutParams slot = shelfSlot.getLayoutParams();
        Shelf.ShelfView.Geometry g = Shelf.ShelfView.geometry(this, slot.width, slot.height);
        int[][] out = new int[Shelf.COUNT][];
        for (int i = 0; i < Shelf.COUNT; i++) out[i] = new int[]{Math.round(g.widths[i]), Math.round(g.artHeight)};
        return out;
    }

    private void loadShelf() {
        loadShelf(false);
    }

    /**
     * The titles: from disk at once, then from TMDB when they're four hours old ([fresh]: now, for the
     * refresh button, which then goes to page 1 if TMDB has something new, else to the next page, so ‹
     * leads back to the ones seen).
     */
    private void loadShelf(boolean fresh) {
        if (shelfBusy || apps.isEmpty() || !shelfSource.hasToken()) return;
        shelfBusy = true;
        if (fresh) shelf.refresh.spin(true);
        final long now = System.currentTimeMillis();
        final boolean online = online();
        final boolean quiet = !fresh && shelfQuietUntil - now > 0;
        final List<Shelf.Item> known = shelfPool;
        final Set<String> installed = new HashSet<>(apps.keySet());
        final int[][] sizes = posterSizes();
        shelfIo.execute(() -> {
            List<Shelf.Item> pool = known;
            try {
                if (pool == null) {
                    pool = shelfSource.cached();
                    if (pool != null) {
                        List<Shelf.Item> cached = pool;
                        handler.post(() -> usePool(cached, -1, null));
                    }
                }
                // Four hours old, or holding titles that have aged out of the two weeks (then the gaps are
                // filled at once, as the sports card moves on from a finished match).
                boolean stale = shelfSource.age(now) >= SHELF_EVERY_MS
                        || (pool != null && Shelf.fresh(pool, now).size() < pool.size());
                if (!fresh && (quiet || !online || (!stale && pool != null))) return;
                List<Shelf.Item> got = shelfSource.fetch(now, installed);
                handler.post(() -> {
                    int to = -1;
                    String note = null;
                    if (fresh) {
                        Set<String> had = new HashSet<>();
                        if (shelfPool != null) for (Shelf.Item it : shelfPool) had.add(Shelf.key(it));
                        boolean news = false;
                        for (Shelf.Item it : got) news |= !had.contains(Shelf.key(it));
                        int pages = Shelf.pages(got).size();
                        if (news) {
                            to = 0;
                            note = "New titles";
                        } else if (shelfSet + 1 < pages) {
                            to = shelfSet + 1;
                        } else {
                            to = 0;
                            note = "Back to the newest";
                        }
                    }
                    usePool(got, to, note);
                });
                prefetchPictures(got, sizes);
            } catch (IOException e) {
                // Offline, the service hiccuped, or too few shows this week: keep the shelf that's up.
                handler.post(() -> {
                    if (fresh) {
                        if (shelfSets.size() > 1) showSet((shelfSet + 1) % shelfSets.size());
                        shelf.note(online ? "Couldn't reach TMDB" : "Offline");
                    }
                    shelfQuietUntil = System.currentTimeMillis() + (shelfItems == null ? RETRY_MS : 30 * 60 * 1000L);
                    if (shelfItems == null && resumed) handler.postDelayed(retryShelf, RETRY_MS);
                });
            } finally {
                handler.post(() -> {
                    shelfBusy = false;
                    shelf.refresh.spin(false);
                });
            }
        });
    }

    /** Takes [pool] as the shelf's titles, dealt into pages, and shows page [to] (-1: stay on the current page). */
    private void usePool(List<Shelf.Item> pool, int to, String note) {
        shelfPool = Shelf.fresh(pool, System.currentTimeMillis()); // only the last two weeks, however old the list
        shelfSets.clear();
        shelfSets.addAll(Shelf.pages(shelfPool));
        if (shelfSets.isEmpty()) return;
        showSet(Math.min(to >= 0 ? to : shelfSet, shelfSets.size() - 1));
        if (note != null) shelf.note(note);
    }

    /** Shows page [index] of the titles, its pictures from disk (or the network while they're still coming). */
    private void showSet(int index) {
        if (index < 0 || index >= shelfSets.size()) return;
        shelfSet = index;
        final List<Shelf.Item> items = shelfSets.get(index);
        final int[][] sizes = posterSizes();
        final boolean online = online();
        final int ticket = ++shelfTicket;
        if (shelfItems == null || !shelfItems.equals(items)) {
            // Titles at once; pictures as they come.
            shelfItems = items;
            showShelf(items, Collections.nCopies(Shelf.COUNT, (Bitmap) null));
        } else {
            shelf.setPage(shelfSet, shelfSets.size());
        }
        pictureIo.execute(() -> present(items, sizes, online, ticket));
    }

    /** Cuts the pictures for [items] to size and shows them all at once, unless another page was asked for since. */
    private void present(List<Shelf.Item> items, int[][] sizes, boolean online, int ticket) {
        List<Bitmap> pictures = new ArrayList<>();
        Bitmap[] backdrops = new Bitmap[Shelf.COUNT];
        int hw = Math.round(Ui.dp(this, 336)), hh = Math.round(Ui.dp(this, 200)); // half the hero's size on screen
        for (int i = 0; i < items.size() && i < Shelf.COUNT; i++) {
            Shelf.Item it = items.get(i);
            pictures.add(shelfSource.picture(it.image, sizes[i][0], sizes[i][1], online));
            backdrops[i] = Backdrop.Scene.heroMask(shelfSource.picture(it.backdrop, hw, hh, online));
        }
        while (pictures.size() < Shelf.COUNT) pictures.add(null);
        handler.post(() -> {
            if (ticket != shelfTicket) return;
            System.arraycopy(backdrops, 0, heroes, 0, Shelf.COUNT);
            showShelf(items, pictures);
            int focused = shelf.posters.indexOf(getCurrentFocus());
            // Folded, the sky stays clear of backdrops.
            sceneView.hero(shelf.collapsed() ? null : heroes[Math.max(0, focused)]);
        });
    }

    /** Puts every title's pictures on disk, so paging through is instant, and lets go of old ones. */
    private void prefetchPictures(List<Shelf.Item> pool, int[][] sizes) {
        Set<String> keys = new HashSet<>();
        int hw = Math.round(Ui.dp(this, 336)), hh = Math.round(Ui.dp(this, 200));
        for (Shelf.Item it : pool) {
            keys.add(Net.keyFor(it.image, sizes[0][0], sizes[0][1]));
            keys.add(Net.keyFor(it.backdrop, hw, hh));
        }
        for (Shelf.Item it : pool) {
            shelfSource.fetchPicture(it.image, sizes[0][0], sizes[0][1]);
            shelfSource.fetchPicture(it.backdrop, hw, hh);
        }
        shelfSource.prunePictures(keys);
        // Any card still without its picture (another download had it in hand) gets it from disk now.
        handler.post(() -> showSet(shelfSet));
    }

    // ------------------------------------------------------------------ keeping fresh

    private boolean online() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        return cm != null && cm.getActiveNetwork() != null;
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            handler.post(() -> {
                if (resumed) {
                    shelfQuietUntil = 0;
                    loadWeather();
                    loadShelf();
                    loadSports();
                }
            });
        }
    };

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            loadWeather();
            loadShelf();
            loadSports();
            handler.postDelayed(this, CHECK_MS);
        }
    };
}
