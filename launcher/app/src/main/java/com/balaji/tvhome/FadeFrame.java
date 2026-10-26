package com.balaji.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * The page: its children fade out over the last 44 dp above the screen's bottom edge, so a dock row
 * below the screen shows as a soft hint of more instead of a hard cut, and the scene (with the glow) shows
 * through. The fade stays on the screen's edge while the page moves.
 */
public class FadeFrame extends FrameLayout {
    private final Paint fade = new Paint(Paint.DITHER_FLAG);
    private final float fadeHeight;
    private float builtFor = -1;

    public FadeFrame(Context c, AttributeSet attrs) {
        super(c, attrs);
        fadeHeight = Ui.dp(c, 44f);
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    @Override
    public void setTranslationY(float y) {
        super.setTranslationY(y);
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        float bottom = getHeight() - getTranslationY(), top = bottom - fadeHeight;
        if (bottom != builtFor) {
            builtFor = bottom;
            fade.setShader(new LinearGradient(0, top, 0, bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        }
        int layer = canvas.saveLayer(0, top, getWidth(), bottom, null);
        super.dispatchDraw(canvas);
        canvas.drawRect(0, top, getWidth(), bottom, fade);
        canvas.restoreToCount(layer);
        // Above the fade, draw normally (the layer only covers the strip).
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), top);
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
