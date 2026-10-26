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
    private final ExecutorService coverIo = Executors.newSingleThreadExecutor();

    private Backdrop.SceneView sceneView;
    private BentoView bento;
    private Shelf.ShelfView shelf;
    private DockView dock;
    private FrameLayout shelfSlot, dockSlot;
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
    private List<Shelf.Item> shelfItems;
    private boolean shelfComplete, weatherBusy, shelfBusy, resumed;
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

        shelfSlot = findViewById(R.id.shelfSlot);
        shelf = new Shelf.ShelfView(this);
        ViewGroup.LayoutParams s = shelfSlot.getLayoutParams();
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(s.width + 2 * shelf.overhang, s.height + shelf.overhang);
        sp.leftMargin = -shelf.overhang;
        sp.topMargin = -shelf.overhang;
        shelfSlot.addView(shelf, sp);

        dockSlot = findViewById(R.id.dockSlot);
        dockSlot.addView(new Backdrop.Glass(this, sceneView, Ui.dp(this, Ui.R_TRAY), 24f), match());
        dock = new DockView(this, getResources().getInteger(R.integer.columns));
        dockSlot.addView(dock, match());
        content = findViewById(R.id.content);

        dock.above = shelf::lastFocused;
        shelf.below = dock::lastFocused;
        shelf.above = () -> sports;
        sports.below = shelf::lastFocused;
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
        // Back twice within 1.5 s on Home starts the screensaver (as before the restyle)
        long now = SystemClock.uptimeMillis();
        if (now - lastBack < 1500) {
            lastBack = 0;
            try {
                startActivity(new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        } else {
            lastBack = now;
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
        for (int i = 0; i < Shelf.COUNT; i++) {
            Shelf.PosterView p = shelf.posters.get(i);
            Shelf.Item item = items != null && i < items.size() ? items.get(i) : null;
            if (item == null) {
                p.setText(i == 0 && items == null ? getString(R.string.shelf_waiting) : "", "");
                p.onPress = null;
                if (pictures != null || items == null) p.setArt(null);
                p.glowColor = Colors.FALLBACK;
            } else {
                p.setText(item.title, item.meta);
                p.onPress = () -> openPkg(item.pkg);
                if (pictures != null) p.setArt(pictures.get(i));
                // The glow takes the colour of the app the poster opens.
                DockView.App a = apps.get(item.pkg);
                Integer brand = DockView.BRANDS.get(item.pkg);
                p.glowColor = a != null && a.color != Colors.FALLBACK ? a.color : brand != null ? brand : Colors.FALLBACK;
            }
            if (p.isFocused()) sceneView.glow(p.glowColor);
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
        if (shelfBusy || apps.isEmpty() || !shelfSource.hasToken()) return;
        shelfBusy = true;
        final long now = System.currentTimeMillis();
        final boolean online = online(), complete = shelfComplete;
        final boolean quiet = shelfQuietUntil - now > 0;
        final List<Shelf.Item> current = shelfItems;
        final Set<String> installed = new HashSet<>(apps.keySet());
        final int[][] sizes = posterSizes();
        shelfIo.execute(() -> {
            try {
                List<Shelf.Item> data = current;
                boolean whole = complete;
                if (data == null) {
                    data = shelfSource.cached();
                    if (data != null) whole = present(data, sizes, false);
                }
                boolean stale = shelfSource.age(now) >= SHELF_EVERY_MS;
                if (quiet || !online || (!stale && data != null && whole)) return;
                // Stale or missing: ask the service. Fresh but a picture missing: just fetch the pictures.
                if (stale || data == null) data = shelfSource.fetch(now, installed);
                present(data, sizes, true);
            } catch (IOException e) {
                // Offline, the service hiccuped, or too few shows this week: keep the shelf that's up.
                handler.post(() -> {
                    shelfQuietUntil = System.currentTimeMillis() + (shelfItems == null ? RETRY_MS : 30 * 60 * 1000L);
                    if (shelfItems == null && resumed) handler.postDelayed(retryShelf, RETRY_MS);
                });
            } finally {
                handler.post(() -> shelfBusy = false);
            }
        });
    }

    /** Cuts the pictures to size (from disk, or downloaded when [online]) and shows it all at once; true if all came. */
    private boolean present(List<Shelf.Item> items, int[][] sizes, boolean online) {
        List<Bitmap> pictures = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Bitmap[] backdrops = new Bitmap[Shelf.COUNT];
        int hw = Math.round(Ui.dp(this, 336)), hh = Math.round(Ui.dp(this, 200)); // half the hero's size on screen
        boolean all = true;
        for (int i = 0; i < items.size() && i < Shelf.COUNT; i++) {
            Shelf.Item it = items.get(i);
            Bitmap b = shelfSource.picture(it.image, sizes[i][0], sizes[i][1], online);
            all &= b != null;
            pictures.add(b);
            keys.add(Net.keyFor(it.image, sizes[i][0], sizes[i][1]));
            Bitmap bd = shelfSource.picture(it.backdrop, hw, hh, online);
            backdrops[i] = Backdrop.Scene.heroMask(bd);
            keys.add(Net.keyFor(it.backdrop, hw, hh));
        }
        while (pictures.size() < Shelf.COUNT) pictures.add(null);
        if (online) shelfSource.prunePictures(keys);
        final boolean complete = all;
        handler.post(() -> {
            shelfItems = items;
            shelfComplete = complete;
            System.arraycopy(backdrops, 0, heroes, 0, Shelf.COUNT);
            showShelf(items, pictures);
            int focused = shelf.posters.indexOf(getCurrentFocus());
            sceneView.hero(heroes[Math.max(0, focused)]);
        });
        return complete;
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
