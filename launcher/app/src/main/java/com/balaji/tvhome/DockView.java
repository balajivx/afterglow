package com.balaji.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The app dock: a tray of white at 7.5% over frosted glass, radius 26, 12 of
 * padding, every app in rows of six (config.xml "columns"), 16:9 tiles 12 apart. The tray grows with the
 * rows; the home screen moves up to show a lower row when focus goes there.
 */
final class DockView extends ViewGroup {

    static final class App {
        final String pkg, label;
        final android.content.Intent intent;
        final android.content.pm.ResolveInfo ri;
        int color = Colors.FALLBACK;

        App(String pkg, String label, android.content.Intent intent, android.content.pm.ResolveInfo ri) {
            this.pkg = pkg;
            this.label = label;
            this.intent = intent;
            this.ri = ri;
        }
    }

    /**
     * Brand colours for the apps on this TV. Every cover is drawn at one lightness from its colour (see
     * [Covers]), so a bright orange and a deep green carry the same weight. Apps not listed take the vivid
     * colour of their own icon.
     */
    static final Map<String, Integer> BRANDS = new HashMap<>();

    static {
        BRANDS.put("com.netflix.ninja", 0xFFE50914);
        BRANDS.put("com.amazon.amazonvideo.livingroom", 0xFF1A98FF);
        BRANDS.put("com.google.android.youtube.tv", 0xFFFF0033);
        BRANDS.put("org.smarttube.stable", 0xFFE0322B);
        BRANDS.put("in.startv.hotstar", 0xFF3A49F0);
        BRANDS.put("com.sonyliv", 0xFFE8A33D);
        BRANDS.put("com.graymatrix.did", 0xFF8230C6);
        BRANDS.put("ahaflix.tv", 0xFFFF6A13);
        BRANDS.put("com.android.vending", 0xFF01875F);
        BRANDS.put("com.cricfytv.sports", 0xFF16A34A);
        BRANDS.put("com.sony.dtv.tvx", 0xFF5B6CFF);
        BRANDS.put("com.yablio.sendfilestotv", 0xFF2F80ED);
        BRANDS.put("com.neilturner.aerialviews", 0xFF2BA6C8);
    }

    /** Drawn covers: the brand colour as a soft top-to-bottom gradient, the app's icon centred on it. */
    static final class Covers {
        static Bitmap draw(Context c, Drawable icon, int brand, int w, int h) {
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            fill.setShader(new LinearGradient(0, 0, 0, h, Colors.even(brand, 0.62), Colors.even(brand, 0.47), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, fill);
            // A faint light from the top edge, so the flat colour reads as a surface.
            Paint sheen = new Paint(Paint.DITHER_FLAG);
            sheen.setShader(new LinearGradient(0, 0, 0, h * 0.5f, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h * 0.5f, sheen);
            if (icon != null) {
                float side = Math.round(h * 0.52f);
                float left = (w - side) / 2f, top = (h - side) / 2f;
                RectF box = new RectF(left, top, left + side, top + side);
                Path clip = new Path();
                clip.addRoundRect(box, side * 0.24f, side * 0.24f, Path.Direction.CW);
                // A soft shadow under the icon, the same for every app.
                Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadow.setColor(0x33000000);
                RectF sh = new RectF(box);
                sh.offset(0, Ui.dp(c, 1.5f));
                canvas.drawRoundRect(sh, side * 0.24f, side * 0.24f, shadow);
                canvas.save();
                canvas.clipPath(clip);
                icon.setBounds(Math.round(box.left), Math.round(box.top), Math.round(box.right), Math.round(box.bottom));
                icon.draw(canvas);
                canvas.restore();
            }
            return out;
        }

        /** The vivid colour of an icon, for apps with no brand colour listed. */
        static int iconColor(Drawable icon) {
            if (icon == null) return Colors.FALLBACK;
            Bitmap b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 48, 48);
            icon.draw(new Canvas(b));
            int c = Colors.vivid(b);
            b.recycle();
            return c;
        }
    }

    /** An app in the dock: its drawn cover (radius 14) with a white 10% hairline just inside the edge. */
    static final class Tile extends ArtView {
        final App app;
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG), hairP = new Paint(Paint.ANTI_ALIAS_FLAG),
                dimP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF hair = new RectF();

        Tile(Context c, App app) {
            super(c);
            this.app = app;
            setContentDescription(app.label);
            fillP.setColor(Ui.FILL);
            hairP.setStyle(Paint.Style.STROKE);
            hairP.setStrokeWidth(Ui.dp(c, 1f));
            hairP.setColor(0x1AFFFFFF);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            placeArt(ringSpace, ringSpace, w - 2 * ringSpace, h - 2 * ringSpace, Ui.dp(getContext(), Ui.R_TILE));
            float half = Ui.dp(getContext(), 0.5f);
            hair.set(art);
            hair.inset(half, half);
        }

        @Override
        protected void drawEmptyArt(Canvas canvas, float focused) {
            canvas.drawRoundRect(art, radius, radius, fillP);
        }

        @Override
        protected void drawOverArt(Canvas canvas, float focused) {
            // The colour change: an unfocused cover sits a little darker; focus brings it to full colour.
            if (focused < 1f) {
                dimP.setColor(Ui.withAlpha(0xFF000000, 0.30f * (1f - focused)));
                canvas.drawRoundRect(art, radius, radius, dimP);
            }
            float r = radius - Ui.dp(getContext(), 0.5f);
            canvas.drawRoundRect(hair, r, r, hairP);
        }
    }

    final List<Tile> tiles = new ArrayList<>();
    private final int cols;
    private final float pad, gap, trayRadius;
    private final Paint trayP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tray = new RectF();
    private Tile lastFocused;
    /** Where Up goes from the first row. */
    java.util.function.Supplier<View> above;
    /** Told whenever a tile takes focus. */
    java.util.function.Consumer<Tile> onFocus;

    DockView(Context c, int cols) {
        super(c);
        this.cols = cols;
        pad = Ui.dp(c, Ui.S3);
        gap = Ui.dp(c, Ui.S3);
        trayRadius = Ui.dp(c, Ui.R_TRAY);
        trayP.setColor(Ui.BAND);
        setWillNotDraw(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
    }

    void setApps(List<App> apps, java.util.function.Consumer<App> open) {
        String focusedPkg = lastFocused != null ? lastFocused.app.pkg : null;
        removeAllViews();
        tiles.clear();
        lastFocused = null;
        for (App a : apps) {
            Tile t = new Tile(getContext(), a);
            t.onPress = () -> open.accept(a);
            t.glowColor = a.color;
            tiles.add(t);
            addView(t);
            if (a.pkg.equals(focusedPkg)) lastFocused = t;
        }
    }

    Tile lastFocused() {
        return lastFocused != null ? lastFocused : tiles.isEmpty() ? null : tiles.get(0);
    }

    private float tileWidth(int trayWidth) {
        return (trayWidth - 2 * pad - (cols - 1) * gap) / cols;
    }

    private float tileHeight(int trayWidth) {
        return tileWidth(trayWidth) * 9f / 16f;
    }

    int rows() {
        return Math.max(1, (tiles.size() + cols - 1) / cols);
    }

    /** The tray's height (px) for every row at [trayWidth]. */
    int trayHeight(int trayWidth) {
        int r = rows();
        return Math.round(2 * pad + r * tileHeight(trayWidth) + (r - 1) * gap);
    }

    /** The bottom (px, in this view) of the art in [row]. */
    float rowBottom(int row) {
        return pad + (row + 1) * tileHeight(getWidth()) + row * gap;
    }

    int rowOf(Tile t) {
        return tiles.indexOf(t) / cols;
    }

    /** The cover size (px) for a tray [trayWidth] wide. */
    int[] coverSize(int trayWidth) {
        return new int[]{Math.round(tileWidth(trayWidth)), Math.round(tileHeight(trayWidth))};
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, MeasureSpec.getSize(hSpec));
        float ring = tiles.isEmpty() ? 0 : 2 * tiles.get(0).ringSpace;
        for (Tile t : tiles) {
            t.measure(MeasureSpec.makeMeasureSpec(Math.round(tileWidth(w) + ring), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(tileHeight(w) + ring), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float tw = tileWidth(getWidth()), th = tileHeight(getWidth());
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            int x = Math.round(pad + (i % cols) * (tw + gap) - tile.ringSpace);
            int y = Math.round(pad + (i / cols) * (th + gap) - tile.ringSpace);
            tile.layout(x, y, x + tile.getMeasuredWidth(), y + tile.getMeasuredHeight());
        }
        tray.set(0, 0, getWidth(), getHeight());
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (child instanceof Tile) {
            lastFocused = (Tile) child;
            if (onFocus != null) onFocus.accept((Tile) child);
        }
    }

    /** Rows of [cols]: Left and Right stop at the row's ends, Up from the first row goes to the shelf. */
    @Override
    public View focusSearch(View focused, int direction) {
        int i = tiles.indexOf(focused);
        if (i >= 0) {
            int col = i % cols, n = tiles.size();
            switch (direction) {
                case FOCUS_LEFT:
                    return col > 0 ? tiles.get(i - 1) : focused;
                case FOCUS_RIGHT:
                    return col < cols - 1 && i + 1 < n ? tiles.get(i + 1) : focused;
                case FOCUS_DOWN:
                    if (i + cols < n) return tiles.get(i + cols);
                    return i / cols < (n - 1) / cols ? tiles.get(n - 1) : focused;
                case FOCUS_UP:
                    if (i >= cols) return tiles.get(i - cols);
                    View up = above != null ? above.get() : null;
                    return up != null ? up : focused;
            }
        }
        return super.focusSearch(focused, direction);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRoundRect(tray, trayRadius, trayRadius, trayP);
    }
}
