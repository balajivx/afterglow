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


/**
 * "New and top rated on your apps", from TMDB only: movies and shows released in the last two weeks
 * (a movie in cinemas or on streaming in India; a show's series or its current season), streaming in India
 * on the apps this TV has. No animation (anime, cartoons) and no kids' shows. English and Telugu come
 * first, taken in turn, Hindi fills what's left. Within a language: rated titles (5+ votes) best rated
 * first, anything rated below 6.0 left out; then titles too new to be rated, most popular first. Six
 * posters, at most three from one app.
 *
 * The token comes from the build (launcher/tmdb.properties). TMDB's streaming availability is supplied by
 * JustWatch, and TMDB's terms ask that JustWatch be credited for it; nothing here talks to JustWatch.
 */
final class Shelf {
    private Shelf() {
    }

    static final int COUNT = 6;

    static final class Item {
        final String title, meta, image, backdrop, pkg;

        Item(String title, String meta, String image, String backdrop, String pkg) {
            this.title = title;
            this.meta = meta;
            this.image = image;
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
            new Service("aha", "ahaflix.tv", 532));

    // ---- Languages, in the order this home watches them

    /** TMDB language code, the name shown, the tier (0 first, taken in turn; 1 fills what's left). */
    private static final String[][] LANGUAGES = {
            {"en", "English", "0"},
            {"te", "Telugu", "0"},
            {"hi", "Hindi", "1"},
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

    static List<Item> fetch(String token, Set<String> installed, long now) throws IOException {
        Map<Integer, Service> providers = new HashMap<>();
        for (Service s : SERVICES) if (installed.contains(s.pkg)) providers.put(s.tmdbId, s);
        if (providers.isEmpty()) return new ArrayList<>();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(Weather.ZONE);
        Calendar cal = Calendar.getInstance(Weather.ZONE);
        cal.setTimeInMillis(now);
        String today = f.format(cal.getTime());
        cal.add(Calendar.DAY_OF_MONTH, -14);
        String since = f.format(cal.getTime());
        String ids = TextUtils.join("|", providers.keySet());

        // English and Telugu in turn, then Hindi.
        List<List<JSONObject>> first = new ArrayList<>(), then = new ArrayList<>();
        List<String> firstNames = new ArrayList<>(), thenNames = new ArrayList<>();
        for (String[] l : LANGUAGES) {
            List<JSONObject> c = candidates(token, l[0], ids, since, today);
            (l[2].equals("0") ? first : then).add(c);
            (l[2].equals("0") ? firstNames : thenNames).add(l[1]);
        }
        List<JSONObject> order = new ArrayList<>();
        List<String> langOf = new ArrayList<>();
        for (int i = 0; ; i++) {
            boolean any = false;
            for (int k = 0; k < first.size(); k++) {
                if (i < first.get(k).size()) {
                    order.add(first.get(k).get(i));
                    langOf.add(firstNames.get(k));
                    any = true;
                }
            }
            if (!any) break;
        }
        for (int k = 0; k < then.size(); k++) {
            for (JSONObject o : then.get(k)) {
                order.add(o);
                langOf.add(thenNames.get(k));
            }
        }

        // Check each in that order (one call: details with where it streams) until six; three per app at most.
        List<Item> out = new ArrayList<>();
        Map<Service, Integer> perApp = new HashMap<>();
        int asked = 0;
        for (int i = 0; i < order.size() && out.size() < COUNT && asked < 40; i++) {
            JSONObject r = order.get(i);
            String type = r.optString("_type");
            boolean tv = type.equals("tv");
            asked++;
            JSONObject d = tmdb(token, "/" + type + "/" + r.optInt("id"), Collections.singletonMap("append_to_response", "watch/providers"));
            int season = 0;
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
                if (began == null || began.isEmpty() || began.compareTo(since) < 0) continue;
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
            if (app == null) continue;
            int n = perApp.containsKey(app) ? perApp.get(app) : 0;
            if (n >= 3) continue;
            perApp.put(app, n + 1);
            String rating = rated(r) ? String.format(Locale.US, "★ %.1f", r.optDouble("vote_average", 0)) : "New";
            String kind = !tv ? "Movie" : "S" + Math.max(1, season);
            out.add(new Item(r.optString(tv ? "name" : "title"), rating + " · " + langOf.get(i) + "\n" + app.name + " · " + kind,
                    "https://image.tmdb.org/t/p/w342" + r.optString("poster_path"),
                    "https://image.tmdb.org/t/p/w1280" + r.optString("backdrop_path"), app.pkg));
        }
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
            // Day files from earlier shelves (TVmaze in v6.0, JustWatch in v7).
            for (String name : new String[]{"home-tvmaze", "home-new"}) {
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
                JSONArray a = root.getJSONArray("items");
                List<Item> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new Item(o.getString("title"), o.getString("meta"), o.getString("image"),
                            o.getString("backdrop"), o.getString("package")));
                }
                return out.isEmpty() ? null : out;
            } catch (IOException | JSONException e) {
                return null;
            }
        }

        boolean hasToken() {
            return !token.isEmpty();
        }

        private static String sourceName() {
            return "TMDB_FRESH2";
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
            JSONArray a = new JSONArray();
            try {
                for (Item it : items) {
                    a.put(new JSONObject().put("title", it.title).put("meta", it.meta).put("image", it.image)
                            .put("backdrop", it.backdrop).put("package", it.pkg));
                }
                Net.writeAtomically(file, new JSONObject().put("source", sourceName()).put("items", a).toString());
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
            File part = new File(pictures, key + ".part");
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

    /** A poster: the art (radius 16), its title (15, brightening with focus) and the details (13). */
    static final class PosterView extends ArtView {
        private final TextPaint titleP, metaP;
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), glyphP = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final int glyphSize;
        private final Bitmap glyph;
        private String title = "", meta = "", app = "";
        private CharSequence shownTitle = "", shownMeta = "", shownApp = "";
        private float titleBase, metaBase, appBase;
        float artHeight;

        PosterView(Context c) {
            super(c);
            titleP = Ui.textPaint(c, Ui.medium(c), Ui.T_BODY, Ui.TEXT_2, -0.006f, false);
            metaP = Ui.textPaint(c, Ui.medium(c), Ui.T_LABEL, Ui.TEXT_2, 0f, false);
            glyphSize = Ui.dpi(c, 36f);
            glyph = Ui.glyph(c, R.drawable.ph_film_slate, glyphSize);
        }

        /** Below the art: the title, then the details ("★ 7.6 · Telugu"), then the app on its own line. */
        static float textHeight(Context c) {
            return Ui.dp(c, Ui.S3 + Ui.T_BODY * 1.3f + 2f + 2 * Ui.T_LABEL * 1.3f);
        }

        /** [meta] may hold a second line after a newline: the app. */
        void setText(String title, String meta) {
            int nl = meta.indexOf('\n');
            this.title = title;
            this.meta = nl < 0 ? meta : meta.substring(0, nl);
            this.app = nl < 0 ? "" : meta.substring(nl + 1);
            setContentDescription(meta.isEmpty() ? title : title + ", " + meta.replace('\n', ' '));
            fitText();
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            Context c = getContext();
            placeArt(ringSpace, ringSpace, w - 2 * ringSpace, artHeight, Ui.dp(c, Ui.R_POSTER));
            float titleTop = art.bottom + Ui.dp(c, Ui.S3);
            titleBase = Ui.baselineIn(titleP, titleTop, Ui.dp(c, Ui.T_BODY * 1.3f));
            metaBase = Ui.baselineIn(metaP, titleTop + Ui.dp(c, Ui.T_BODY * 1.3f + 2f), Ui.dp(c, Ui.T_LABEL * 1.3f));
            appBase = metaBase + Ui.dp(c, Ui.T_LABEL * 1.3f);
            fitText();
        }

        private void fitText() {
            float w = art.width();
            if (w <= 0) return;
            shownTitle = TextUtils.ellipsize(title, titleP, w, TextUtils.TruncateAt.END);
            shownMeta = TextUtils.ellipsize(meta, metaP, w, TextUtils.TruncateAt.END);
            shownApp = TextUtils.ellipsize(app, metaP, w, TextUtils.TruncateAt.END);
        }

        @Override
        protected void drawEmptyArt(Canvas canvas, float focused) {
            fillP.setColor(Ui.mix(Ui.FILL, Ui.POSTER_FOCUS, focused));
            canvas.drawRoundRect(art, radius, radius, fillP);
            glyphP.setColor(Ui.mix(Ui.TEXT_2, Ui.TEXT, focused));
            glyphP.setAlpha(Math.round(255 * 0.6f));
            canvas.drawBitmap(glyph, art.centerX() - glyphSize / 2f, art.centerY() - glyphSize / 2f, glyphP);
        }

        @Override
        protected void drawUnderArt(Canvas canvas, float focused) {
            titleP.setColor(Ui.mix(Ui.TEXT_2, Ui.TEXT, focused));
            canvas.drawText(shownTitle, 0, shownTitle.length(), art.left, titleBase, titleP);
            if (shownMeta.length() > 0) {
                metaP.setColor(Ui.withAlpha(Ui.TEXT_2, 0.75f + 0.25f * focused));
                canvas.drawText(shownMeta, 0, shownMeta.length(), art.left, metaBase, metaP);
            }
            if (shownApp.length() > 0) canvas.drawText(shownApp, 0, shownApp.length(), art.left, appBase, metaP);
        }
    }

    /**
     * The shelf: its head (left) and the data's credit (right) over six posters in equal columns, 16 apart,
     * their art one height so the titles share a line. It reaches a ring's width past its slot so focus
     * rings stay inside it.
     */
    static final class ShelfView extends ViewGroup {
        final List<PosterView> posters = new ArrayList<>();
        final int overhang;
        private final TextPaint headP, creditP;
        private String head = "", credit = "";
        private float creditWidth, headBase;
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
            overhang = Math.round(posters.get(0).ringSpace);
            headP = Ui.textPaint(c, Ui.semibold(c), Ui.T_LABEL, Ui.TEXT_2, 0f, false);
            creditP = Ui.textPaint(c, Ui.medium(c), Ui.T_CAPTION, Ui.TEXT_2, 0.012f, false);
            creditP.setAlpha(Math.round(255 * 0.7f));
            setWillNotDraw(false);
            setClipChildren(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setHead(String head, String credit) {
            this.head = head;
            this.credit = credit;
            creditWidth = creditP.measureText(credit);
            invalidate();
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
            g.artHeight = slotH - g.artTop - PosterView.textHeight(c);
            return g;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec));
            Geometry g = geometry(getContext(), getMeasuredWidth() - 2f * overhang, getMeasuredHeight() - overhang);
            for (int i = 0; i < COUNT; i++) {
                PosterView p = posters.get(i);
                p.artHeight = g.artHeight;
                p.measure(MeasureSpec.makeMeasureSpec(Math.round(g.widths[i] + 2 * overhang), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(overhang + g.artHeight + PosterView.textHeight(getContext())), MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            Geometry g = geometry(getContext(), getWidth() - 2f * overhang, getHeight() - overhang);
            for (int i = 0; i < COUNT; i++) {
                PosterView p = posters.get(i);
                int x = Math.round(g.lefts[i]), y = Math.round(g.artTop);
                p.layout(x, y, x + p.getMeasuredWidth(), y + p.getMeasuredHeight());
            }
            headBase = Ui.baselineIn(headP, overhang, Ui.dp(getContext(), Ui.T_LABEL * 1.3f));
        }

        @Override
        public void requestChildFocus(View child, View focused) {
            super.requestChildFocus(child, focused);
            int i = posters.indexOf(child);
            if (i >= 0) lastFocused = i;
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
                        return above != null && above.get() != null ? above.get() : focused;
                    case FOCUS_DOWN:
                        return below != null && below.get() != null ? below.get() : focused;
                }
            }
            return super.focusSearch(focused, direction);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawText(head, overhang, headBase, headP);
            if (!credit.isEmpty()) canvas.drawText(credit, getWidth() - overhang - creditWidth, headBase, creditP);
        }
    }
}
