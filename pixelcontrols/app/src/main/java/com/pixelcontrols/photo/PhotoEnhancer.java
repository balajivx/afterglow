package com.pixelcontrols.photo;

import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * "Shot by a pro" auto-enhancer. Pure Java on ARGB int[] pixels so it runs identically on
 * the phone and in desktop tests.
 *
 * analyze() reads the photo the way a photographer reads a scene (what kind of shot it is,
 * where the exposure sits, whether light has a color cast, how much dynamic range is
 * clipped, how noisy it is) and picks settings. apply() then renders them:
 *   1. white balance (gray-world on neutral pixels + bright-neutral "white patch")
 *   2. exposure compensation in linear light
 *   3. black/white levels (haze removal / stretch)
 *   4. shadow lift and filmic highlight recovery
 *   5. scene-aware contrast curve
 *   6. vibrance with skin-tone protection
 *   7. clarity (midtone local contrast) and noise-aware sharpening
 * Tone is applied to luminance and RGB is scaled by the same ratio, so hues don't shift.
 */
public final class PhotoEnhancer {

    public enum Scene {
        GENERAL("General"), PORTRAIT("Portrait"), LANDSCAPE("Landscape"),
        SUNSET("Golden hour"), NIGHT("Low light"), BACKLIT("Backlit / high contrast");
        public final String label;
        Scene(String l) { label = l; }
    }

    public static final class Params {
        public Scene scene = Scene.GENERAL;
        public float ev;
        public float gainR = 1, gainG = 1, gainB = 1;
        public float blackPoint = 0, whitePoint = 1;   // gamma-encoded 0..1, after exposure
        public float topLinear = 1;                     // linear p99.9 of the input (pre-exposure)
        public float shadows, highlights, contrast, vibrance, clarity, sharpen;
        public float noiseThreshold = 2f;

        public Params scaled(float s) {
            Params p = new Params();
            p.scene = scene;
            p.ev = ev * s;
            p.gainR = (float) Math.pow(gainR, s);
            p.gainG = (float) Math.pow(gainG, s);
            p.gainB = (float) Math.pow(gainB, s);
            p.blackPoint = blackPoint * s;
            p.whitePoint = 1 - (1 - whitePoint) * s;
            p.topLinear = topLinear;
            p.shadows = shadows * s;
            p.highlights = highlights * s;
            p.contrast = contrast * s;
            p.vibrance = vibrance * s;
            p.clarity = clarity * s;
            p.sharpen = sharpen * s;
            p.noiseThreshold = noiseThreshold;
            return p;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(scene.label);
            if (Math.abs(ev) >= 0.1f) sb.append(String.format(Locale.US, " · Exposure %+.1f EV", ev));
            float warm = gainR / gainB;
            if (warm > 1.04f) sb.append(" · Warmer");
            else if (warm < 0.96f) sb.append(" · Cooler");
            if (blackPoint > 0.02f) sb.append(" · Dehaze");
            if (shadows >= 0.05f) sb.append(String.format(Locale.US, " · Shadows +%d", Math.round(shadows * 100)));
            if (highlights >= 0.05f) sb.append(String.format(Locale.US, " · Highlights −%d", Math.round(highlights * 100)));
            if (Math.abs(contrast) >= 0.03f) sb.append(String.format(Locale.US, " · Contrast %+d", Math.round(contrast * 100)));
            if (vibrance >= 0.05f) sb.append(String.format(Locale.US, " · Vibrance +%d", Math.round(vibrance * 100)));
            if (clarity >= 0.05f) sb.append(" · Clarity");
            if (sharpen >= 0.05f) sb.append(" · Sharpen");
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- color helpers
    private static final float[] LIN = new float[256];
    private static final int OUT_STEPS = 4096;
    private static final float[] OUT_GAMMA = new float[OUT_STEPS + 1];
    static {
        for (int i = 0; i < 256; i++) LIN[i] = (float) toLinear(i / 255.0);
        for (int i = 0; i <= OUT_STEPS; i++) OUT_GAMMA[i] = (float) toGamma(i / (double) OUT_STEPS);
    }

    static double toLinear(double g) {
        return g <= 0.04045 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
    }

    /** sRGB encode; values above 1 are extended with the same power curve. */
    static double toGamma(double l) {
        if (l <= 0) return 0;
        return l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
    }

    private static float gammaOf(float lin) {
        if (lin <= 0) return 0;
        if (lin >= 1) return 1;
        float f = lin * OUT_STEPS;
        int i = (int) f;
        float t = f - i;
        return OUT_GAMMA[i] + (OUT_GAMMA[Math.min(i + 1, OUT_STEPS)] - OUT_GAMMA[i]) * t;
    }

    // ---------------------------------------------------------------- analysis
    public static Params analyze(int[] px, int w, int h) { return analyze(px, w, h, false); }

    /** @param hasFaces true when a face detector found a prominent face (portrait mode). */
    public static Params analyze(int[] px, int w, int h, boolean hasFaces) {
        int n = w * h;
        int step = Math.max(1, (int) Math.sqrt(n / 250_000.0));
        int[] hist = new int[256];
        int count = 0;
        // Illuminant estimate, two scale-equivariant estimators (a color cast multiplies every
        // pixel and every gradient by the same per-channel factor, so both track it exactly):
        //  - shades-of-gray (Minkowski p=4) over non-clipped pixels
        //  - gray-edge (p=2) over local gradients: edges between surfaces carry the light's
        //    color but not the surfaces', so big single-colored objects bias it far less.
        double sgR = 0, sgG = 0, sgB = 0, geR = 0, geG = 0, geB = 0; int sgN = 0, geN = 0;
        double brightRB = 0, darkRB = 0; int brightN = 0, darkN = 0;
        double topY = 0, botY = 0; int topN = 0, botN = 0;
        int sky = 0, foliage = 0, dark = 0, bright = 0;
        double sumSat = 0;
        int[] lap = new int[256];
        int lapN = 0;

        for (int y = 0; y < h; y += step) {
            boolean top = y < h / 3, bottom = y >= 2 * h / 3;
            int row = y * w;
            for (int x = 0; x < w; x += step) {
                int c = px[row + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int Y = (54 * r + 183 * g + 19 * b) >> 8;
                hist[Y]++;
                count++;
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                sumSat += mx == 0 ? 0 : (mx - mn) / (float) mx;
                if (Y < 40) dark++;
                if (Y >= 225) bright++;
                if (top) { topY += Y; topN++; } else if (bottom) { botY += Y; botN++; }
                if (mx < 250 && Y > 8) {
                    double lr = LIN[r], lg = LIN[g], lb = LIN[b];
                    sgR += sq(sq(lr)); sgG += sq(sq(lg)); sgB += sq(sq(lb)); sgN++;
                    if (x + 1 < w && y + 1 < h) {
                        int cx = px[row + x + 1], cy = px[row + w + x];
                        if (maxc(cx) < 250 && maxc(cy) < 250) {
                            geR += sq(LIN[(cx >> 16) & 0xFF] - lr) + sq(LIN[(cy >> 16) & 0xFF] - lr);
                            geG += sq(LIN[(cx >> 8) & 0xFF] - lg) + sq(LIN[(cy >> 8) & 0xFF] - lg);
                            geB += sq(LIN[cx & 0xFF] - lb) + sq(LIN[cy & 0xFF] - lb);
                            geN++;
                        }
                    }
                }
                if (Y > 150) { brightRB += (r + 1.0) / (b + 1.0); brightN++; }
                else if (Y > 40 && Y < 100) { darkRB += (r + 1.0) / (b + 1.0); darkN++; }
                if (top && b > r + 10 && b >= g && Y > 110) sky++;
                if (g > r + 8 && g > b + 8) foliage++;
                if (x > 0 && y > 0 && x < w - 1 && y < h - 1) {
                    int l = 4 * Y - luma(px[row + x - 1]) - luma(px[row + x + 1])
                            - luma(px[row - w + x]) - luma(px[row + w + x]);
                    lap[Math.min(255, Math.abs(l))]++;
                    lapN++;
                }
            }
        }

        float p005 = percentile(hist, count, 0.005), p25 = percentile(hist, count, 0.25);
        float p50 = percentile(hist, count, 0.50), p995 = percentile(hist, count, 0.995);
        float p999 = percentile(hist, count, 0.999);
        float clipped = (hist[255] + hist[254] + hist[253] + hist[252] + hist[251] + hist[250]) / (float) count;
        float darkFrac = dark / (float) count, brightFrac = bright / (float) count;
        float meanSat = (float) (sumSat / count);
        float noise = lapN > 0 ? percentile(lap, lapN, 0.5) : 2;
        double bRB = brightN > 0 ? brightRB / brightN : 1, dRB = darkN > 0 ? darkRB / darkN : 1;

        Params p = new Params();

        // ---- scene classification
        boolean lightSources = p995 > 200 || clipped > 0.002f;
        boolean topBrighter = topN > 0 && botN > 0 && topY / topN > botY / botN + 15;
        if (p50 < 45 && lightSources) p.scene = Scene.NIGHT;
        else if ((clipped > 0.015f || p995 >= 250) && darkFrac > 0.25f && p50 < 100) p.scene = Scene.BACKLIT;
        // Golden hour: warm light in the bright areas but NOT in the shadows (a white-balance
        // error tints everything equally) and a bright sky region on top.
        else if (brightN > count / 20 && bRB > 1.6 && (dRB < bRB * 0.75 || darkFrac > 0.3f) && topBrighter && meanSat > 0.3f)
            p.scene = Scene.SUNSET;
        else if (hasFaces) p.scene = Scene.PORTRAIT;
        else if (sky > count * 0.06 || foliage > count * 0.25) p.scene = Scene.LANDSCAPE;

        // ---- white balance
        double gr = 1, gb = 1;
        if (sgN > count / 10 && geN > count / 10) {
            double eR1 = Math.pow(sgR / sgN, 0.25), eG1 = Math.pow(sgG / sgN, 0.25), eB1 = Math.pow(sgB / sgN, 0.25);
            double eR2 = Math.sqrt(geR / geN), eG2 = Math.sqrt(geG / geN), eB2 = Math.sqrt(geB / geN);
            if (eR1 > 0 && eB1 > 0 && eR2 > 0 && eB2 > 0) {
                double lr1 = Math.log(eG1 / eR1), lb1 = Math.log(eG1 / eB1);
                double lr2 = Math.log(eG2 / eR2), lb2 = Math.log(eG2 / eB2);
                double lr = 0.35 * lr1 + 0.65 * lr2, lb = 0.35 * lb1 + 0.65 * lb2;
                // Confidence: if the estimators disagree, the scene's own colors are fooling them.
                double disagree = Math.abs(lr1 - lr2) + Math.abs(lb1 - lb2);
                double conf = clamp(1.0 - (disagree - 0.08) / 0.30, 0.25, 1.0);
                // Split into the temperature axis (amber <-> blue, where real lighting errors
                // live: tungsten, shade, LEDs) and the tint axis (green <-> magenta, rarer, and
                // where scene content like foliage biases estimates most).
                double temp = (lr - lb) / 2, tint = (lr + lb) / 2;
                // Shrink small deviations: they are more likely the scene than the light.
                temp *= shrink(temp, 0.12);
                tint *= shrink(tint, 0.18) * 0.5;
                double strength;
                switch (p.scene) {
                    case SUNSET: strength = 0.2; break;     // the warmth is the point of the shot
                    case NIGHT: strength = 0.35; break;     // mixed artificial light: correct gently
                    default: strength = 0.85;
                }
                strength *= conf;
                lr = (temp + tint) * strength;
                lb = (tint - temp) * strength;
                if (Math.abs(lr) < 0.02) lr = 0;
                if (Math.abs(lb) < 0.02) lb = 0;
                gr = clamp(Math.exp(lr), 0.75, 1.33);
                gb = clamp(Math.exp(lb), 0.75, 1.33);
            }
        }
        double norm = 0.2126 * gr + 0.7152 + 0.0722 * gb;   // keep overall brightness unchanged
        p.gainR = (float) (gr / norm);
        p.gainG = (float) (1 / norm);
        p.gainB = (float) (gb / norm);

        // ---- exposure: only correct what is actually wrong
        float target;
        switch (p.scene) {
            case PORTRAIT: target = 125; break;
            case LANDSCAPE: target = 115; break;
            case BACKLIT: target = 105; break;
            case SUNSET: target = 100; break;
            case NIGHT: target = 70; break;
            default: target = 112;
        }
        double evTarget = log2(toLinear(target / 255.0) / toLinear(Math.max(p50, 2) / 255.0));
        double ev = 0;
        if (evTarget > 0) {
            // Underexposed = unused headroom. Don't push the brightest tones past white
            // (the filmic shoulder absorbs ~0.3 EV of overshoot gracefully).
            double headroom = log2(toLinear(248 / 255.0) / toLinear(Math.max(p995, 8) / 255.0)) + 0.3;
            ev = Math.min(evTarget, Math.max(0, headroom));
            ev = Math.min(ev, p.scene == Scene.NIGHT ? 1.2 : 1.8);
        } else if (clipped > 0.015f || p50 > 200) {
            // Overexposed = real clipping. A bright but intact scene (snow, white walls,
            // high-key) is left alone. Never push the deepest shadows into black.
            double footroom = log2(toLinear(4 / 255.0) / toLinear(Math.max(p005, 4) / 255.0));
            ev = Math.max(Math.max(evTarget, -1.2), footroom);
        }
        ev *= 0.85;
        if (Math.abs(ev) < 0.34) ev = 0;                       // within 1/3 stop: leave the photographer's choice
        p.ev = (float) ev;
        double gain = Math.pow(2, ev);
        p.topLinear = (float) toLinear(p999 / 255.0);

        // ---- levels (measured after exposure). Flatness = both ends compressed (haze, fog,
        // cheap lens flare, a dirty lens). Bright-but-contrasty scenes aren't flat.
        float lowG = (float) toGamma(toLinear(p005 / 255.0) * gain);
        float highG = (float) Math.min(1, toGamma(toLinear(p995 / 255.0) * gain));
        float flatness = (float) clamp((0.85 - (highG - lowG)) / 0.35, 0, 1);
        if (lowG > 0.04f && p.scene != Scene.NIGHT) {
            float bp = Math.max(0, lowG - 0.05f) * (0.15f + 0.75f * flatness);
            if (bp > 0.015f) p.blackPoint = Math.min(bp, 0.4f);
        }
        if (ev < 0 && clipped > 0.005f) {
            p.whitePoint = (float) toGamma(gain);              // blown areas stay white, mids come down
        } else if (highG < 0.92f && (flatness > 0.5f || highG < 0.7f)
                && p.scene != Scene.NIGHT && p.scene != Scene.SUNSET) {
            p.whitePoint = Math.max(highG + 0.03f, 0.6f);
        }

        // ---- shadows / highlights
        float p25G = (float) toGamma(toLinear(p25 / 255.0) * gain);
        float sh = (float) clamp((0.30 - p25G) / 0.30, 0, 1) * 0.6f;
        if (p.scene == Scene.BACKLIT) sh = Math.min(0.9f, Math.max(sh, 0.5f) + 0.2f);
        if (p.scene == Scene.NIGHT) sh *= 0.4f;
        p.shadows = sh;
        float hl = (float) clamp((brightFrac - clipped - 0.04) * 4, 0, 0.5);
        if (p.scene == Scene.BACKLIT) hl += 0.3f;
        if (p.scene == Scene.SUNSET) hl += 0.2f;
        p.highlights = Math.min(0.8f, hl);

        // ---- contrast: measure the spread the tone curve produces, aim for a natural band
        p.contrast = 0;
        float[] tone0 = buildTone(p);
        float[] gray = buildChannelLuts(p)[1];
        double s1 = 0, s2 = 0;
        for (int i = 0; i < 256; i++) {
            if (hist[i] == 0) continue;
            double out = toGamma(lookup(tone0, gray[i]));
            s1 += hist[i] * out;
            s2 += hist[i] * out * out;
        }
        double mean = s1 / count, std = Math.sqrt(Math.max(0, s2 / count - mean * mean));
        double c = 0;
        if (std < 0.18) c = Math.min(0.35, (0.20 - std) / 0.15);
        else if (std > 0.30) c = Math.max(-0.12, (0.28 - std) / 0.3);
        if (p.scene == Scene.PORTRAIT) c = Math.min(c, 0.2);
        if (p.scene == Scene.NIGHT) c = Math.min(c, 0.15);
        if (p.scene == Scene.BACKLIT) c = Math.min(c, 0.1);   // we just opened it up: don't re-crush it
        if (Math.abs(c) < 0.03) c = 0;
        p.contrast = (float) c;

        // ---- color + detail
        float vib;
        switch (p.scene) {
            case PORTRAIT: vib = 0.15f; break;
            case LANDSCAPE: vib = 0.40f; break;
            case SUNSET: vib = 0.35f; break;
            case NIGHT: vib = 0.10f; break;
            case BACKLIT: vib = 0.25f; break;
            default: vib = 0.25f;
        }
        if (meanSat > 0.45f) vib *= 0.4f; else if (meanSat < 0.15f) vib *= 1.3f;
        p.vibrance = vib;

        float noisy = (float) clamp((noise - 6) / 14.0, 0, 1);  // 0 = clean, 1 = very noisy
        float cl;
        switch (p.scene) {
            case PORTRAIT: cl = 0.08f; break;
            case LANDSCAPE: cl = 0.35f; break;
            case NIGHT: cl = 0.10f; break;
            case BACKLIT: cl = 0.30f; break;
            case SUNSET: cl = 0.20f; break;
            default: cl = 0.25f;
        }
        p.clarity = cl * (1 - 0.6f * noisy);
        float shp = (float) clamp(0.5 * Math.min(w, h) / 1500.0, 0.25, 0.9);
        if (p.scene == Scene.PORTRAIT) shp *= 0.5f;
        if (p.scene == Scene.NIGHT) shp *= 0.4f;
        p.sharpen = shp * (1 - 0.7f * noisy);
        p.noiseThreshold = (float) clamp(noise * 0.6, 1.5, 10);
        return p;
    }

    private static double sq(double v) { return v * v; }
    /** Wiener-style shrinkage factor: ~0 for |v| << t, ~1 for |v| >> t. */
    private static double shrink(double v, double t) { return v * v / (v * v + t * t); }
    private static double log2(double v) { return Math.log(v) / Math.log(2); }
    private static int maxc(int c) { return Math.max((c >> 16) & 0xFF, Math.max((c >> 8) & 0xFF, c & 0xFF)); }

    private static int luma(int c) {
        return (54 * ((c >> 16) & 0xFF) + 183 * ((c >> 8) & 0xFF) + 19 * (c & 0xFF)) >> 8;
    }

    private static float percentile(int[] hist, int count, double q) {
        long target = (long) Math.ceil(count * q);
        long acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc >= target) return i;
        }
        return hist.length - 1;
    }

    // ---------------------------------------------------------------- tone curves
    /** Exposure, white balance and black/white levels: per channel, 8-bit sRGB -> linear. */
    static float[][] buildChannelLuts(Params p) {
        double gain = Math.pow(2, p.ev);
        double[] wb = {p.gainR, p.gainG, p.gainB};
        float[][] luts = new float[3][256];
        for (int ch = 0; ch < 3; ch++) {
            for (int v = 0; v < 256; v++) {
                double g = toGamma(LIN[v] * wb[ch] * gain);
                g = levels(g, p);
                luts[ch][v] = (float) toLinear(g);
            }
        }
        return luts;
    }

    private static double levels(double g, Params p) {
        g = (g - p.blackPoint) / (p.whitePoint - p.blackPoint);
        return g < 0 ? 0 : g;                     // > 1 is kept: the shoulder rolls it off
    }


    private static final int TONE_STEPS = 8192;
    private static final float TONE_MAX = 3f;     // linear headroom for exposure overshoot

    /** Shadows, highlights (filmic shoulder + recovery) and contrast on linear luminance. */
    static float[] buildTone(Params p) {
        float[] lut = new float[TONE_STEPS + 1];
        double knee = 0.75 - 0.25 * p.highlights;
        double gHigh = levels(toGamma(p.topLinear * Math.pow(2, p.ev)), p);
        double tmax = Math.max(1, (gHigh - knee) / (1 - knee));
        double dip = 1.2 * p.highlights;
        for (int i = 0; i <= TONE_STEPS; i++) {
            double x = i * (double) TONE_MAX / TONE_STEPS;
            double g = toGamma(x);
            if (g < 1 && p.shadows > 0) {
                double inv = 1 - g;
                g += p.shadows * 2.0 * g * inv * inv * inv * inv;
            }
            if (g > knee) {
                double t = (g - knee) / (1 - knee);
                if (tmax > 1) t = t * (1 + t / (tmax * tmax)) / (1 + t);   // fit pushed tones under white
                if (t > 1) t = 1;
                // Highlight recovery: pull near-white tones down while white stays white, which
                // steepens the curve right below white and brings back texture in bright areas.
                t = t - dip * t * t * (1 - t);
                g = knee + (1 - knee) * t;
            }
            if (g > 1) g = 1;
            if (p.contrast != 0) g += p.contrast * (g * g * (3 - 2 * g) - g);
            lut[i] = (float) toLinear(clamp(g, 0, 1));
        }
        return lut;
    }

    private static float lookup(float[] lut, float x) {
        if (x <= 0) return lut[0];
        float f = x * (TONE_STEPS / TONE_MAX);
        if (f >= TONE_STEPS) return lut[TONE_STEPS];
        int i = (int) f;
        return lut[i] + (lut[i + 1] - lut[i]) * (f - i);
    }

    // ---------------------------------------------------------------- rendering
    /** Enhances px in place. */
    public static void apply(final int[] px, final int w, final int h, final Params p) {
        final float[] tone = buildTone(p);
        final float[][] ch = buildChannelLuts(p);
        final float[] LR = ch[0], LG = ch[1], LB = ch[2];
        final float vib = p.vibrance;

        IntStream.range(0, h).parallel().forEach(new IntConsumer() { @Override public void accept(int y) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[row + x];
                float r = LR[(c >> 16) & 0xFF], g = LG[(c >> 8) & 0xFF], b = LB[c & 0xFF];
                float Y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float Yo = lookup(tone, Y);
                if (Y > 1e-6f) {
                    float k = Yo / Y;
                    r *= k; g *= k; b *= k;
                } else {
                    r = g = b = Yo;
                }
                float mx = Math.max(r, Math.max(g, b));
                if (mx > 1f) {                         // out of gamut: desaturate toward Yo
                    float t = Yo >= 1f ? 0f : (1f - Yo) / (mx - Yo);
                    r = Yo + (r - Yo) * t; g = Yo + (g - Yo) * t; b = Yo + (b - Yo) * t;
                }
                float R = gammaOf(r), G = gammaOf(g), B = gammaOf(b);
                if (vib > 0) {
                    float L = 0.2126f * R + 0.7152f * G + 0.0722f * B;
                    float cmax = Math.max(R, Math.max(G, B)), cmin = Math.min(R, Math.min(G, B));
                    float chroma = cmax - cmin, inv = 1 - chroma;
                    float amt = vib * inv * inv;
                    if (R > G && G > B && R - B < 0.45f && R > 0.25f) amt *= 0.35f;  // skin
                    float f = 1 + amt;
                    // keep every channel inside [0,1] without clipping hue
                    if (cmax > L) f = Math.min(f, (1 - L) / (cmax - L));
                    if (cmin < L) f = Math.min(f, L / (L - cmin));
                    R = L + (R - L) * f; G = L + (G - L) * f; B = L + (B - L) * f;
                }
                px[row + x] = (c & 0xFF000000) | (to8(R) << 16) | (to8(G) << 8) | to8(B);
            }
        } });

        if (p.clarity > 0.01f || p.sharpen > 0.01f) detailPass(px, w, h, p);
    }

    private static int to8(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** Clarity (large-radius, midtone-weighted local contrast) + noise-cored unsharp mask. */
    private static void detailPass(final int[] px, final int w, final int h, final Params p) {
        final byte[] Y = new byte[w * h];
        IntStream.range(0, h).parallel().forEach(new IntConsumer() { @Override public void accept(int y) {
            for (int x = 0, i = y * w; x < w; x++, i++) Y[i] = (byte) luma(px[i]);
        } });

        // Low-res base layer for clarity: average-pool so the short side is ~200px, then blur.
        final int ds = Math.max(1, Math.min(w, h) / 200);
        final int sw = (w + ds - 1) / ds, sh = (h + ds - 1) / ds;
        final float[] base = new float[sw * sh];
        for (int sy = 0; sy < sh; sy++) {
            for (int sx = 0; sx < sw; sx++) {
                int sum = 0, cnt = 0;
                for (int y = sy * ds, ye = Math.min(h, y + ds); y < ye; y++)
                    for (int x = sx * ds, xe = Math.min(w, x + ds); x < xe; x++) { sum += Y[y * w + x] & 0xFF; cnt++; }
                base[sy * sw + sx] = sum / (float) cnt;
            }
        }
        for (int pass = 0; pass < 3; pass++) boxBlur(base, sw, sh, 3);

        final float clarity = p.clarity, sharpen = p.sharpen, thr = p.noiseThreshold;
        IntStream.range(0, h).parallel().forEach(new IntConsumer() { @Override public void accept(int y) {
            float fy = Math.min(sh - 1f, Math.max(0f, (y + 0.5f) / ds - 0.5f));
            int y0 = (int) fy, y1 = Math.min(sh - 1, y0 + 1);
            float ty = fy - y0;
            int ym = Math.max(0, y - 1), yp = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int yc = Y[i] & 0xFF;

                float fx = Math.min(sw - 1f, Math.max(0f, (x + 0.5f) / ds - 0.5f));
                int x0 = (int) fx, x1 = Math.min(sw - 1, x0 + 1);
                float tx = fx - x0;
                float b0 = base[y0 * sw + x0] + (base[y0 * sw + x1] - base[y0 * sw + x0]) * tx;
                float b1 = base[y1 * sw + x0] + (base[y1 * sw + x1] - base[y1 * sw + x0]) * tx;
                float bl = b0 + (b1 - b0) * ty;
                float m = yc / 127.5f - 1f;
                float d1 = clarity * (1 - m * m) * (yc - bl);
                d1 = Math.max(-30f, Math.min(30f, d1));

                int xm = Math.max(0, x - 1), xp = Math.min(w - 1, x + 1);
                int blur3 = (Y[ym * w + xm] & 0xFF) + 2 * (Y[ym * w + x] & 0xFF) + (Y[ym * w + xp] & 0xFF)
                        + 2 * (Y[y * w + xm] & 0xFF) + 4 * yc + 2 * (Y[y * w + xp] & 0xFF)
                        + (Y[yp * w + xm] & 0xFF) + 2 * (Y[yp * w + x] & 0xFF) + (Y[yp * w + xp] & 0xFF);
                float d = yc - blur3 / 16f;
                float ad = Math.abs(d);
                if (ad < thr) d *= ad / thr;                 // soft coring: leave noise alone
                float d2 = Math.max(-25f, Math.min(25f, sharpen * 1.6f * d));

                float df = d1 + d2;
                int c = px[i];
                if (df > 0) {                                  // never create new blown highlights
                    int mxc = Math.max((c >> 16) & 0xFF, Math.max((c >> 8) & 0xFF, c & 0xFF));
                    df = Math.min(df, (254 - mxc) * 0.75f);
                }
                int delta = Math.round(df);
                if (delta == 0) continue;
                int r = clamp8(((c >> 16) & 0xFF) + delta), g = clamp8(((c >> 8) & 0xFF) + delta), b = clamp8((c & 0xFF) + delta);
                px[i] = (c & 0xFF000000) | (r << 16) | (g << 8) | b;
            }
        } });
    }

    private static int clamp8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    private static void boxBlur(float[] a, int w, int h, int r) {
        float[] tmp = new float[Math.max(w, h)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0; int c = 0;
                for (int k = Math.max(0, x - r), e = Math.min(w - 1, x + r); k <= e; k++) { s += a[y * w + k]; c++; }
                tmp[x] = s / c;
            }
            System.arraycopy(tmp, 0, a, y * w, w);
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float s = 0; int c = 0;
                for (int k = Math.max(0, y - r), e = Math.min(h - 1, y + r); k <= e; k++) { s += a[k * w + x]; c++; }
                tmp[y] = s / c;
            }
            for (int y = 0; y < h; y++) a[y * w + x] = tmp[y];
        }
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
