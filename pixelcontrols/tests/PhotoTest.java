import com.pixelcontrols.photo.PhotoEnhancer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.function.UnaryOperator;

public class PhotoTest {
    static int fails = 0, passes = 0;
    static void check(boolean ok, String what) { if (ok) passes++; else { fails++; System.out.println("FAIL: " + what); } }

    static double lin(int v) { double g = v / 255.0; return g <= 0.04045 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4); }
    static int gam(double l) { l = Math.max(0, Math.min(1, l)); double g = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055; return (int) Math.round(g * 255); }

    /** Per-channel transform in linear light (simulates camera exposure / white balance errors). */
    static int[] linearOp(int[] src, double kr, double kg, double kb) {
        int[] o = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            int c = src[i];
            o[i] = 0xFF000000 | gam(lin((c >> 16) & 255) * kr) << 16 | gam(lin((c >> 8) & 255) * kg) << 8 | gam(lin(c & 255) * kb);
        }
        return o;
    }

    static int[] flat(int[] src) {   // haze / low contrast: compress toward mid grey in gamma space
        int[] o = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            int c = src[i];
            int r = (int) (((c >> 16) & 255) * 0.55 + 70), g = (int) (((c >> 8) & 255) * 0.55 + 70), b = (int) ((c & 255) * 0.55 + 70);
            o[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        return o;
    }

    // ---- CIELAB distance (perceptual error vs the pristine original)
    static double[] lab(int c) {
        double r = lin((c >> 16) & 255), g = lin((c >> 8) & 255), b = lin(c & 255);
        double X = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047, Y = 0.2126 * r + 0.7152 * g + 0.0722 * b, Z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(X), fy = f(Y), fz = f(Z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }
    static double f(double t) { return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116.0; }
    static int W;
    static int[] pool(int[] a) {
        int w = W, h = a.length / w, pw = w / 4, ph = h / 4; int[] o = new int[pw * ph];
        for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
            int r = 0, g = 0, b = 0;
            for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) { int c = a[(y * 4 + j) * w + x * 4 + i]; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; }
            o[y * pw + x] = 0xFF000000 | (r / 16) << 16 | (g / 16) << 8 | (b / 16);
        }
        return o;
    }
    static double deltaE(int[] a0, int[] b0) {
        int[] a = pool(a0), b = pool(b0);
        double s = 0;
        for (int i = 0; i < a.length; i++) { double[] x = lab(a[i]), y = lab(b[i]); s += Math.sqrt(sq(x[0] - y[0]) + sq(x[1] - y[1]) + sq(x[2] - y[2])); }
        return s / a.length;
    }
    static double sq(double v) { return v * v; }

    static double medianLuma(int[] p) {
        int[] h = new int[256];
        for (int c : p) h[(54 * ((c >> 16) & 255) + 183 * ((c >> 8) & 255) + 19 * (c & 255)) >> 8]++;
        int acc = 0; for (int i = 0; i < 256; i++) { acc += h[i]; if (acc >= p.length / 2) return i; } return 255;
    }
    static double clipFrac(int[] p) { int n = 0; for (int c : p) if (((c >> 16) & 255) >= 254 && ((c >> 8) & 255) >= 254 && (c & 255) >= 254) n++; return n / (double) p.length; }

    static int[] enhance(int[] src, int w, int h, StringBuilder log) {
        int[] p = src.clone();
        PhotoEnhancer.Params prm = PhotoEnhancer.analyze(p, w, h);
        PhotoEnhancer.apply(p, w, h, prm);
        log.append(prm.describe());
        return p;
    }

    public static void main(String[] args) throws Exception {
        String dir = args[0];
        String[] names = {"building.jpg", "fruits.jpg", "home.jpg", "baboon.jpg", "aloeL.jpg", "graf1.png"};
        Map<String, UnaryOperator<int[]>> degr = new LinkedHashMap<>();
        degr.put("under -1.5EV", s -> linearOp(s, 0.35, 0.35, 0.35));
        degr.put("over +1EV", s -> linearOp(s, 2.0, 2.0, 2.0));
        degr.put("blue cast (tungsten WB)", s -> linearOp(s, 0.78, 1.0, 1.30));
        degr.put("warm cast (shade WB)", s -> linearOp(s, 1.25, 1.0, 0.75));
        degr.put("flat / hazy", PhotoTest::flat);

        List<BufferedImage> sheetRows = new ArrayList<>();
        double improvedSum = 0; int improvedN = 0, total = 0;
        System.out.printf("%-12s %-26s %7s %7s  %s%n", "image", "degradation", "ΔE deg", "ΔE fix", "chosen settings");
        for (String n : names) {
            BufferedImage im = ImageIO.read(new File(dir, n));
            int w = im.getWidth(), h = im.getHeight(); W = w;
            int[] orig = im.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < orig.length; i++) orig[i] |= 0xFF000000;

            // Do-no-harm on the good original
            StringBuilder lg = new StringBuilder();
            int[] eo = enhance(orig, w, h, lg);
            double harm = deltaE(eo, orig);
            System.out.printf("%-12s %-26s %7s %7.2f  %s%n", n, "(original, untouched)", "-", harm, lg);
            check(harm < 8, n + " do-no-harm ΔE=" + harm);

            List<int[]> row = new ArrayList<>();
            for (Map.Entry<String, UnaryOperator<int[]>> d : degr.entrySet()) {
                int[] bad = d.getValue().apply(orig);
                StringBuilder log = new StringBuilder();
                int[] fixed = enhance(bad, w, h, log);
                double eBad = deltaE(bad, orig), eFix = deltaE(fixed, orig);
                System.out.printf("%-12s %-26s %7.2f %7.2f  %s%n", n, d.getKey(), eBad, eFix, log);
                total++;
                boolean better = eFix < eBad;
                if (better) improvedN++;
                improvedSum += (eBad - eFix) / eBad;
                check(eFix < eBad + 1.0, n + " / " + d.getKey() + " must not make it worse");
                if (n.equals("building.jpg") || n.equals("fruits.jpg") || n.equals("aloeL.jpg")) { row.add(bad); row.add(fixed); }
                // sanity
                for (int c : fixed) if ((c >>> 24) != 0xFF) { check(false, "alpha preserved"); break; }
                check(clipFrac(fixed) <= Math.max(0.02, clipFrac(bad) + 0.03), n + "/" + d.getKey() + " no new blown highlights (" + clipFrac(fixed) + ")");
            }
            if (!row.isEmpty()) sheetRows.add(sheet(row, w, h, n));
        }
        System.out.printf("improved %d/%d cases, mean error reduction %.0f%%%n", improvedN, total, 100 * improvedSum / total);
        check(improvedN >= total * 0.8, "at least 80% of degraded photos measurably improved");

        // Strength 0 must be an identity (except detail pass rounding) -> exactly identity expected
        BufferedImage im = ImageIO.read(new File(dir, "fruits.jpg"));
        int w = im.getWidth(), h = im.getHeight();
        int[] o = im.getRGB(0, 0, w, h, null, 0, w), p = o.clone();
        PhotoEnhancer.apply(p, w, h, PhotoEnhancer.analyze(p, w, h).scaled(0f));
        int maxd = 0; for (int i = 0; i < o.length; i++) for (int s = 0; s < 24; s += 8) maxd = Math.max(maxd, Math.abs(((o[i] >> s) & 255) - ((p[i] >> s) & 255)));
        check(maxd <= 1, "strength 0 = identity (max diff " + maxd + ")");

        // Edge cases: solid black, solid white, 1x1, thin strip, noise
        for (int[] dims : new int[][]{{1, 1}, {3, 1}, {1, 500}, {64, 64}}) {
            for (int fill : new int[]{0xFF000000, 0xFFFFFFFF, 0xFF808080}) {
                int[] t = new int[dims[0] * dims[1]]; Arrays.fill(t, fill);
                PhotoEnhancer.apply(t, dims[0], dims[1], PhotoEnhancer.analyze(t, dims[0], dims[1]));
                check(true, "edge ok");
            }
        }
        int[] blk = new int[100 * 100]; Arrays.fill(blk, 0xFF000000);
        PhotoEnhancer.apply(blk, 100, 100, PhotoEnhancer.analyze(blk, 100, 100));
        check(blk[5000] == 0xFF000000, "pure black stays black: " + Integer.toHexString(blk[5000]));
        int[] wht = new int[100 * 100]; Arrays.fill(wht, 0xFFFFFFFF);
        PhotoEnhancer.apply(wht, 100, 100, PhotoEnhancer.analyze(wht, 100, 100));
        check((wht[5000] & 0xFF) >= 245, "pure white stays white-ish: " + Integer.toHexString(wht[5000]));

        // Performance at phone resolution (12.5 MP, like a Pixel main camera shot)
        BufferedImage big = new BufferedImage(4080, 3072, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = big.createGraphics();
        g2.drawImage(ImageIO.read(new File(dir, "aloeL.jpg")), 0, 0, 4080, 3072, null); g2.dispose();
        int[] bp = big.getRGB(0, 0, 4080, 3072, null, 0, 4080);
        long t0 = System.nanoTime();
        PhotoEnhancer.Params prm = PhotoEnhancer.analyze(bp, 4080, 3072);
        long t1 = System.nanoTime();
        PhotoEnhancer.apply(bp, 4080, 3072, prm);
        long t2 = System.nanoTime();
        System.out.printf("12.5MP: analyze %d ms, apply %d ms on %d cores%n", (t1 - t0) / 1000000, (t2 - t1) / 1000000, Runtime.getRuntime().availableProcessors());

        // Contact sheet for visual review
        int sw = sheetRows.get(0).getWidth(), shh = 0;
        for (BufferedImage r : sheetRows) shh += r.getHeight();
        BufferedImage out = new BufferedImage(sw, shh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics(); int y = 0;
        for (BufferedImage r : sheetRows) { g.drawImage(r, 0, y, null); y += r.getHeight(); }
        g.dispose();
        ImageIO.write(out, "jpg", new File("/home/claude/tests/contact_sheet.jpg"));
        System.out.println("photo: " + passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    static BufferedImage sheet(List<int[]> imgs, int w, int h, String title) {
        int tw = 220, th = Math.round(h * tw / (float) w);
        BufferedImage row = new BufferedImage(tw * imgs.size(), th + 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = row.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        String[] lbl = {"under", "fixed", "over", "fixed", "blue WB", "fixed", "warm WB", "fixed", "flat", "fixed"};
        for (int i = 0; i < imgs.size(); i++) {
            BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            b.setRGB(0, 0, w, h, imgs.get(i), 0, w);
            g.drawImage(b, i * tw, 16, tw, th, null);
            g.setColor(Color.WHITE);
            g.drawString(title + " " + lbl[i], i * tw + 4, 12);
        }
        g.dispose();
        return row;
    }
}
