package com.balaji.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.View;

/**
 * A picture that takes focus quietly: a colour change and a fine
 * ring, 1 dp of soft white 2.5 dp outside the art, fading in over 200 ms. Nothing grows, lifts, casts a
 * shadow or moves, not even a dip on press.
 *
 * The view's bounds include [ringSpace] round the art, so the ring is never drawn outside them. The art
 * is a bitmap shader in a rounded rectangle, cropped to fill: one anti-aliased draw, no clipping.
 */
abstract class ArtView extends View {
    final float ringSpace;
    protected final RectF art = new RectF();
    protected float radius;
    protected final Ui.Tween ring = new Ui.Tween(0f);
    protected final Ui.Tween focus = new Ui.Tween(0f);

    private Bitmap bitmap;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();
    private final float ringOffset;

    /** What OK does. */
    Runnable onPress;
    /** The colour the glow takes while this is focused. */
    int glowColor = Colors.FALLBACK;

    ArtView(Context c) {
        super(c);
        ringSpace = Ui.dp(c, 3f);
        ringOffset = Ui.dp(c, 2.5f);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Ui.dp(c, 1f));
        setFocusable(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);
    }

    boolean hasArt() {
        return bitmap != null;
    }

    void setArt(Bitmap b) {
        if (b == bitmap) return;
        bitmap = b;
        bitmapPaint.setShader(b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        fitArt();
        invalidate();
    }

    protected void placeArt(float left, float top, float width, float height, float corner) {
        art.set(left, top, left + width, top + height);
        radius = corner;
        ringRect.set(art);
        ringRect.inset(-ringOffset, -ringOffset);
        fitArt();
    }

    private void fitArt() {
        if (bitmap == null || art.isEmpty()) return;
        float scale = Math.max(art.width() / bitmap.getWidth(), art.height() / bitmap.getHeight());
        shaderMatrix.setScale(scale, scale);
        shaderMatrix.postTranslate(art.left + (art.width() - bitmap.getWidth() * scale) / 2f,
                art.top + (art.height() - bitmap.getHeight() * scale) / 2f);
        bitmapPaint.getShader().setLocalMatrix(shaderMatrix);
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        if (!gain) pressed = false;
        ring.to(gain ? 1f : 0f, 200);
        focus.to(gain ? 1f : 0f, 240);
        invalidate();
    }

    /**
     * OK went down on this view. A press counts only when it both starts and ends here: OK on an app's
     * "Exit?" closes the app as the key goes down, and its release then lands on the Home screen, on the
     * tile that opened the app, which would open it straight back up.
     */
    private boolean pressed;

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isConfirm(keyCode)) {
            boolean click = pressed && !event.isCanceled();
            pressed = false;
            if (click) performClick();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isConfirm(keyCode)) {
            if (event.getRepeatCount() == 0) pressed = true;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (onPress != null) onPress.run();
        return true;
    }

    private static boolean isConfirm(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean moving = ring.step();
        moving |= focus.step();
        float r = ring.value;
        if (r > 0f) {
            ringPaint.setColor(Ui.withAlpha(Ui.FOCUS_RING, r));
            canvas.drawRoundRect(ringRect, radius + ringOffset, radius + ringOffset, ringPaint);
        }
        if (bitmap != null) canvas.drawRoundRect(art, radius, radius, bitmapPaint);
        else drawEmptyArt(canvas, focus.value);
        drawOverArt(canvas, focus.value);
        drawUnderArt(canvas, focus.value);
        if (moving) postInvalidateOnAnimation();
    }

    protected abstract void drawEmptyArt(Canvas canvas, float focused);

    protected void drawOverArt(Canvas canvas, float focused) {
    }

    protected void drawUnderArt(Canvas canvas, float focused) {
    }
}
