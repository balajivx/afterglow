package com.balaji.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Open-Meteo's forecast and the words and glyphs for it. The hourly series
 * is kept, so after a reboot the cached forecast still shows the right hour, and "what's next" moves on
 * with the clock between refreshes.
 */
final class Weather {
    static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Kolkata");
    static final long EVERY_MS = 30 * 60 * 1000L;

    static final class Hour {
        final String label, text, icon;
        final int temp;

        Hour(String label, int temp, String text, String icon) {
            this.label = label;
            this.temp = temp;
            this.text = text;
            this.icon = icon;
        }
    }

    static final class Reading {
        final int temp;
        final String text, icon;
        final List<Hour> hours;

        Reading(int temp, String text, String icon, List<Hour> hours) {
            this.temp = temp;
            this.text = text;
            this.icon = icon;
            this.hours = hours;
        }
    }

    /** What the weather does next: a glyph and words; strong ones are white ("Drizzle from 3 pm"). */
    static final class Next {
        final String icon;
        final String[] words;
        final boolean[] strong;

        Next(String icon, String[] words, boolean[] strong) {
            this.icon = icon;
            this.words = words;
            this.strong = strong;
        }
    }

    enum Sky {NONE, SUN, PARTLY, CLOUD, RAIN, STORM, NIGHT}

    // ---- The forecast

    private final String currentHour;
    private final double currentTemp;
    private final int currentCode;
    private final boolean currentDay;
    private final String[] times;
    private final double[] temps;
    private final int[] codes;
    private final boolean[] days;

    private Weather(JSONObject root) throws JSONException {
        JSONObject current = root.getJSONObject("current");
        JSONObject hourly = root.getJSONObject("hourly");
        currentHour = current.getString("time").substring(0, 13);
        currentTemp = current.getDouble("temperature_2m");
        currentCode = current.getInt("weather_code");
        currentDay = current.getInt("is_day") == 1;
        JSONArray t = hourly.getJSONArray("time"), tp = hourly.getJSONArray("temperature_2m"),
                c = hourly.getJSONArray("weather_code"), d = hourly.getJSONArray("is_day");
        int n = t.length();
        times = new String[n];
        temps = new double[n];
        codes = new int[n];
        days = new boolean[n];
        for (int i = 0; i < n; i++) {
            times[i] = t.getString(i);
            temps[i] = tp.optDouble(i, 0);
            codes[i] = c.optInt(i, -1);
            days[i] = d.optInt(i, 1) == 1;
        }
    }

    static Weather parse(String json) throws IOException {
        try {
            return new Weather(new JSONObject(json));
        } catch (JSONException | RuntimeException e) {
            throw new IOException("Open-Meteo sent something unexpected", e);
        }
    }

    /** The reading for [hour] ("2026-10-20T17", local time), or null if the forecast doesn't reach it. */
    Reading readingAt(String hour) {
        int at = -1;
        for (int i = 0; i < times.length; i++) {
            if (times[i].startsWith(hour)) {
                at = i;
                break;
            }
        }
        if (at < 0) return null;
        boolean live = hour.equals(currentHour);
        String[] now = describe(live ? currentCode : codes[at], live ? currentDay : days[at]);
        List<Hour> next = new ArrayList<>();
        for (int k = at + 1; k < Math.min(at + 9, times.length); k++) {
            String[] d = describe(codes[k], days[k]);
            next.add(new Hour(hourLabel(times[k]), round(temps[k]), d[0], d[1]));
        }
        return new Reading(round(live ? currentTemp : temps[at]), now[0], now[1], next);
    }

    static String url(String lat, String lon) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day&hourly=temperature_2m,weather_code,is_day"
                + "&forecast_days=2&timezone=Asia%2FKolkata";
    }

    static int round(double x) {
        return (int) Math.floor(x + 0.5);
    }

    static String hourKey(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.US);
        f.setTimeZone(ZONE);
        return f.format(millis);
    }

    static String hourLabel(String iso) {
        int h = Integer.parseInt(iso.substring(11, 13));
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " am" : " pm");
    }

    /** WMO weather codes to {words, glyph name}. */
    static String[] describe(int code, boolean isDay) {
        if (code == 0) return new String[]{"Clear", isDay ? "sun" : "moon"};
        if (code == 1) return new String[]{"Mostly clear", isDay ? "sun" : "moon"};
        if (code == 2) return new String[]{"Partly cloudy", isDay ? "cloud-sun" : "cloud-moon"};
        if (code == 3) return new String[]{"Cloudy", "cloud"};
        if (code == 45 || code == 48) return new String[]{"Foggy", "cloud-fog"};
        if (code >= 51 && code <= 57) return new String[]{"Drizzle", "cloud-rain"};
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return new String[]{"Snow", "cloud-snow"};
        if (code >= 95) return new String[]{"Thunderstorms", "cloud-lightning"};
        switch (code) {
            case 61: return new String[]{"Light rain", "cloud-rain"};
            case 63: return new String[]{"Rain", "cloud-rain"};
            case 65: return new String[]{"Heavy rain", "cloud-rain"};
            case 66: case 67: return new String[]{"Freezing rain", "cloud-rain"};
            case 80: return new String[]{"Light showers", "cloud-rain"};
            case 81: return new String[]{"Showers", "cloud-rain"};
            case 82: return new String[]{"Heavy showers", "cloud-rain"};
            default: return new String[]{"Weather", "cloud"};
        }
    }

    static Sky sky(Reading r) {
        if (r == null) return Sky.NONE;
        switch (r.icon) {
            case "sun": return Sky.SUN;
            case "moon": case "cloud-moon": return Sky.NIGHT;
            case "cloud-sun": return Sky.PARTLY;
            case "cloud-rain": return Sky.RAIN;
            case "cloud-lightning": return Sky.STORM;
            default: return Sky.CLOUD;
        }
    }

    private static final int FAIR = 0, CLOUD = 1, RAIN = 2, STORM = 3, SNOW = 4;

    private static int kind(String icon) {
        if (icon.contains("lightning")) return STORM;
        if (icon.contains("rain")) return RAIN;
        if (icon.contains("snow")) return SNOW;
        if (icon.equals("cloud") || icon.equals("cloud-fog")) return CLOUD;
        return FAIR;
    }

    private static boolean wet(int k) {
        return k == RAIN || k == STORM || k == SNOW;
    }

    private static String shorter(String text) {
        switch (text) {
            case "Thunderstorms": return "Storms";
            case "Light rain": case "Freezing rain": return "Rain";
            case "Light showers": return "Showers";
            case "Heavy showers": return "Downpour";
            default: return text;
        }
    }

    /** What the weather does next: the first coming hour whose kind changes (bento.js nextFor). */
    static Next next(Reading r) {
        int now = kind(r.icon);
        Hour next = null;
        for (Hour h : r.hours) {
            if (kind(h.icon) != now) {
                next = h;
                break;
            }
        }
        if (next == null) {
            if (wet(now)) return new Next(r.icon, new String[]{shorter(r.text), "all evening"}, new boolean[]{true, false});
            return new Next(r.icon, new String[]{now == CLOUD ? "Cloudy" : "Clear", "till late"}, new boolean[]{true, false});
        }
        int k = kind(next.icon);
        String word = wet(k) ? shorter(next.text) : wet(now) ? "Dry" : k == CLOUD ? "Clouds" : "Clearing";
        return new Next(next.icon, new String[]{word, "from", next.label}, new boolean[]{true, false, true});
    }

    static int glyph(String icon) {
        if (icon == null) return R.drawable.ph_cloud;
        switch (icon) {
            case "sun": return R.drawable.ph_sun;
            case "moon": return R.drawable.ph_moon;
            case "cloud-sun": return R.drawable.ph_cloud_sun;
            case "cloud-moon": return R.drawable.ph_cloud_moon;
            case "cloud-fog": return R.drawable.ph_cloud_fog;
            case "cloud-snow": return R.drawable.ph_cloud_snow;
            case "cloud-rain": return R.drawable.ph_cloud_rain;
            case "cloud-lightning": return R.drawable.ph_cloud_lightning;
            default: return R.drawable.ph_cloud;
        }
    }

    // ---- On disk and from the network (blocking: call off the main thread)

    static final class Source {
        private final File file;
        private final String url;

        Source(File cacheDir, String lat, String lon) {
            file = new File(cacheDir, "home-weather.json");
            url = Weather.url(lat, lon);
        }

        Weather cached() {
            try {
                return file.isFile() ? parse(Net.readText(file)) : null;
            } catch (IOException e) {
                return null;
            }
        }

        long age(long now) {
            if (!file.isFile()) return Long.MAX_VALUE;
            long a = now - file.lastModified();
            return a < 0 ? Long.MAX_VALUE : a;
        }

        Weather fetch() throws IOException {
            String json = Net.getString(url);
            Weather w = parse(json);
            Net.writeAtomically(file, json);
            return w;
        }
    }
}
