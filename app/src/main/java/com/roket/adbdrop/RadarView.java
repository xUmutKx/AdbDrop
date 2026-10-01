package com.roket.adbdrop;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import com.google.android.material.color.MaterialColors;

/** Quick-Share style expanding ripples. */
public class RadarView extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
    float t; int color; boolean fast;

    public RadarView(Context c, AttributeSet s) {
        super(c, s);
        color = MaterialColors.getColor(c, com.google.android.material.R.attr.colorPrimary, Color.GREEN);
        a.setDuration(2600); a.setRepeatCount(ValueAnimator.INFINITE); a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidate(); });
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); a.start(); }
    @Override protected void onDetachedFromWindow() { a.cancel(); super.onDetachedFromWindow(); }
    public void setFast(boolean f) { if (f != fast) { fast = f; a.setDuration(f ? 1200 : 2600); } }

    @Override protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, max = Math.min(cx, cy), core = max * 0.30f;
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float f = (t + i / 3f) % 1f, r = core + (max - core) * f;
            p.setColor(color); p.setAlpha((int) (95 * (1 - f)));
            c.drawCircle(cx, cy, r, p);
        }
    }
}
