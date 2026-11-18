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
 *   4. local shadow recovery (edge-aware dodging, no halos) and filmic highlight recovery
 *   5. scene-aware contrast curve
 *   6. vibrance with skin-tone protection
 *   7. noise reduction (chroma speckle + luma grain, measured from the photo's flat areas)
 *   8. clarity (midtone local contrast) and noise-aware sharpening
 * Portraits are metered on the faces, the way a portrait photographer exposes.
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
        public float nrLuma, nrChroma;                  // estimated output noise sigma (8-bit units); 0 = off
        public boolean faceMetered;

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
            p.nrLuma = nrLuma * s;
            p.nrChroma = nrChroma * s;
            p.faceMetered = faceMetered;
            return p;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(scene.label);
            if (Math.abs(ev) >= 0.1f) sb.append(String.format(Locale.US, " · Exposure %+.1f EV", ev));
            if (faceMetered) sb.append(" (metered on face)");
            float warm = gainR / gainB;
            if (warm > 1.04f) sb.append(" · Warmer");
            else if (warm < 0.96f) sb.append(" · Cooler");
            if (blackPoint > 0.02f) sb.append(" · Dehaze");
            if (shadows >= 0.05f) sb.append(String.format(Locale.US, " · Shadows +%d", Math.round(shadows * 100)));
            if (highlights >= 0.05f) sb.append(String.format(Locale.US, " · Highlights −%d", Math.round(highlights * 100)));
            if (Math.abs(contrast) >= 0.03f) sb.append(String.format(Locale.US, " · Contrast %+d", Math.round(contrast * 100)));
            if (vibrance >= 0.05f) sb.append(String.format(Locale.US, " · Vibrance +%d", Math.round(vibrance * 100)));
            if (nrLuma >= NR_LUMA_MIN || nrChroma >= NR_CHROMA_MIN) sb.append(" · Noise reduction");
            if (clarity >= 0.05f) sb.append(" · Clarity");
            if (sharpen >= 0.05f) sb.append(" · Sharpen");
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- color helpers
    private static final float[] LIN = new float[256];
    private static final int OUT_STEPS = 4096;
    private static final float[] OUT_GAMMA = new float[OUT_STEPS + 1];
    private static final double[] LN = new double[256];
    static {
        for (int i = 0; i < 256; i++) LIN[i] = (float) toLinear(i / 255.0);
        for (int i = 0; i < 256; i++) LN[i] = Math.log(LIN[i] + 1e-3);
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
    static final float NR_LUMA_MIN = 3.5f, NR_CHROMA_MIN = 2.5f;
    static double WB_W_GP = 0.5;                        // weight of the grey-pixel estimate
    private static final int GRID = 24;                 // coarse luminance map for backlight detection

    public static Params analyze(int[] px, int w, int h) { return analyze(px, w, h, null); }

    /**
     * @param faces face boxes from a face detector as {x, y, width, height, ...} in pixel
     *              coordinates of px, or null. Faces switch on portrait mode and face metering.
     */
    public static Params analyze(int[] px, int w, int h, int[] faces) {
        boolean hasFaces = faces != null && faces.length >= 4;
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
        // grey pixels: greyness histogram with linear RGB sums per bin
        final int GB = 200;
        double[] gpR = new double[GB], gpG = new double[GB], gpB = new double[GB];
        int[] gpN = new int[GB];
        int gpTotal = 0;
        // noise: mean |Laplacian| per grid cell (luma) and chroma Laplacian
        double[] cellLap = new double[GRID * GRID], cellY = new double[GRID * GRID];
        int[] cellLapN = new int[GRID * GRID], cellN = new int[GRID * GRID];
        double[] cellChroma = new double[GRID * GRID];

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
                int cell = Math.min(GRID - 1, y * GRID / h) * GRID + Math.min(GRID - 1, x * GRID / w);
                cellY[cell] += 0.2126 * LIN[r] + 0.7152 * LIN[g] + 0.0722 * LIN[b];
                cellN[cell]++;
                if (x > 0 && y > 0 && x < w - 1 && y < h - 1) {
                    int cl = px[row + x - 1], cr = px[row + x + 1], cu = px[row - w + x], cd = px[row + w + x];
                    int l = 4 * Y - luma(cl) - luma(cr) - luma(cu) - luma(cd);
                    lap[Math.min(255, Math.abs(l))]++;
                    lapN++;
                    cellLap[cell] += Math.abs(l);
                    // chroma noise: Laplacian of (R - B)
                    int k = 4 * (r - b) - (((cl >> 16) & 0xFF) - (cl & 0xFF)) - (((cr >> 16) & 0xFF) - (cr & 0xFF))
                            - (((cu >> 16) & 0xFF) - (cu & 0xFF)) - (((cd >> 16) & 0xFF) - (cd & 0xFF));
                    cellChroma[cell] += Math.abs(k);
                    cellLapN[cell]++;
                    // Grey pixels: on a neutral surface, light falls off identically in all three
                    // channels, so the local contrast of log R, log G and log B is the same.
                    if (mx < 250 && Y > 20) {
                        double dR = Math.abs(4 * LN[r] - LN[(cl >> 16) & 0xFF] - LN[(cr >> 16) & 0xFF] - LN[(cu >> 16) & 0xFF] - LN[(cd >> 16) & 0xFF]);
                        double dG = Math.abs(4 * LN[g] - LN[(cl >> 8) & 0xFF] - LN[(cr >> 8) & 0xFF] - LN[(cu >> 8) & 0xFF] - LN[(cd >> 8) & 0xFF]);
                        double dB = Math.abs(4 * LN[b] - LN[cl & 0xFF] - LN[cr & 0xFF] - LN[cu & 0xFF] - LN[cd & 0xFF]);
                        double m = (dR + dG + dB) / 3;
                        if (m > 0.15 && maxc(cl) < 250 && maxc(cr) < 250 && maxc(cu) < 250 && maxc(cd) < 250) {
                            double grey = (Math.abs(dR - m) + Math.abs(dG - m) + Math.abs(dB - m)) / (3 * m);
                            int bin = (int) Math.min(GB - 1, grey * GB);
                            gpR[bin] += LIN[r]; gpG[bin] += LIN[g]; gpB[bin] += LIN[b]; gpN[bin]++;
                            gpTotal++;
                        }
                    }
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

        // Coarse luminance map: a large dark region next to a large bright one = backlit subject.
        int darkCells = 0, brightCells = 0, usedCells = 0;
        for (int i = 0; i < GRID * GRID; i++) {
            if (cellN[i] == 0) continue;
            usedCells++;
            double ly = cellY[i] / cellN[i];
            if (ly < 0.045) darkCells++;
            else if (ly > 0.40) brightCells++;
        }
        boolean splitLight = usedCells > 0 && darkCells >= usedCells * 0.20 && brightCells >= usedCells * 0.15;


        // Noise from the flattest 10% of cells, so texture (fur, foliage) isn't mistaken for noise.
        double[] flatLap = new double[GRID * GRID], flatChroma = new double[GRID * GRID];
        int nc = 0;
        for (int i = 0; i < GRID * GRID; i++) {
            if (cellLapN[i] < 8) continue;
            flatLap[nc] = cellLap[i] / cellLapN[i];
            flatChroma[nc] = cellChroma[i] / cellLapN[i];
            nc++;
        }
        double sigmaLuma = 0, sigmaChroma = 0;
        if (nc > 0) {
            java.util.Arrays.sort(flatLap, 0, nc);
            java.util.Arrays.sort(flatChroma, 0, nc);
            // mean |Laplacian| of Gaussian noise = 0.798 * sqrt(20) * sigma = 3.57 sigma
            sigmaLuma = flatLap[nc / 10] / 3.57;
            sigmaChroma = flatChroma[nc / 10] / (3.57 * 1.41);   // R-B doubles the variance
        }

        // ---- scene classification
        boolean lightSources = p995 > 200 || clipped > 0.002f;
        boolean topBrighter = topN > 0 && botN > 0 && topY / topN > botY / botN + 15;
        if (p50 < 45 && lightSources) p.scene = Scene.NIGHT;
        else if (((clipped > 0.015f || p995 >= 250) && darkFrac > 0.25f && p50 < 100) || (splitLight && !hasFaces))
            p.scene = Scene.BACKLIT;
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
                // Grey pixels (greyest 2% by how evenly light falls off across channels) are the
                // most reliable witness of the light's color, especially in very colorful scenes.
                int need = Math.max(30, gpTotal / 50), got = 0;
                double sR = 0, sG = 0, sB = 0;
                for (int bin = 0; bin < GB && got < need; bin++) { sR += gpR[bin]; sG += gpG[bin]; sB += gpB[bin]; got += gpN[bin]; }
                if (gpTotal >= 200 && got >= 30 && sR > 0 && sB > 0) {
                    double lr3 = Math.log(sG / sR), lb3 = Math.log(sG / sB);
                    lr = WB_W_GP * lr3 + (1 - WB_W_GP) * lr;
                    lb = WB_W_GP * lb3 + (1 - WB_W_GP) * lb;
                    disagree = Math.min(disagree, Math.abs(lr3 - lr2) + Math.abs(lb3 - lb2));
                }
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
        if (hasFaces) {
            // Expose for the face (inner part of each box: skin, not hair or background).
            double fSum = 0; int fN = 0;
            for (int f = 0; f + 3 < faces.length; f += 4) {
                int fx0 = faces[f] + faces[f + 2] / 5, fy0 = faces[f + 1] + faces[f + 3] / 5;
                int fx1 = faces[f] + faces[f + 2] * 4 / 5, fy1 = faces[f + 1] + faces[f + 3] * 4 / 5;
                int fs = Math.max(1, (fx1 - fx0) / 40);
                for (int y = Math.max(0, fy0); y < Math.min(h, fy1); y += fs)
                    for (int x = Math.max(0, fx0); x < Math.min(w, fx1); x += fs) {
                        int c = px[y * w + x];
                        fSum += 0.2126 * LIN[(c >> 16) & 0xFF] + 0.7152 * LIN[(c >> 8) & 0xFF] + 0.0722 * LIN[c & 0xFF];
                        fN++;
                    }
            }
            if (fN > 20) {
                double faceLin = Math.max(1e-4, fSum / fN);
                double evFace = log2(toLinear(0.60) / faceLin) * 0.85;   // skin around L* 65
                evFace = clamp(evFace, -1.0, 1.8);
                if (Math.abs(evFace) < 0.25) evFace = 0;
                ev = evFace;
                p.faceMetered = evFace != 0;
            }
        }
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
        if (splitLight) sh = Math.max(sh, 0.8f);

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
        // Noise grows with every stop of brightening and with the shadow lift.
        double amp = Math.pow(2, Math.max(0, ev) / 2.2) * (1 + 0.6 * p.shadows);
        double nl = sigmaLuma * amp, ncr = sigmaChroma * amp;
        p.nrLuma = nl >= NR_LUMA_MIN ? (float) Math.min(nl, 25) : 0;
        p.nrChroma = ncr >= NR_CHROMA_MIN ? (float) Math.min(ncr, 25) : 0;
        if (System.getProperty("nrdebug") != null)
            System.out.printf("   [nr] sigmaLuma=%.2f sigmaChroma=%.2f amp=%.2f -> nrLuma=%.2f nrChroma=%.2f sharpen=%.2f thr=%.1f%n",
                    sigmaLuma, sigmaChroma, amp, p.nrLuma, p.nrChroma, p.sharpen, p.noiseThreshold);
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
        final LocalBase lb = p.shadows > 0.02f ? LocalBase.build(px, w, h, LR, LG, LB) : null;
        final float lift = p.shadows;

        IntStream.range(0, h).parallel().forEach(new IntConsumer() { @Override public void accept(int y) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[row + x];
                float r = LR[(c >> 16) & 0xFF], g = LG[(c >> 8) & 0xFF], b = LB[c & 0xFF];
                float Y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                if (lb != null) {
                    // Dodge: brighten dark REGIONS by up to ~2 stops, following the edge-aware base
                    // layer so a subject lifts as a whole and bright surroundings are untouched.
                    float base = lb.base(x, y, Y);
                    float d = PIVOT - base;
                    if (d > 0) {
                        float gain = (float) Math.pow(2, Math.min(2.2f, lift * 1.1f * d));
                        r *= gain; g *= gain; b *= gain; Y *= gain;
                    }
                }
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

        if (p.clarity > 0.01f || p.sharpen > 0.01f || p.nrLuma >= NR_LUMA_MIN || p.nrChroma >= NR_CHROMA_MIN)
            detailPass(px, w, h, p);
    }

    private static int to8(float v) {
        int i = (int) (v * 255f + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** Clarity (large-radius, midtone-weighted local contrast) + noise-cored unsharp mask. */
    private static void detailPass(final int[] px, final int w, final int h, final Params p) {
        byte[] y0 = new byte[w * h];
        final byte[] Y0 = y0;
        IntStream.range(0, h).parallel().forEach(new IntConsumer() { @Override public void accept(int y) {
            for (int x = 0, i = y * w; x < w; x++, i++) Y0[i] = (byte) luma(px[i]);
        } });
        if (p.nrLuma >= NR_LUMA_MIN) {
            y0 = denoiseLuma(px, y0, w, h, p.nrLuma);
            // Heavy noise (typically a night shot we brightened): a second, gentler pass
            // removes what the first left, at about 60% of the remaining noise level.
            if (p.nrLuma >= 8f) y0 = denoiseLuma(px, y0, w, h, p.nrLuma * 0.6f);
        }
        if (p.nrChroma >= NR_CHROMA_MIN) denoiseChroma(px, w, h, p.nrChroma);
        final byte[] Y = y0;

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

    // ---------------------------------------------------------------- local tone mapping
    private static final float PIVOT = (float) (Math.log(0.18) / Math.log(2));   // mid grey, in stops

    /**
     * Edge-aware base layer of log2 luminance (fast guided filter, He et al.): computed on a
     * ~160 px proxy, then applied at full resolution as base = a * logY + b, which keeps sharp
     * edges between a dark subject and a bright sky, so lifting the subject causes no halo.
     */
    static final class LocalBase {
        final int ds, sw, sh;
        final float[] A, B;

        private LocalBase(int ds, int sw, int sh, float[] a, float[] b) { this.ds = ds; this.sw = sw; this.sh = sh; A = a; B = b; }

        static LocalBase build(int[] px, int w, int h, float[] LR, float[] LG, float[] LB) {
            int ds = Math.max(1, Math.min(w, h) / 160);
            int sw = (w + ds - 1) / ds, sh = (h + ds - 1) / ds;
            float[] I = new float[sw * sh];
            for (int sy = 0; sy < sh; sy++)
                for (int sx = 0; sx < sw; sx++) {
                    double sum = 0; int n = 0;
                    for (int y = sy * ds, ye = Math.min(h, y + ds); y < ye; y++)
                        for (int x = sx * ds, xe = Math.min(w, x + ds); x < xe; x++) {
                            int c = px[y * w + x];
                            sum += 0.2126f * LR[(c >> 16) & 0xFF] + 0.7152f * LG[(c >> 8) & 0xFF] + 0.0722f * LB[c & 0xFF];
                            n++;
                        }
                    I[sy * sw + sx] = log2f(sum / n);
                }
            int r = 4;
            float eps = 0.12f;                     // edges stronger than ~1/3 stop are kept
            float[] mI = I.clone(), mII = new float[I.length];
            for (int i = 0; i < I.length; i++) mII[i] = I[i] * I[i];
            boxBlur(mI, sw, sh, r);
            boxBlur(mII, sw, sh, r);
            float[] a = new float[I.length], b = new float[I.length];
            for (int i = 0; i < I.length; i++) {
                float var = Math.max(0, mII[i] - mI[i] * mI[i]);
                a[i] = var / (var + eps);
                b[i] = mI[i] - a[i] * mI[i];
            }
            boxBlur(a, sw, sh, r);
            boxBlur(b, sw, sh, r);
            return new LocalBase(ds, sw, sh, a, b);
        }

        float base(int x, int y, float lumLinear) {
            float fx = Math.min(sw - 1f, Math.max(0f, (x + 0.5f) / ds - 0.5f));
            float fy = Math.min(sh - 1f, Math.max(0f, (y + 0.5f) / ds - 0.5f));
            int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(sw - 1, x0 + 1), y1 = Math.min(sh - 1, y0 + 1);
            float tx = fx - x0, ty = fy - y0;
            float a = bil(A, x0, y0, x1, y1, tx, ty), b = bil(B, x0, y0, x1, y1, tx, ty);
            return a * log2f(lumLinear) + b;
        }

        private float bil(float[] m, int x0, int y0, int x1, int y1, float tx, float ty) {
            float t = m[y0 * sw + x0] + (m[y0 * sw + x1] - m[y0 * sw + x0]) * tx;
            float u = m[y1 * sw + x0] + (m[y1 * sw + x1] - m[y1 * sw + x0]) * tx;
            return t + (u - t) * ty;
        }
    }

    private static float log2f(double v) { return (float) (Math.log(Math.max(v, 1e-4)) / 0.6931471805599453); }

    // ---------------------------------------------------------------- noise reduction
    /** Sigma filter: averages the 5x5 neighbours within 2.5 sigma, so edges and texture stay. */
    private static byte[] denoiseLuma(final int[] px, final byte[] Y, final int w, final int h, float sigma) {
        final byte[] out = new byte[w * h];
        final int T = Math.round(2.5f * sigma);
        IntStream.range(0, h).parallel().forEach(new IntConsumer() { @Override public void accept(int y) {
            int y0 = Math.max(0, y - 2), y1 = Math.min(h - 1, y + 2);
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int c = Y[i] & 0xFF, sum = 0, n = 0;
                int x0 = Math.max(0, x - 2), x1 = Math.min(w - 1, x + 2);
                for (int yy = y0; yy <= y1; yy++) {
                    int rowi = yy * w;
                    for (int xx = x0; xx <= x1; xx++) {
                        int v = Y[rowi + xx] & 0xFF;
                        if (v - c <= T && c - v <= T) { sum += v; n++; }
                    }
                }
                int d = (sum + n / 2) / n;
                out[i] = (byte) d;
                int delta = d - c;
                if (delta != 0) {
                    int p = px[i];
                    px[i] = (p & 0xFF000000) | (clamp8(((p >> 16) & 0xFF) + delta) << 16)
                            | (clamp8(((p >> 8) & 0xFF) + delta) << 8) | clamp8((p & 0xFF) + delta);
                }
            }
        } });
        return out;
    }

    /**
     * Chroma speckle removal: colour (R-Y, B-Y) is smoothed at 1/4 resolution, but a pixel keeps
     * its own colour wherever it differs from the smooth version by more than the noise level,
     * which preserves real colour edges. Luma (and so detail) is unchanged.
     */
    private static void denoiseChroma(final int[] px, final int w, final int h, float sigma) {
        final int ds = 4, sw = (w + ds - 1) / ds, sh = (h + ds - 1) / ds;
        final float[] cr = new float[sw * sh], cb = new float[sw * sh];
        for (int sy = 0; sy < sh; sy++)
            for (int sx = 0; sx < sw; sx++) {
                float a = 0, b = 0; int n = 0;
                for (int y = sy * ds, ye = Math.min(h, y + ds); y < ye; y++)
                    for (int x = sx * ds, xe = Math.min(w, x + ds); x < xe; x++) {
                        int c = px[y * w + x];
                        int L = luma(c);
                        a += ((c >> 16) & 0xFF) - L; b += (c & 0xFF) - L; n++;
                    }
                cr[sy * sw + sx] = a / n; cb[sy * sw + sx] = b / n;
            }
        boxBlur(cr, sw, sh, 1);
        boxBlur(cb, sw, sh, 1);
        final float T = 3f * sigma;
        IntStream.range(0, h).parallel().forEach(new IntConsumer() { @Override public void accept(int y) {
            float fy = Math.min(sh - 1f, Math.max(0f, (y + 0.5f) / ds - 0.5f));
            int y0 = (int) fy, y1 = Math.min(sh - 1, y0 + 1);
            float ty = fy - y0;
            for (int x = 0; x < w; x++) {
                float fx = Math.min(sw - 1f, Math.max(0f, (x + 0.5f) / ds - 0.5f));
                int x0 = (int) fx, x1 = Math.min(sw - 1, x0 + 1);
                float tx = fx - x0;
                float lr = lerp2(cr, sw, x0, y0, x1, y1, tx, ty), lbv = lerp2(cb, sw, x0, y0, x1, y1, tx, ty);
                int i = y * w + x, c = px[i];
                int L = luma(c), R = (c >> 16) & 0xFF, B = c & 0xFF;
                float dr = (R - L) - lr, db = (B - L) - lbv;
                float kr = Math.min(1f, Math.abs(dr) / T), kb = Math.min(1f, Math.abs(db) / T);
                float nr = lr + dr * kr * kr, nb = lbv + db * kb * kb;
                int R2 = clamp8(Math.round(L + nr)), B2 = clamp8(Math.round(L + nb));
                int G2 = clamp8(Math.round((L * 256f - 54 * R2 - 19 * B2) / 183f));
                px[i] = (c & 0xFF000000) | (R2 << 16) | (G2 << 8) | B2;
            }
        } });
    }

    private static float lerp2(float[] m, int sw, int x0, int y0, int x1, int y1, float tx, float ty) {
        float t = m[y0 * sw + x0] + (m[y0 * sw + x1] - m[y0 * sw + x0]) * tx;
        float u = m[y1 * sw + x0] + (m[y1 * sw + x1] - m[y1 * sw + x0]) * tx;
        return t + (u - t) * ty;
    }

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
