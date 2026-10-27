package com.balaji.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * "New and top rated on your apps", from TMDB only: movies and shows released in the last two weeks
 * (a movie in cinemas or on streaming in India; a show's series or its current season), streaming in India
 * on the apps this TV has. No animation (anime, cartoons) and no kids' shows. Newest first: up to four
 * pages of six, page 1 the most recent, each page three Telugu, two English and one Hindi ([LANGUAGES]'
 * shares) while a language has titles left, the newest of the others filling in when it runs out. Titles
 * rated below 6.0 (5+ votes) are left out; on the same day, rated titles best rated first, then the most
 * popular. The arrows page through; refresh asks TMDB again and goes to the next page (page 1 when there's
 * something new).
 *
 * The token comes from the build (launcher/tmdb.properties). TMDB's streaming availability is supplied by
 * JustWatch, and TMDB's terms ask that JustWatch be credited for it; nothing here talks to JustWatch.
 */
final class Shelf {
    private Shelf() {
    }

    static final int COUNT = 6;
    /** At most [PAGES] pages of six to page through, so [POOL] titles, from the first [MAX_ASKED] looked up. */
    static final int PAGES = 4, POOL = PAGES * COUNT, MAX_ASKED = 80;

    static final class Item {
        /**
         * [image]: the card's wide picture; [titled]: it already carries the title, so none is drawn on it;
         * [lang]: "Telugu"; [released]: "2026-10-21", when it came out (a show: its series or season).
         */
        final String title, meta, overview, image, backdrop, pkg, lang, released;
        final boolean titled;

        Item(String title, String meta, String overview, String image, boolean titled, String backdrop, String pkg, String lang,
             String released) {
            this.lang = lang;
            this.released = released;
            this.title = title;
            this.meta = meta;
            this.overview = overview;
            this.image = image;
            this.titled = titled;
            this.backdrop = backdrop;
            this.pkg = pkg;
        }
    }

    // ---- The streaming apps, by TMDB's provider ids for India

    static final class Service {
        final String name, pkg;
        final int tmdbId;

        Service(String name, String pkg, int tmdbId) {
            this.name = name;
            this.pkg = pkg;
            this.tmdbId = tmdbId;
        }
    }

    static final List<Service> SERVICES = java.util.Arrays.asList(
            new Service("Prime Video", "com.amazon.amazonvideo.livingroom", 119),
            new Service("JioHotstar", "in.startv.hotstar", 2336),
            new Service("Netflix", "com.netflix.ninja", 8),
            new Service("SonyLIV", "com.sonyliv", 237),
            new Service("ZEE5", "com.graymatrix.did", 232),
            new Service("aha", "ahaflix.tv", 532),
            new Service("Apple TV", "com.apple.atve.sony.appletv", 350));

    // ---- Languages, in the order this home watches them

    /** TMDB language code, the name shown, its share of the shelf (and of every page) in percent. */
    private static final String[][] LANGUAGES = {
            {"te", "Telugu", "50"},
            {"en", "English", "35"},
            {"hi", "Hindi", "15"},
    };

    // ---- TMDB

    private static JSONObject tmdb(String token, String path, Map<String, String> params) throws IOException {
        StringBuilder q = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : params.entrySet()) {
                q.append(q.length() == 0 ? '?' : '&').append(e.getKey()).append('=')
                        .append(java.net.URLEncoder.encode(e.getValue(), "UTF-8"));
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Authorization", "Bearer " + token);
            return new JSONObject(Net.getString("https://api.themoviedb.org/3" + path + q, headers));
        } catch (JSONException e) {
            throw new IOException("TMDB sent something unexpected for " + path, e);
        }
    }

    /** TMDB rating weighted by votes: (v * n + 6.5 * 20) / (n + 20), so a few votes don't make a 10. */
    private static double score(JSONObject r) {
        double v = r.optDouble("vote_average", 0), n = r.optDouble("vote_count", 0);
        return (v * n + 6.5 * 20) / (n + 20);
    }

    /** Rated: enough votes for the rating to mean something. */
    private static boolean rated(JSONObject r) {
        return r.optInt("vote_count") >= 5;
    }

    /** Animation (anime, cartoons) and, for shows, Kids: never on this shelf. */
    private static final String NO_MOVIE_GENRES = "16", NO_TV_GENRES = "16|10762";

    private static void addResults(List<JSONObject> out, Set<String> seen, JSONObject page, String type) {
        JSONArray r = page.optJSONArray("results");
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            if (o == null || !seen.add(type + o.optInt("id"))) continue;
            if (rated(o) && o.optDouble("vote_average", 0) < 6.0) continue; // rated, and rated poorly
            if (!o.optString("poster_path").startsWith("/") || !o.optString("backdrop_path").startsWith("/")) continue;
            try {
                out.add(o.put("_type", type));
            } catch (JSONException ignored) {
            }
        }
    }

    /**
     * In [lang], on this TV's apps, released in the last two weeks: movies out since [since] (in cinemas or
     * on streaming in India), and shows with an episode out since then (each checked later for when its
     * series or season began). Rated titles first, best rated first; then ones too new to be rated, most
     * popular first.
     */
    private static List<JSONObject> candidates(String token, String lang, String providers, String since, String today) throws IOException {
        Map<String, String> common = new HashMap<>();
        common.put("watch_region", "IN");
        common.put("with_watch_providers", providers);
        common.put("with_watch_monetization_types", "flatrate|free|ads");
        common.put("with_original_language", lang);
        common.put("include_adult", "false");
        common.put("language", "en-IN");
        common.put("sort_by", "popularity.desc");
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Map<String, String> tv = new HashMap<>(common);
        tv.put("without_genres", NO_TV_GENRES);
        tv.put("air_date.gte", since);
        tv.put("air_date.lte", today);
        addResults(out, seen, tmdb(token, "/discover/tv", tv), "tv");

        Map<String, String> cinema = new HashMap<>(common);
        cinema.put("without_genres", NO_MOVIE_GENRES);
        cinema.put("primary_release_date.gte", since);
        cinema.put("primary_release_date.lte", today);
        addResults(out, seen, tmdb(token, "/discover/movie", cinema), "movie");

        Map<String, String> streaming = new HashMap<>(common);
        streaming.put("without_genres", NO_MOVIE_GENRES);
        streaming.put("region", "IN");
        streaming.put("with_release_type", "4|6"); // digital or TV release
        streaming.put("release_date.gte", since);
        streaming.put("release_date.lte", today);
        addResults(out, seen, tmdb(token, "/discover/movie", streaming), "movie");

        Collections.sort(out, (x, y) -> {
            boolean rx = rated(x), ry = rated(y);
            if (rx != ry) return rx ? -1 : 1;
            return rx ? Double.compare(score(y), score(x)) : Double.compare(y.optDouble("popularity"), x.optDouble("popularity"));
        });
        return out;
    }

    /**
     * Every title that qualifies, newest first (up to [POOL]), for the pages the shelf deals. TMDB answers
     * in about a second, so the languages are asked side by side and the titles six at a time. The pool
     * holds each language's newest, by [LANGUAGES]' shares while they last.
     */
    static List<Item> fetch(String token, Set<String> installed, long now) throws IOException {
        Map<Integer, Service> providers = new HashMap<>();
        for (Service s : SERVICES) if (installed.contains(s.pkg)) providers.put(s.tmdbId, s);
        if (providers.isEmpty()) return new ArrayList<>();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(Weather.ZONE);
        Calendar cal = Calendar.getInstance(Weather.ZONE);
        cal.setTimeInMillis(now);
        final String today = f.format(cal.getTime());
        cal.add(Calendar.DAY_OF_MONTH, -14);
        final String since = f.format(cal.getTime());
        final String ids = TextUtils.join("|", providers.keySet());

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<List<JSONObject>>> asked = new ArrayList<>();
            for (String[] l : LANGUAGES) asked.add(pool.submit(() -> candidates(token, l[0], ids, since, today)));

            // Each language's titles checked (details with where it streams, all at once), then newest first.
            int n = LANGUAGES.length;
            List<List<JSONObject>> found = new ArrayList<>();
            List<List<Future<JSONObject>>> details = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                List<JSONObject> c = result(asked.get(k));
                found.add(c);
                List<Future<JSONObject>> d = new ArrayList<>();
                for (int i = 0; i < c.size() && i < MAX_ASKED / n; i++) {
                    JSONObject r = c.get(i);
                    String type = r.optString("_type");
                    d.add(pool.submit(() -> tmdb(token, "/" + type + "/" + r.optInt("id"), type.equals("movie") ? MOVIE_DETAILS : DETAILS)));
                }
                details.add(d);
            }
            List<List<Item>> byLang = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                List<Item> q = new ArrayList<>();
                for (int i = 0; i < details.get(k).size(); i++) {
                    try {
                        Item it = item(found.get(k).get(i), result(details.get(k).get(i)), LANGUAGES[k][1], providers, since, today);
                        if (it != null) q.add(it);
                    } catch (IOException ignored) {
                        // one title that didn't answer
                    }
                }
                Collections.sort(q, NEWEST); // stable: on the same day, the best rated (then most popular) first
                byLang.add(q);
            }

            // Each language's newest, by share: each place goes to the language furthest below its share so
            // far. When a language runs out, the others fill its places.
            List<Item> out = new ArrayList<>();
            Set<String> names = new HashSet<>();
            int[] taken = new int[n];
            while (out.size() < POOL) {
                int pick = -1;
                for (int k = 0; k < n; k++) {
                    if (taken[k] >= byLang.get(k).size()) continue;
                    double share = Double.parseDouble(LANGUAGES[k][2]);
                    if (pick < 0 || taken[k] / share < taken[pick] / Double.parseDouble(LANGUAGES[pick][2])) pick = k;
                }
                if (pick < 0) break;
                Item it = byLang.get(pick).get(taken[pick]++);
                if (names.add(it.title)) out.add(it); // the same title found through two apps: once
            }
            Collections.sort(out, NEWEST);
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static final java.util.Comparator<Item> NEWEST = (x, y) -> y.released.compareTo(x.released);

    /** A title's details, where it streams, and its wide pictures (those with the title on them too). */
    private static final Map<String, String> DETAILS = new HashMap<>();

    static {
        DETAILS.put("append_to_response", "watch/providers,images");
        DETAILS.put("include_image_language", "en,te,hi,null");
    }

    /** A movie's, with its release dates in India too (in cinemas, on streaming). */
    private static final Map<String, String> MOVIE_DETAILS = new HashMap<>(DETAILS);

    static {
        MOVIE_DETAILS.put("append_to_response", "watch/providers,images,release_dates");
    }

    private static <T> T result(Future<T> f) throws IOException {
        try {
            return f.get(40, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e.getCause());
        } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
            throw new IOException("TMDB took too long", e);
        }
    }

    /**
     * The poster for [r] given its details [d], or null if it didn't come out between [since] and [today]
     * or isn't on this TV's apps.
     */
    private static Item item(JSONObject r, JSONObject d, String lang, Map<Integer, Service> providers, String since, String today) {
        boolean tv = r.optString("_type").equals("tv");
        int season = 0;
        String released = null;
        if (tv) {
            // The series, or the season now airing, must have begun in the last two weeks.
            JSONObject last = d.optJSONObject("last_episode_to_air");
            season = last == null ? 0 : last.optInt("season_number");
            String began = null;
            JSONArray seasons = d.optJSONArray("seasons");
            for (int k = 0; seasons != null && k < seasons.length(); k++) {
                JSONObject sn = seasons.optJSONObject(k);
                if (sn != null && sn.optInt("season_number") == season) began = sn.optString("air_date", null);
            }
            if (began == null || began.isEmpty() || began.compareTo(since) < 0 || began.compareTo(today) > 0) return null;
            released = began;
        } else {
            // Out in the window: its first release anywhere, or the first of its releases in India (in cinemas
            // or on streaming) that falls in it.
            String first = r.optString("release_date");
            if (first.compareTo(since) >= 0 && first.compareTo(today) <= 0) released = first;
            JSONObject rd = d.optJSONObject("release_dates");
            JSONArray countries = rd == null ? null : rd.optJSONArray("results");
            for (int k = 0; countries != null && k < countries.length(); k++) {
                JSONObject c = countries.optJSONObject(k);
                if (c == null || !"IN".equals(c.optString("iso_3166_1"))) continue;
                JSONArray dates = c.optJSONArray("release_dates");
                for (int j = 0; dates != null && j < dates.length(); j++) {
                    String day = dates.optJSONObject(j).optString("release_date");
                    if (day.length() < 10) continue;
                    day = day.substring(0, 10);
                    if (day.compareTo(since) >= 0 && day.compareTo(today) <= 0 && (released == null || day.compareTo(released) < 0)) released = day;
                }
            }
            if (released == null) return null;
        }
        JSONObject wp = d.optJSONObject("watch/providers");
        JSONObject res = wp == null ? null : wp.optJSONObject("results");
        JSONObject in = res == null ? null : res.optJSONObject("IN");
        Service app = null;
        for (String kind : new String[]{"flatrate", "free", "ads"}) {
            JSONArray offers = in == null ? null : in.optJSONArray(kind);
            for (int k = 0; offers != null && k < offers.length() && app == null; k++) {
                app = providers.get(offers.optJSONObject(k).optInt("provider_id"));
            }
        }
        if (app == null) return null;
        String rating = rated(r) ? String.format(Locale.US, "★ %.1f", r.optDouble("vote_average", 0)) : "New";
        String kind = !tv ? "Movie" : "Season " + Math.max(1, season);
        String day = released;
        try {
            java.text.SimpleDateFormat ymd = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            day = new java.text.SimpleDateFormat("d MMM", Locale.UK).format(ymd.parse(released));
        } catch (java.text.ParseException ignored) {
        }
        // The card: the best rated wide picture with the title lettered on it, else the plain backdrop.
        String card = null;
        JSONObject images = d.optJSONObject("images");
        JSONArray wide = images == null ? null : images.optJSONArray("backdrops");
        for (int k = 0; wide != null && k < wide.length() && card == null; k++) {
            JSONObject w = wide.optJSONObject(k);
            if (w != null && !w.isNull("iso_639_1") && w.optString("file_path").startsWith("/")) card = w.optString("file_path");
        }
        return new Item(r.optString(tv ? "name" : "title"), rating + " · " + lang + " · " + app.name + " · " + kind + " · " + day,
                d.optString("overview", r.optString("overview")).trim(),
                "https://image.tmdb.org/t/p/w780" + (card != null ? card : r.optString("backdrop_path")), card != null,
                "https://image.tmdb.org/t/p/w1280" + r.optString("backdrop_path"), app.pkg, lang, released);
    }

    /** One title, whichever fetch it came from. */
    static String key(Item it) {
        return it.title + "|" + it.pkg;
    }

    /**
     * The languages of a page's six places, in order: each place goes to the language furthest below its
     * share so far (Telugu, English, Hindi, Telugu, English, Telugu).
     */
    static List<String> places() {
        List<String> out = new ArrayList<>();
        int[] taken = new int[LANGUAGES.length];
        for (int p = 0; p < COUNT; p++) {
            int pick = 0;
            for (int k = 1; k < LANGUAGES.length; k++) {
                if (taken[k] / Double.parseDouble(LANGUAGES[k][2]) < taken[pick] / Double.parseDouble(LANGUAGES[pick][2])) pick = k;
            }
            taken[pick]++;
            out.add(LANGUAGES[pick][1]);
        }
        return out;
    }

    /**
     * [pool] (newest first) dealt into at most [PAGES] pages of six, page 1 the newest: each place takes the
     * newest title left in its language ([places]), else, that language run out, the newest left of any;
     * at most three from one app in a page, shown newest first. A last page of fewer than three is left out.
     */
    /**
     * [pool] without the titles that came out more than two weeks before [now]: a kept pool ages (up to
     * four hours between fetches, days when offline), the window doesn't.
     */
    static List<Item> fresh(List<Item> pool, long now) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(Weather.ZONE);
        Calendar cal = Calendar.getInstance(Weather.ZONE);
        cal.setTimeInMillis(now);
        cal.add(Calendar.DAY_OF_MONTH, -14);
        String since = f.format(cal.getTime());
        List<Item> out = new ArrayList<>();
        for (Item it : pool) if (it.released.isEmpty() || it.released.compareTo(since) >= 0) out.add(it);
        return out;
    }

    static List<List<Item>> pages(List<Item> pool) {
        List<Item> left = new ArrayList<>(pool);
        List<List<Item>> out = new ArrayList<>();
        while (out.size() < PAGES && !left.isEmpty()) {
            List<Item> page = new ArrayList<>();
            Map<String, Integer> perApp = new HashMap<>();
            for (String lang : places()) {
                Item pick = null;
                for (int pass = 0; pass < 2 && pick == null; pass++) {
                    for (Item it : left) {
                        if (perApp.getOrDefault(it.pkg, 0) < 3 && (pass == 1 || it.lang.equals(lang))) {
                            pick = it;
                            break;
                        }
                    }
                }
                if (pick == null) break;
                left.remove(pick);
                perApp.put(pick.pkg, perApp.getOrDefault(pick.pkg, 0) + 1);
                page.add(pick);
            }
            if (page.isEmpty()) break;
            Collections.sort(page, NEWEST); // left to right, newest first
            out.add(page);
        }
        if (out.size() > 1 && out.get(out.size() - 1).size() < 3) out.remove(out.size() - 1);
        return out;
    }

    // ---- Where it all comes from and where it's kept (blocking: run off the main thread)

    static final class Source {
        private final File file, pictures;
        private final String token;

        Source(File cacheDir, String tmdbToken) {
            token = tmdbToken == null ? "" : tmdbToken.trim();
            file = new File(cacheDir, "home-shelf.json");
            pictures = new File(cacheDir, "home-img");
            // Day files from earlier shelves (TVmaze in v6.0, JustWatch in v7), and pages kept by an earlier one.
            for (String name : new String[]{"home-tvmaze", "home-new", "home-shelf-pages.json"}) {
                File old = new File(cacheDir, name);
                File[] files = old.listFiles();
                if (files != null) for (File d : files) d.delete();
                old.delete();
            }
        }

        List<Item> cached() {
            try {
                if (!file.isFile()) return null;
                JSONObject root = new JSONObject(Net.readText(file));
                if (!sourceName().equals(root.optString("source"))) return null;
                List<Item> out = fromJson(root.getJSONArray("items"));
                return out.isEmpty() ? null : out;
            } catch (IOException | JSONException e) {
                return null;
            }
        }

        private static List<Item> fromJson(JSONArray a) throws JSONException {
            List<Item> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String meta = o.getString("meta"), parts[] = meta.split(" · ");
                out.add(new Item(o.getString("title"), meta, o.optString("overview"), o.getString("image"), o.optBoolean("titled"),
                        o.getString("backdrop"), o.getString("package"), o.optString("lang", parts.length > 1 ? parts[1] : ""),
                        o.optString("released")));
            }
            return out;
        }

        private static JSONArray toJson(List<Item> items) throws JSONException {
            JSONArray a = new JSONArray();
            for (Item it : items) {
                a.put(new JSONObject().put("title", it.title).put("meta", it.meta).put("image", it.image)
                        .put("overview", it.overview).put("titled", it.titled).put("backdrop", it.backdrop).put("package", it.pkg).put("lang", it.lang).put("released", it.released));
            }
            return a;
        }

        boolean hasToken() {
            return !token.isEmpty();
        }

        private static String sourceName() {
            return "TMDB_CARDS3"; // 3: newest first, with the day each came out
        }

        long age(long now) {
            if (!file.isFile()) return Long.MAX_VALUE;
            long a = now - file.lastModified();
            return a < 0 ? Long.MAX_VALUE : a;
        }

        List<Item> fetch(long now, Set<String> installed) throws IOException {
            if (!hasToken()) throw new IOException("No TMDB token in the build");
            List<Item> items = Shelf.fetch(token, installed, now);
            if (items.size() < 3) throw new IOException("Only " + items.size() + " titles to show");
            try {
                Net.writeAtomically(file, new JSONObject().put("source", sourceName()).put("items", toJson(items)).toString());
            } catch (JSONException e) {
                throw new IOException(e);
            }
            return items;
        }

        /** The picture at [url] at [w] x [h] px: from memory, from disk, or (when [online]) downloaded and kept. */
        Bitmap picture(String url, int w, int h, boolean online) {
            String key = Net.keyFor(url, w, h);
            Bitmap b = Net.remembered(key);
            if (b != null) return b;
            pictures.mkdirs();
            File kept = new File(pictures, key + ".jpg");
            if (kept.isFile()) {
                b = Net.decodeFile(kept, w, h);
                if (b != null) {
                    Net.remember(key, b);
                    return b;
                }
                kept.delete();
            }
            if (!online) return null;
            File part = new File(pictures, key + "." + Thread.currentThread().getId() + ".part"); // one per download
            try {
                Net.download(url, part);
                b = Net.decodeFile(part, w, h);
                if (b == null) return null;
                Net.save(b, kept);
                Net.remember(key, b);
                return b;
            } catch (IOException e) {
                return null;
            } finally {
                part.delete();
            }
        }

        /** Puts the picture at [url], cut to [w] x [h] px, on disk if it isn't there yet. */
        void fetchPicture(String url, int w, int h) {
            String key = Net.keyFor(url, w, h);
            pictures.mkdirs();
            File kept = new File(pictures, key + ".jpg");
            if (kept.isFile()) return;
            File part = new File(pictures, key + "." + Thread.currentThread().getId() + ".part"); // one per download
            try {
                Net.download(url, part);
                Bitmap b = Net.decodeFile(part, w, h);
                if (b != null) Net.save(b, kept);
            } catch (IOException ignored) {
            } finally {
                part.delete();
            }
        }

        void prunePictures(Set<String> keys) {
            File[] all = pictures.listFiles();
            if (all == null) return;
            for (File p : all) {
                String name = p.getName();
                int dot = name.indexOf('.');
                if (!keys.contains(dot < 0 ? name : name.substring(0, dot))) p.delete();
            }
        }
    }

    // ---- The posters and the shelf

    /**
     * A wide card (16:9, radius 12): the title's picture, nothing else. A picture without the title
     * lettered on it gets the title in small type over a soft shade at its foot. The rest (rating, language,
     * app, story) shows once, for the focused card, in the [SpotlightView].
     */
    static final class PosterView extends ArtView {
        private final TextPaint titleP;
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), glyphP = new Paint(Paint.FILTER_BITMAP_FLAG),
                shadeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int glyphSize;
        private final Bitmap glyph;
        private String title = "";
        private CharSequence shownTitle = "";
        private boolean titled;
        private float titleBase;
        float artHeight;
        Item item;

        PosterView(Context c) {
            super(c);
            titleP = Ui.textPaint(c, Ui.semibold(c), Ui.T_LABEL, Ui.TEXT, 0f, false);
            titleP.setShadowLayer(Ui.dp(c, 3f), 0f, Ui.dp(c, 1f), 0x99000000);
            glyphSize = Ui.dpi(c, 22f);
            glyph = Ui.glyph(c, R.drawable.ph_film_slate, glyphSize);
        }

        void setItem(Item it) {
            item = it;
            title = it == null ? "" : it.title;
            titled = it != null && it.titled;
            setContentDescription(it == null ? "" : it.title + ", " + it.meta);
            fitText();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            Context c = getContext();
            placeArt(ringSpace, ringSpace, w - 2 * ringSpace, artHeight, Ui.dp(c, R_CARD));
            titleBase = art.bottom - Ui.dp(c, Ui.S2) - titleP.descent();
            shadeP.setShader(new android.graphics.LinearGradient(0, art.bottom - art.height() * 0.6f, 0, art.bottom,
                    0x00000000, 0xB3000000, android.graphics.Shader.TileMode.CLAMP));
            fitText();
        }

        private void fitText() {
            float w = art.width() - 2 * Ui.dp(getContext(), Ui.S2 + 2f);
            if (w <= 0) return;
            shownTitle = TextUtils.ellipsize(title, titleP, w, TextUtils.TruncateAt.END);
        }

        @Override
        protected void drawEmptyArt(Canvas canvas, float focused) {
            fillP.setColor(Ui.mix(Ui.FILL, Ui.POSTER_FOCUS, focused));
            canvas.drawRoundRect(art, radius, radius, fillP);
            if (title.isEmpty()) {
                glyphP.setColor(Ui.mix(Ui.TEXT_2, Ui.TEXT, focused));
                glyphP.setAlpha(Math.round(255 * 0.5f));
                canvas.drawBitmap(glyph, art.centerX() - glyphSize / 2f, art.centerY() - glyphSize / 2f, glyphP);
            } else {
                drawTitle(canvas);
            }
        }

        @Override
        protected void drawOverArt(Canvas canvas, float focused) {
            if (!hasArt() || titled || shownTitle.length() == 0) return;
            canvas.drawRoundRect(art, radius, radius, shadeP);
            drawTitle(canvas);
        }

        private void drawTitle(Canvas canvas) {
            canvas.drawText(shownTitle, 0, shownTitle.length(), art.left + Ui.dp(getContext(), Ui.S2 + 2f), titleBase, titleP);
        }
    }

    static final float R_CARD = 12f;

    /**
     * The focused card's story, once, at the left under the clock: its title (24), then "★ 8.8 · English ·
     * JioHotstar · Season 1" (13), then two lines of what it's about (13). It fades to each new title.
     */
    static final class SpotlightView extends View {
        private final TextPaint titleP, metaP, aboutP;
        private Item item;
        private CharSequence shownTitle = "";
        private android.text.StaticLayout about;
        private final Ui.Tween in = new Ui.Tween(0f);

        SpotlightView(Context c) {
            super(c);
            titleP = Ui.textPaint(c, Ui.semibold(c), 24f, Ui.TEXT, -0.012f, false);
            metaP = Ui.textPaint(c, Ui.medium(c), Ui.T_LABEL, Ui.TEXT_2, 0f, false);
            aboutP = Ui.textPaint(c, Ui.regular(c), Ui.T_LABEL, Ui.TEXT_2, 0f, false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void show(Item it) {
            if (it == item) return;
            item = it;
            layoutText();
            in.snap(0f);
            in.to(1f, 360);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            layoutText();
        }

        private void layoutText() {
            int w = getWidth();
            if (w <= 0 || item == null) {
                about = null;
                shownTitle = "";
                return;
            }
            shownTitle = TextUtils.ellipsize(item.title, titleP, w, TextUtils.TruncateAt.END);
            about = item.overview.isEmpty() ? null : android.text.StaticLayout.Builder
                    .obtain(item.overview, 0, item.overview.length(), aboutP, w)
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(Ui.dp(getContext(), 3f), 1f).build();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean moving = in.step();
            if (item != null) {
                Context c = getContext();
                int a = Math.round(255 * in.value);
                float y = Ui.baselineIn(titleP, 0, Ui.dp(c, 30f));
                titleP.setAlpha(a);
                canvas.drawText(shownTitle, 0, shownTitle.length(), 0, y, titleP);
                y = Ui.baselineIn(metaP, Ui.dp(c, 34f), Ui.dp(c, 17f));
                metaP.setAlpha(a);
                canvas.drawText(item.meta, 0, y, metaP);
                if (about != null) {
                    aboutP.setAlpha(Math.round(a * 0.8f));
                    int save = canvas.save();
                    canvas.translate(0, Ui.dp(c, 60f));
                    about.draw(canvas);
                    canvas.restoreToCount(save);
                }
            }
            if (moving) postInvalidateOnAnimation();
        }
    }

    /**
     * A small round button by the shelf's head: a glyph on a faint disc. Focus is the disc brightening and
     * the fine ring, as everywhere; a button with nothing to do dims its glyph. [spin] turns the glyph
     * while the shelf is being refreshed.
     */
    static final class ShelfButton extends ArtView {
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), glyphP = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap glyph;
        private final int glyphSize;
        private final Ui.Tween live = new Ui.Tween(1f);
        private boolean spinning;
        private long spinStart;

        ShelfButton(Context c, int glyphRes, String label) {
            super(c);
            glyphSize = Ui.dpi(c, 12f);
            setGlyph(glyphRes, label);
        }

        void setGlyph(int glyphRes, String label) {
            glyph = Ui.glyph(getContext(), glyphRes, glyphSize);
            setContentDescription(label);
            invalidate();
        }

        static float diameter(Context c) {
            return Ui.dp(c, 22f);
        }

        void setLive(boolean on) {
            live.to(on ? 1f : 0f, 200);
            invalidate();
        }

        void spin(boolean on) {
            if (on == spinning) return;
            spinning = on;
            spinStart = Ui.now();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float d = w - 2 * ringSpace;
            placeArt(ringSpace, ringSpace, d, d, d / 2f);
        }

        @Override
        protected void drawEmptyArt(Canvas canvas, float focused) {
            boolean moving = live.step();
            fillP.setColor(Ui.mix(Ui.BAND, 0x33FFFFFF, focused));
            canvas.drawRoundRect(art, radius, radius, fillP);
            glyphP.setColor(Ui.mix(Ui.TEXT_2, Ui.TEXT, focused));
            glyphP.setAlpha(Math.round(255 * (0.3f + 0.7f * live.value)));
            int save = canvas.save();
            if (spinning) {
                canvas.rotate((Ui.now() - spinStart) % 900 / 900f * 360f, art.centerX(), art.centerY());
                moving = true;
            }
            canvas.drawBitmap(glyph, art.centerX() - glyphSize / 2f, art.centerY() - glyphSize / 2f, glyphP);
            canvas.restoreToCount(save);
            if (moving) postInvalidateOnAnimation();
        }
    }

    /**
     * The shelf: its head (left) and the data's credit (right) over six posters in equal columns, 16 apart,
     * their art one height so the titles share a line. It reaches a ring's width past its slot so focus
     * rings stay inside it. It starts folded: only the head and its + button, on the line just over the
     * dock; + opens it out and − folds it again.
     */
    static final class ShelfView extends ViewGroup {
        final List<PosterView> posters = new ArrayList<>();
        /** After the head: open or fold the shelf, the previous page, the next page, and fresh titles. */
        final ShelfButton toggle, prev, next, refresh;
        private final List<ShelfButton> buttons;
        private int lastButton = 2;
        private boolean collapsed = true;
        private int pages;
        final int overhang;
        private final TextPaint headP, creditP, pageP;
        private String head = "", credit = "", page = "", note = "";
        private float creditWidth, headBase, pageLeft, noteLeft;
        private final Ui.Tween noteIn = new Ui.Tween(0f);
        /** Where Down goes from a poster, and Up. */
        java.util.function.Supplier<View> below, above;
        private int lastFocused;

        ShelfView(Context c) {
            super(c);
            for (int i = 0; i < COUNT; i++) {
                PosterView p = new PosterView(c);
                posters.add(p);
                addView(p);
            }
            toggle = new ShelfButton(c, R.drawable.ph_plus_bold, "Show titles");
            prev = new ShelfButton(c, R.drawable.ph_caret_left_bold, "Previous page");
            next = new ShelfButton(c, R.drawable.ph_caret_right_bold, "Next page");
            refresh = new ShelfButton(c, R.drawable.ph_arrow_clockwise_bold, "Fresh titles");
            buttons = java.util.Arrays.asList(toggle, prev, next, refresh);
            for (ShelfButton b : buttons) {
                addView(b);
                b.setVisibility(b == toggle ? VISIBLE : INVISIBLE);
            }
            overhang = Math.round(posters.get(0).ringSpace);
            headP = Ui.textPaint(c, Ui.semibold(c), Ui.T_LABEL, Ui.TEXT_2, 0f, false);
            pageP = Ui.textPaint(c, Ui.medium(c), Ui.T_CAPTION, Ui.TEXT_2, 0f, true);
            creditP = Ui.textPaint(c, Ui.medium(c), Ui.T_CAPTION, Ui.TEXT_2, 0.012f, false);
            creditP.setAlpha(Math.round(255 * 0.7f));
            setWillNotDraw(false);
            setClipChildren(false); // the buttons' rings reach a little above the shelf
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setHead(String head, String credit) {
            this.head = head;
            this.credit = credit;
            creditWidth = creditP.measureText(credit);
            requestLayout();
            invalidate();
        }

        /** Shows the buttons with "[at] / [of]" between the arrows (of 0: no pages yet, only + or −). */
        void setPage(int at, int of) {
            pages = of;
            showButtons();
            page = of > 0 ? (at + 1) + " / " + of : "";
            prev.setLive(at > 0);
            next.setLive(at + 1 < of);
            invalidate();
        }

        boolean collapsed() {
            return collapsed;
        }

        /** Folds the shelf to its head line (the posters are hidden by whoever fills them), or opens it. */
        void setCollapsed(boolean c) {
            collapsed = c;
            toggle.setGlyph(c ? R.drawable.ph_plus_bold : R.drawable.ph_minus_bold, c ? "Show titles" : "Hide titles");
            showButtons();
            requestLayout();
            invalidate();
        }

        private void showButtons() {
            boolean open = !collapsed && pages > 0;
            for (ShelfButton b : buttons) if (b != toggle) b.setVisibility(open ? VISIBLE : INVISIBLE);
            View f = findFocus();
            if (f != null && f.getVisibility() != VISIBLE) entry().requestFocus();
        }

        /** Where focus lands coming from above or below: the + button while folded, else the last poster. */
        View entry() {
            return collapsed ? toggle : lastFocused();
        }

        /** A word after the buttons ("Up to date") that fades away after a few seconds. */
        void note(String text) {
            note = text;
            noteIn.snap(1f);
            invalidate();
            removeCallbacks(fadeNote);
            postDelayed(fadeNote, 3500);
        }

        private final Runnable fadeNote = () -> {
            noteIn.to(0f, 600);
            invalidate();
        };

        View lastButton() {
            return buttons.get(lastButton).getVisibility() == VISIBLE ? buttons.get(lastButton) : null;
        }

        PosterView lastFocused() {
            PosterView p = posters.get(lastFocused);
            return p.getVisibility() == VISIBLE ? p : posters.get(0);
        }

        static final class Geometry {
            final float[] lefts = new float[COUNT], widths = new float[COUNT];
            float artTop, artHeight;
        }

        /** Six equal columns, gaps of 16; the art takes the height the head and titles leave. */
        static Geometry geometry(Context c, float slotW, float slotH) {
            Geometry g = new Geometry();
            float gap = Ui.dp(c, Ui.S4);
            float w = (slotW - (COUNT - 1) * gap) / COUNT;
            for (int i = 0; i < COUNT; i++) {
                g.lefts[i] = i * (w + gap);
                g.widths[i] = w;
            }
            g.artTop = Ui.dp(c, Ui.T_LABEL * 1.3f + Ui.S3);
            g.artHeight = slotH - g.artTop;
            return g;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
            Geometry g = geometry(getContext(), getMeasuredWidth() - 2f * overhang, getMeasuredHeight() - 2f * overhang);
            for (int i = 0; i < COUNT; i++) {
                PosterView p = posters.get(i);
                p.artHeight = g.artHeight;
                p.measure(MeasureSpec.makeMeasureSpec(Math.round(g.widths[i] + 2 * overhang), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(2 * overhang + g.artHeight), MeasureSpec.EXACTLY));
            }
            int size = MeasureSpec.makeMeasureSpec(Math.round(ShelfButton.diameter(getContext()) + 2 * overhang), MeasureSpec.EXACTLY);
            for (ShelfButton b : buttons) b.measure(size, size);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            Geometry g = geometry(getContext(), getWidth() - 2f * overhang, getHeight() - 2f * overhang);
            for (int i = 0; i < COUNT; i++) {
                PosterView p = posters.get(i);
                int x = Math.round(g.lefts[i]), y = Math.round(g.artTop);
                p.layout(x, y, x + p.getMeasuredWidth(), y + p.getMeasuredHeight());
            }
            Context c = getContext();
            float line = Ui.dp(c, Ui.T_LABEL * 1.3f);
            // Folded, the head line drops to the slot's foot, just over the dock.
            float lineTop = collapsed ? getHeight() - overhang - line : overhang;
            headBase = Ui.baselineIn(headP, lineTop, line);
            // In the head's line, after it: [+]  [<] 1 / 3 [>]  [refresh]  note
            int size = Math.round(ShelfButton.diameter(c) + 2 * overhang);
            float mid = lineTop + line / 2f, x = overhang + headP.measureText(head) + Ui.dp(c, Ui.S3);
            int top = Math.round(mid - size / 2f);
            float pageW = pageP.measureText("88 / 88"), p = x + size + Ui.dp(c, Ui.S2);
            float[] lefts = {x, p, p + size + Ui.dp(c, Ui.S1) + pageW, p + 2 * size + Ui.dp(c, Ui.S1 + Ui.S2) + pageW};
            pageLeft = p + size - overhang + Ui.dp(c, Ui.S1) / 2f;
            noteLeft = lefts[3] + size + Ui.dp(c, Ui.S1);
            for (int i = 0; i < buttons.size(); i++) {
                int left = Math.round(lefts[i] - overhang);
                buttons.get(i).layout(left, top, left + size, top + size);
            }
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            int i = posters.indexOf(child);
            if (i >= 0) lastFocused = i;
            int b = buttons.indexOf(child);
            if (b >= 0) lastButton = b;
        }

        @Override
        public View focusSearch(View focused, int direction) {
            int i = posters.indexOf(focused);
            if (i >= 0) {
                switch (direction) {
                    case FOCUS_LEFT:
                        return i > 0 ? posters.get(i - 1) : focused;
                    case FOCUS_RIGHT:
                        return i + 1 < COUNT && posters.get(i + 1).getVisibility() == VISIBLE ? posters.get(i + 1) : focused;
                    case FOCUS_UP:
                        if (lastButton() != null) return lastButton();
                        return above != null && above.get() != null ? above.get() : focused;
                    case FOCUS_DOWN:
                        return below != null && below.get() != null ? below.get() : focused;
                }
            }
            int b = buttons.indexOf(focused);
            if (b >= 0) {
                switch (direction) {
                    case FOCUS_LEFT:
                        return b > 0 && buttons.get(b - 1).getVisibility() == VISIBLE ? buttons.get(b - 1) : focused;
                    case FOCUS_RIGHT:
                        return b + 1 < buttons.size() && buttons.get(b + 1).getVisibility() == VISIBLE ? buttons.get(b + 1) : focused;
                    case FOCUS_UP:
                        return above != null && above.get() != null ? above.get() : focused;
                    case FOCUS_DOWN:
                        if (!collapsed) return lastFocused();
                        return below != null && below.get() != null ? below.get() : focused;
                }
            }
            return super.focusSearch(focused, direction);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawText(head, overhang, headBase, headP);
            if (collapsed) return; // the head and + only
            if (!credit.isEmpty()) canvas.drawText(credit, getWidth() - overhang - creditWidth, headBase, creditP);
            if (!page.isEmpty()) {
                float w = pageP.measureText(page), room = pageP.measureText("88 / 88");
                canvas.drawText(page, pageLeft + (room - w) / 2f, headBase, pageP);
            }
            boolean moving = noteIn.step();
            if (noteIn.value > 0f && !note.isEmpty()) {
                pageP.setAlpha(Math.round(255 * noteIn.value));
                canvas.drawText(note, noteLeft, headBase, pageP);
                pageP.setAlpha(255);
            }
            if (moving) postInvalidateOnAnimation();
        }
    }
}
