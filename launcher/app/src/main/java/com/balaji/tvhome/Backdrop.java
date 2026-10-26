package com.balaji.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * What sits behind the cards: a night sky in navy with aurora ribbons swaying slowly and faint twinkling
 * stars, the focused title's backdrop top right, a calm dark ground under the dock, and the glow (one
 * soft light below the dock). One [Scene] draws it all; [SceneView] shows it
 * full screen and [Glass] shows a blurred copy of the part behind a card, so the cards read as frosted
 * glass.
 *
 * Effects level (config.xml "effects"): 2 full (60 fps drift), 1 reduced (the drift steps 8 times a
 * second, the glow still eases smoothly), 0 off (still background, no blur).
 */
final class Backdrop {
    private Backdrop() {
    }

    /**
     * The scene draws at half resolution and the frosted glass at a quarter, each scaled back up: both are
     * soft gradients and blur, so it looks the same, and this TV's GPU has a quarter (and a sixteenth) of
     * the pixels to fill. Measured on the Bravia: full resolution missed most frames while the glow eased.
     */
    static final float SCENE_SCALE = 2f, GLASS_SCALE = 4f;

    /** Measures [v] at 1/[scale] of what its parent offers and scales it back up from its top left. */
    private static int[] shrink(View v, int wSpec, int hSpec, float scale) {
        v.setPivotX(0);
        v.setPivotY(0);
        v.setScaleX(scale);
        v.setScaleY(scale);
        return new int[]{(int) Math.ceil(View.MeasureSpec.getSize(wSpec) / scale),
                (int) Math.ceil(View.MeasureSpec.getSize(hSpec) / scale)};
    }

    static final class Scene {
        final int level;
        private final long born = Ui.now();
        private float w, h, density = 2f;

        private final Paint basePaint = new Paint(Paint.DITHER_FLAG);
        private final Paint fadePaint = new Paint(Paint.DITHER_FLAG);

        // Aurora ribbons: long, thin ellipses of light that sway, tilt and breathe, each on its own loop.
        // x, y (fraction of screen), half-length (fraction of width), half-thickness (fraction of height),
        // tilt (degrees), alpha, period (s), phase.
        private final float[][] ribbons = {
                {0.26f, 0.13f, 0.62f, 0.095f, -5f, 0.62f, 61f, 0.0f},
                {0.58f, 0.24f, 0.58f, 0.105f, -7f, 0.50f, 77f, 1.7f},
                {0.82f, 0.30f, 0.42f, 0.095f, -10f, 0.55f, 89f, 3.1f},
                {0.12f, 0.46f, 0.50f, 0.150f, 2f, 0.38f, 97f, 4.4f},
                {0.96f, 0.07f, 0.34f, 0.080f, -3f, 0.32f, 71f, 5.3f},
        };
        private final int[] ribbonColors = {
                Colors.oklch(0.74, 0.13, 172),
                Colors.oklch(0.60, 0.14, 236),
                Colors.oklch(0.54, 0.19, 298),
                Colors.oklch(0.42, 0.09, 222),
                Colors.oklch(0.52, 0.17, 345),
        };
        private final Paint[] ribbonPaints = new Paint[ribbons.length];
        private final Matrix m = new Matrix();
        private final RectF bounds = new RectF();

        // Stars: fixed points in the upper sky, a few twinkling slowly.
        private final float[] stars = new float[80 * 5]; // x, y, radius, alpha, period
        private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // The hero: the focused title's backdrop, top right, faded into the sky; cross-fades on change.
        private Bitmap hero, heroOld;
        private final Ui.Tween heroIn = new Ui.Tween(0f);
        private final Paint heroPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF heroRect = new RectF();

        // The glow: a deep core and a darker mid tone in one wide ellipse centred below the screen.
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Matrix glowMatrix = new Matrix();
        private final RectF glowRect = new RectF();
        private final Ui.Tween glowOpacity = new Ui.Tween(0f);
        private int coreFrom, coreTo, core, midFrom, midTo, mid, builtCore = 1, builtMid = 1;
        private long colorStart;
        private boolean colorEasing, hasGlow;
        private int glowGoal;

        Scene(int level) {
            this.level = level;
            java.util.Random rnd = new java.util.Random(11);
            for (int i = 0; i < stars.length; i += 5) {
                stars[i] = rnd.nextFloat();
                float y = rnd.nextFloat();
                stars[i + 1] = y * y * 0.62f; // denser near the top
                stars[i + 2] = 0.5f + rnd.nextFloat() * 0.8f;
                stars[i + 3] = 0.12f + rnd.nextFloat() * 0.38f;
                stars[i + 4] = rnd.nextFloat() < 0.4f ? 3f + rnd.nextFloat() * 5f : 0f;
            }
            starPaint.setColor(0xFFFFFFFF);
            for (int i = 0; i < ribbonPaints.length; i++) ribbonPaints[i] = new Paint(Paint.DITHER_FLAG);
        }

        void setSize(float width, float height, float density) {
            if (width == w && height == h) return;
            w = width;
            h = height;
            this.density = density;
            basePaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF0A0F1F, 0xFF070A14, 0xFF030409},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            // The lower part fades to near black so the dock and its glow sit on a calm ground.
            fadePaint.setShader(new LinearGradient(0, h * 0.50f, 0, h, 0x00030409, 0xF2030409, Shader.TileMode.CLAMP));
            for (int i = 0; i < ribbons.length; i++) {
                int c = ribbonColors[i];
                float a = ribbons[i][5];
                // A unit circle's gradient; the matrix stretches it into the ribbon.
                ribbonPaints[i].setShader(new RadialGradient(0, 0, 1f,
                        new int[]{Ui.withAlpha(c, a), Ui.withAlpha(c, a * 0.42f), c & 0x00FFFFFF},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            }
            heroRect.set(w * 0.30f, 0, w, h * 0.74f);
            // radial-gradient(115% 74% at 50% 112%, core, mid 44%, transparent 80%)
            float cx = w * 0.5f, cy = h * 1.12f, rx = w * 1.15f, ry = h * 0.74f;
            glowMatrix.setScale(1f, ry / rx, cx, cy);
            glowRect.set(cx - rx * 0.8f, cy - ry * 0.8f, cx + rx * 0.8f, cy + ry * 0.8f);
            glowRect.intersect(0, 0, w, h);
            builtCore = builtMid = 1;
        }

        /** The backdrop shown top right (null: none); an already masked picture from [heroMask]. */
        void hero(Bitmap b) {
            if (b == hero) return;
            heroOld = heroIn.value > 0.01f ? hero : null;
            hero = b;
            heroIn.snap(0f);
            heroIn.to(1f, 700);
        }

        /** Glow in the colour of [color] (an app's colour), fading in if it was hidden. */
        void glow(int color) {
            if (hasGlow && color == glowGoal) return;
            double[] lch = Colors.lch(color);
            double hue = lch[2] > 75 && lch[2] < 120 ? 68.0 : lch[2];
            double chroma = lch[1] < 0.03 ? 0.0 : Math.min(lch[1], 0.16);
            double deep = chroma > 0 ? Math.min(chroma * 1.15, 0.18) : 0.0;
            int c = Ui.withAlpha(Colors.oklch(0.50, deep, hue), 0.6f);
            int mm = Ui.withAlpha(Colors.oklch(0.40, deep * 0.9, hue), 0.26f);
            if (!hasGlow) {
                core = coreTo = c;
                mid = midTo = mm;
                colorEasing = false;
            } else {
                coreFrom = core;
                midFrom = mid;
                coreTo = c;
                midTo = mm;
                colorStart = Ui.now();
                colorEasing = true;
            }
            hasGlow = true;
            glowGoal = color;
            glowOpacity.to(1f, 600);
        }

        /** True while something eases, so the views should draw every frame. */
        boolean busy() {
            return colorEasing || glowOpacity.value != glowOpacity.target || heroIn.value != heroIn.target;
        }

        /** Advances the eases; call once per frame before drawing. */
        void step() {
            glowOpacity.step();
            if (heroIn.step() || heroIn.value >= 1f) {
                if (heroIn.value >= 1f) heroOld = null;
            }
            if (colorEasing) {
                float p = Math.min(1f, (Ui.now() - colorStart) / 700f);
                float e = Ui.EASE_OUT.getInterpolation(p);
                core = Ui.mix(coreFrom, coreTo, e);
                mid = Ui.mix(midFrom, midTo, e);
                if (p >= 1f) colorEasing = false;
            }
        }

        void draw(Canvas canvas) {
            float t = level >= 1 ? (Ui.now() - born) / 1000f : 0f;
            canvas.drawRect(0, 0, w, h, basePaint);
            for (int i = 0; i < ribbons.length; i++) {
                float[] r = ribbons[i];
                double ph = 2 * Math.PI * t / r[6] + r[7];
                float x = (r[0] + 0.05f * (float) Math.sin(ph)) * w;
                float y = (r[1] + 0.025f * (float) Math.cos(ph * 0.7)) * h;
                float len = r[2] * w * (1f + 0.06f * (float) Math.sin(ph * 1.3));
                float thick = r[3] * h * (1f + 0.25f * (float) Math.sin(ph * 1.9 + 1));
                m.setScale(len, thick);
                m.postRotate(r[4] + 3f * (float) Math.sin(ph * 0.5));
                m.postTranslate(x, y);
                ribbonPaints[i].getShader().setLocalMatrix(m);
                // Only where the ribbon can be lit: its stretched circle's box, on screen.
                bounds.set(-1, -1, 1, 1);
                m.mapRect(bounds);
                if (bounds.intersect(0, 0, w, h * 0.8f)) canvas.drawRect(bounds, ribbonPaints[i]);
            }
            for (int i = 0; i < stars.length; i += 5) {
                float a = stars[i + 3];
                if (stars[i + 4] > 0) a *= 0.6f + 0.4f * (float) Math.sin(2 * Math.PI * t / stars[i + 4] + i);
                starPaint.setAlpha(Math.round(255 * a));
                canvas.drawCircle(stars[i] * w, stars[i + 1] * h, stars[i + 2] * density * 0.75f, starPaint);
            }
            drawHero(canvas, t);
            canvas.drawRect(0, h * 0.5f, w, h, fadePaint);
            float o = glowOpacity.value;
            if (o > 0f && hasGlow && !glowRect.isEmpty()) {
                if (core != builtCore || mid != builtMid) {
                    builtCore = core;
                    builtMid = mid;
                    RadialGradient g = new RadialGradient(w * 0.5f, h * 1.12f, w * 1.15f,
                            new int[]{core, mid, mid & 0x00FFFFFF}, new float[]{0f, 0.44f, 0.80f}, Shader.TileMode.CLAMP);
                    g.setLocalMatrix(glowMatrix);
                    glowPaint.setShader(g);
                }
                glowPaint.setAlpha(Math.round(o * 255));
                canvas.drawRect(glowRect, glowPaint);
            }
        }

        /** The backdrop, slowly drifting larger and back (2% over 40 s), cross-faded from the last one. */
        private void drawHero(Canvas canvas, float t) {
            if (hero == null && heroOld == null) return;
            float zoom = 1.02f + 0.02f * (float) Math.sin(2 * Math.PI * t / 40f);
            canvas.save();
            canvas.scale(zoom, zoom, heroRect.right, heroRect.top);
            float in = heroIn.value;
            if (heroOld != null && in < 1f) {
                heroPaint.setAlpha(Math.round(255 * HERO_ALPHA * (1f - in)));
                canvas.drawBitmap(heroOld, null, heroRect, heroPaint);
            }
            if (hero != null) {
                heroPaint.setAlpha(Math.round(255 * HERO_ALPHA * in));
                canvas.drawBitmap(hero, null, heroRect, heroPaint);
            }
            canvas.restore();
        }

        private static final float HERO_ALPHA = 0.40f;

        /** Bakes the hero's fade into [src]: clear on the left and at the bottom, so it melts into the sky. */
        static Bitmap heroMask(Bitmap src) {
            if (src == null) return null;
            int bw = src.getWidth(), bh = src.getHeight();
            Bitmap out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            c.drawBitmap(src, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            Paint mask = new Paint(Paint.DITHER_FLAG);
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            mask.setShader(new LinearGradient(0, 0, bw * 0.55f, 0, new int[]{0x00000000, 0x80000000, 0xFF000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, mask);
            mask.setShader(new LinearGradient(0, bh * 0.15f, 0, bh * 0.85f, new int[]{0xFF000000, 0x55000000, 0x00000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, mask);
            return out;
        }
    }

    /** The scene, full screen; it runs the frame loop for itself and every [Glass]. */
    static final class SceneView extends View {
        final Scene scene;
        private final List<Glass> glasses = new ArrayList<>();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean running;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                frame();
                if (running && scene.level == 1) handler.postDelayed(this, 125);
            }
        };

        SceneView(Context c, Scene scene) {
            super(c);
            this.scene = scene;
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void add(Glass g) {
            glasses.add(g);
        }

        /** Drift only while the home screen is in front. */
        void setRunning(boolean run) {
            if (run == running) return;
            running = run;
            handler.removeCallbacks(tick);
            if (run && scene.level == 1) handler.post(tick);
            if (run) frame();
        }

        void glow(int color) {
            scene.glow(color);
            frame();
        }

        /** Redraws the scene and every glass (also while the page moves). */
        void refresh() {
            frame();
        }

        private void frame() {
            invalidate();
            for (Glass g : glasses) g.invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int[] size = shrink(this, wSpec, hSpec, SCENE_SCALE);
            setMeasuredDimension(size[0], size[1]);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            scene.setSize(w, h, getResources().getDisplayMetrics().density / SCENE_SCALE);
        }

        void hero(Bitmap b) {
            scene.hero(b);
            refresh();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            scene.step();
            scene.draw(canvas);
            if ((running && scene.level == 2) || scene.busy()) postInvalidateOnAnimation();
            if (scene.busy() || (running && scene.level == 2)) {
                for (Glass g : glasses) g.invalidate();
            }
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

    /**
     * A blurred copy of the scene behind a card, cut to the card's rounded shape. RenderEffect blur on
     * Android 12 and later, at a quarter of the card's resolution; before that, a pre-blurred image (the
     * scene drawn at 1/16 size and scaled up with filtering). The card draws its own tint on top.
     */
    static final class Glass extends View {
        private final Scene scene;
        private final View sceneView;
        private final float corner;
        private final boolean renderEffect;
        private final float unit; // screen px per unit of this view's canvas
        private final int[] mine = new int[2], theirs = new int[2];
        private Bitmap small;
        private Canvas smallCanvas;
        private final Paint smallPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Rect dst = new Rect();

        Glass(Context c, SceneView sceneView, float cornerPx, float blurDp) {
            super(c);
            this.scene = sceneView.scene;
            this.sceneView = sceneView;
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            renderEffect = scene.level > 0 && Build.VERSION.SDK_INT >= 31;
            unit = renderEffect ? GLASS_SCALE : 1f;
            corner = cornerPx / unit;
            if (renderEffect) {
                float r = Ui.dp(c, blurDp) / unit;
                setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            }
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), corner);
                }
            });
            setClipToOutline(true);
            sceneView.add(this);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            if (!renderEffect) {
                super.onMeasure(wSpec, hSpec);
                return;
            }
            int[] size = shrink(this, wSpec, hSpec, GLASS_SCALE);
            setMeasuredDimension(size[0], size[1]);
        }

        /** Draws the scene so that it lines up with the scene on screen; [canvas] is in this view's units. */
        private void drawScene(Canvas canvas) {
            getLocationInWindow(mine);
            sceneView.getLocationInWindow(theirs);
            canvas.save();
            canvas.scale(SCENE_SCALE / unit, SCENE_SCALE / unit);
            canvas.translate((theirs[0] - mine[0]) / SCENE_SCALE, (theirs[1] - mine[1]) / SCENE_SCALE);
            scene.draw(canvas);
            canvas.restore();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (scene.level == 0) return; // no blur: the card's tint sits straight on the scene
            if (renderEffect) {
                drawScene(canvas);
                return;
            }
            // Pre-blurred: the scene at 1/16 size, scaled back up.
            int sw = Math.max(1, getWidth() / 16), sh = Math.max(1, getHeight() / 16);
            if (small == null || small.getWidth() != sw || small.getHeight() != sh) {
                small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                smallCanvas = new Canvas(small);
            }
            smallCanvas.save();
            smallCanvas.scale(sw / (float) getWidth(), sh / (float) getHeight());
            drawScene(smallCanvas);
            smallCanvas.restore();
            dst.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(small, null, dst, smallPaint);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }
}
