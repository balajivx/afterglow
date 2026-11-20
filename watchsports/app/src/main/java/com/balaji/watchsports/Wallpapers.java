package com.balaji.watchsports;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The face's photos, new every month: 24 (8 each for the Arsenal, Ferrari and space days), picked and prepared by
 * watchsports/wallpapers/make_month.py in the afterglow repo (a monthly GitHub Action) and downloaded here once a
 * month, about 1 MB in all. Until a month's set is here, last month's stays; with none, the face shows its own.
 * Which photo a day gets is the face's own rule: theme = day of year % 3, design = (day of year % 24) / 3, and
 * photo n of a theme was prepared for design n - 1 (its date ink).
 */
final class Wallpapers {
    static final String BASE = "https://raw.githubusercontent.com/balajivx/afterglow/main/watchsports/wallpapers/";
    static final String[] THEMES = {"arsenal", "ferrari", "space"};
    private static final long RETRY = 6 * 3600_000L;

    private Wallpapers() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "walls");
    }

    /** "ferrari-3.jpg": the photo for this day (Calendar's DAY_OF_YEAR, as the face's [DAY_OF_YEAR]). */
    static String nameFor(int dayOfYear) {
        int k = dayOfYear % 24;
        return THEMES[k % 3] + "-" + (k / 3 + 1) + ".jpg";
    }

    /** This day's photo, as JPEG bytes, or null when there's no downloaded set yet. */
    static byte[] photo(Context c, Calendar day) {
        File f = new File(dir(c), nameFor(day.get(Calendar.DAY_OF_YEAR)));
        try {
            return f.exists() ? Files.readAllBytes(f.toPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** The month of the set on the watch ("2026-11"), or "" for none. */
    static String have(Context c) {
        return c.getSharedPreferences("walls", Context.MODE_PRIVATE).getString("month", "");
    }

    /**
     * Fetches the published set when it's newer than ours: at most once every 6 hours until this month's arrives
     * (then not again until next month). Blocking; off the main thread. True when a new set arrived.
     */
    static boolean refresh(Context c, long now) {
        SharedPreferences p = c.getSharedPreferences("walls", Context.MODE_PRIVATE);
        String thisMonth = new SimpleDateFormat("yyyy-MM", Locale.UK).format(new Date(now));
        String have = p.getString("month", "");
        if (have.compareTo(thisMonth) >= 0) return false;
        if (now - p.getLong("tried", 0) < RETRY) return false;
        p.edit().putLong("tried", now).apply();
        try {
            JSONObject m = new JSONObject(new String(get(BASE + "current.json"), "UTF-8"));
            String month = m.getString("month");
            if (month.compareTo(have) <= 0) return false; // not published yet: try again later
            JSONArray walls = m.getJSONArray("walls");
            File fresh = new File(c.getFilesDir(), "walls-new");
            deleteAll(fresh);
            if (!fresh.mkdirs()) return false;
            int got = 0;
            for (int i = 0; i < walls.length(); i++) {
                JSONObject w = walls.getJSONObject(i);
                String theme = w.getString("theme");
                int n = w.getInt("n");
                String file = w.getString("file");
                if (!java.util.Arrays.asList(THEMES).contains(theme) || n < 1 || n > 8 || !file.matches("[0-9-]+/[a-z]+-[1-8]\\.jpg")) continue;
                byte[] jpg = get(BASE + file);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
                if (o.outWidth != 450 || o.outHeight != 450) continue; // only what the face can show as it is
                try (FileOutputStream out = new FileOutputStream(new File(fresh, theme + "-" + n + ".jpg"))) {
                    out.write(jpg);
                }
                got++;
            }
            if (got < 24) {
                deleteAll(fresh);
                return false;
            }
            File live = dir(c), old = new File(c.getFilesDir(), "walls-old");
            deleteAll(old);
            if (live.exists() && !live.renameTo(old)) return false;
            if (!fresh.renameTo(live)) {
                old.renameTo(live);
                return false;
            }
            deleteAll(old);
            p.edit().putString("month", month).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "WatchSports/1 (Wear OS)");
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int k; (k = in.read(buf)) > 0; ) {
                    out.write(buf, 0, k);
                    if (out.size() > 2_000_000) throw new java.io.IOException("too big");
                }
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
