package com.balaji.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;

/**
 * Plain GETs with short timeouts and size caps, and pictures cut to the size they're shown. Blocking: always called off the main thread.
 */
final class Net {
    private Net() {
    }

    /** An HTTP answer other than 200; 429 means "slow down". */
    static final class StatusException extends IOException {
        final int code;

        StatusException(int code, String url) {
            super("HTTP " + code + " from " + url.split("\\?")[0]);
            this.code = code;
        }
    }

    static String getString(String url) throws IOException {
        return getString(url, Collections.emptyMap());
    }

    static String getString(String url, Map<String, String> headers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        get(url, headers, 2 << 20, out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static void download(String url, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            get(url, Collections.emptyMap(), 8 << 20, out);
        } catch (IOException e) {
            file.delete();
            throw e;
        }
    }

    private static void get(String url, Map<String, String> headers, int maxBytes, OutputStream out) throws IOException {
        request(url, headers, null, maxBytes, out);
    }

    private static void request(String url, Map<String, String> headers, String body, int maxBytes, OutputStream out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "Afterglow/7 (Android TV home screen)");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new StatusException(code, url);
            if (c.getContentLengthLong() > maxBytes) throw new IOException("Too big");
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int total = 0, n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > maxBytes) throw new IOException("Too big");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Writes [text] beside [file] then renames it in, so a power cut never leaves half a file. */
    static void writeAtomically(File file, String text) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) throw new IOException("Couldn't save " + file.getName());
        }
    }

    static String readText(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---- Pictures

    /** Pictures shown recently: 2 MB. */
    private static final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(2 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getAllocationByteCount();
        }
    };

    static Bitmap remembered(String key) {
        Bitmap b = memory.get(key);
        return b != null && !b.isRecycled() ? b : null;
    }

    static void remember(String key, Bitmap b) {
        memory.put(key, b);
    }

    static void trim() {
        memory.evictAll();
    }

    /** Decodes [file] with the largest sample size that still covers the slot, then crops to fill it. */
    static Bitmap decodeFile(File file, int width, int height) {
        if (width <= 0 || height <= 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inScaled = false;
        Bitmap full = BitmapFactory.decodeFile(file.getPath(), o);
        if (full == null) return null;
        Bitmap fitted = cover(full, width, height);
        if (fitted != full) full.recycle();
        return fitted;
    }

    /** [src] cropped to [width] x [height]'s shape and scaled to it, as object-fit: cover (RGB_565, dithered). */
    static Bitmap cover(Bitmap src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height && src.getConfig() == Bitmap.Config.RGB_565) return src;
        float scale = Math.max(width / (float) src.getWidth(), height / (float) src.getHeight());
        int sw = Math.max(1, Math.min(src.getWidth(), Math.round(width / scale)));
        int sh = Math.max(1, Math.min(src.getHeight(), Math.round(height / scale)));
        int sx = (src.getWidth() - sw) / 2, sy = (src.getHeight() - sh) / 2;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        new Canvas(out).drawBitmap(src, new Rect(sx, sy, sx + sw, sy + sh), new Rect(0, 0, width, height),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        return out;
    }

    static void save(Bitmap bitmap, File file) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("Couldn't save " + file.getName());
        }
    }

    /** A file name for a picture's URL at a size. */
    static String keyFor(String url, int width, int height) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex + "-" + width + "x" + height;
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(url.hashCode()) + "-" + width + "x" + height;
        }
    }
}
