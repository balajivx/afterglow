package com.balaji.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Colour maths: sRGB to OKLab/OKLCH and back, and an artwork's
 * vivid colour. Covers and the glow are put at one lightness with capped chroma, so no app is louder
 * than another.
 */
final class Colors {
    private Colors() {
    }

    /** No art, or art that can't be read: grey. */
    static final int FALLBACK = 0xFF444444;

    private static double toLinear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double toGamma(double x) {
        double c = Math.max(0, Math.min(1, x));
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    /** sRGB to OKLCH: {L 0..1, chroma, hue degrees}. */
    static double[] lch(int rgb) {
        double lr = toLinear(((rgb >> 16) & 0xFF) / 255.0);
        double lg = toLinear(((rgb >> 8) & 0xFF) / 255.0);
        double lb = toLinear((rgb & 0xFF) / 255.0);
        double l = Math.cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb);
        double m = Math.cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb);
        double s = Math.cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb);
        double L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double b = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        double h = (Math.toDegrees(Math.atan2(b, a)) + 360) % 360;
        return new double[]{L, Math.hypot(a, b), h};
    }

    /** OKLab to an opaque sRGB colour, clipped to gamut. */
    static int fromLab(double lightness, double a, double b) {
        double l = lightness + 0.3963377774 * a + 0.2158037573 * b;
        double m = lightness - 0.1055613458 * a - 0.0638541728 * b;
        double s = lightness - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        int r = (int) Math.round(toGamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s) * 255);
        int g = (int) Math.round(toGamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s) * 255);
        int bl = (int) Math.round(toGamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s) * 255);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static int oklch(double lightness, double chroma, double hue) {
        double h = Math.toRadians(hue);
        return fromLab(lightness, chroma * Math.cos(h), chroma * Math.sin(h));
    }

    /**
     * A brand colour at a set lightness, chroma capped: every app's cover gets the same visual weight.
     * Yellows lean to amber, since a dark yellow reads as olive. Greys stay grey.
     */
    static int even(int brand, double lightness) {
        double[] c = lch(brand);
        double h = c[2] > 75 && c[2] < 120 ? 68.0 : c[2];
        double chroma = c[1] < 0.03 ? 0.0 : Math.min(c[1], 0.16);
        return oklch(lightness, chroma, h);
    }

    /**
     * The art's vivid colour: shrink to 12 x 12 by averaging, take the most
     * saturated not-too-dark pixel, made a touch more vivid; a nearly grey picture gives its average, lifted.
     */
    static int vivid(Bitmap art) {
        if (art == null || art.isRecycled() || art.getWidth() == 0) return FALLBACK;
        try {
            int n = 48;
            Bitmap small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            new Canvas(small).drawBitmap(art, new Rect(0, 0, art.getWidth(), art.getHeight()),
                    new Rect(0, 0, n, n), new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] big = new int[n * n];
            small.getPixels(big, 0, n, 0, 0, n, n);
            small.recycle();
            int side = 12, block = 4;
            float[] hsv = new float[3];
            int best = FALLBACK;
            float bestScore = -1f;
            double sr = 0, sg = 0, sb = 0;
            int count = 0;
            for (int oy = 0; oy < side; oy++) {
                for (int ox = 0; ox < side; ox++) {
                    int r = 0, g = 0, b = 0, a = 0;
                    for (int y = oy * block; y < oy * block + block; y++) {
                        for (int x = ox * block; x < ox * block + block; x++) {
                            int p = big[y * n + x];
                            a += (p >>> 24) & 0xFF;
                            r += (p >> 16) & 0xFF;
                            g += (p >> 8) & 0xFF;
                            b += p & 0xFF;
                        }
                    }
                    if (a / 16 < 160) continue; // transparent corners of an icon don't count
                    r /= 16;
                    g /= 16;
                    b /= 16;
                    sr += r;
                    sg += g;
                    sb += b;
                    count++;
                    Color.RGBToHSV(r, g, b, hsv);
                    float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);
                    if (score > bestScore) {
                        bestScore = score;
                        best = Color.rgb(r, g, b);
                    }
                }
            }
            if (count == 0) return FALLBACK;
            if (bestScore < 0.25f) {
                Color.RGBToHSV((int) (sr / count), (int) (sg / count), (int) (sb / count), hsv);
                hsv[2] = Math.max(hsv[2], 0.55f);
                return Color.HSVToColor(hsv);
            }
            Color.colorToHSV(best, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.1f);
            hsv[2] = Math.max(0.5f, hsv[2]);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return FALLBACK;
        }
    }
}
