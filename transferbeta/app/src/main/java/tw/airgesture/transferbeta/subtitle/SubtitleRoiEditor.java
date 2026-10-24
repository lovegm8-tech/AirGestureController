package tw.airgesture.transferbeta.subtitle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * A translucent, interactive rectangle shown OVER THE VIDEO.
 * Drag inside the box to move it; drag any corner to resize it.
 * The source rectangle is stored as normalized fractions so portrait and
 * landscape values can be saved independently.
 */
public final class SubtitleRoiEditor extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final RectF pendingFraction = new RectF();
    private float minWidth, minHeight, touchRadius;
    private int activeHandle = -2; // -2:none, -1:move, 0..3:corners
    private float downX, downY;
    private final RectF start = new RectF();

    public SubtitleRoiEditor(Context context, RectF initialNormalized) {
        super(context);
        minWidth = dp(88);
        minHeight = dp(32);
        touchRadius = dp(38);
        pendingFraction.set(initialNormalized == null
                ? new RectF(0.13f, 0.23f, 0.87f, 0.37f) : initialNormalized);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setContentDescription("字幕辨識框，框內拖曳移動，四角拖曳調整大小");
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        box.set(pendingFraction.left * w, pendingFraction.top * h,
                pendingFraction.right * w, pendingFraction.bottom * h);
        clamp();
        invalidate();
    }

    private void clamp() {
        float w = Math.max(minWidth, Math.min(getWidth(), box.width()));
        float h = Math.max(minHeight, Math.min(getHeight(), box.height()));
        float l = Math.max(0, Math.min(getWidth() - w, box.left));
        float t = Math.max(0, Math.min(getHeight() - h, box.top));
        box.set(l, t, l + w, t + h);
    }

    /** Four normalized fractions, always within screen bounds. */
    public RectF normalizedRect() {
        if (getWidth() <= 0 || getHeight() <= 0) return new RectF(pendingFraction);
        return new RectF(box.left / getWidth(), box.top / getHeight(),
                box.right / getWidth(), box.bottom / getHeight());
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        paint.setColor(Color.argb(145, 0, 0, 0));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, w, box.top, paint);
        canvas.drawRect(0, box.bottom, w, h, paint);
        canvas.drawRect(0, box.top, box.left, box.bottom, paint);
        canvas.drawRect(box.right, box.top, w, box.bottom, paint);
        paint.setColor(Color.rgb(47, 213, 159));
        paint.setStrokeWidth(dp(3));
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRoundRect(box, dp(8), dp(8), paint);
        paint.setStyle(Paint.Style.FILL);
        float r = dp(11);
        canvas.drawCircle(box.left, box.top, r, paint);
        canvas.drawCircle(box.right, box.top, r, paint);
        canvas.drawCircle(box.left, box.bottom, r, paint);
        canvas.drawCircle(box.right, box.bottom, r, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(14));
        paint.setFakeBoldText(true);
        String label = "字幕辨識範圍 · 拖曳移動 / 拉四角調整";
        float x = Math.max(dp(10), Math.min(w - paint.measureText(label) - dp(10),
                box.centerX() - paint.measureText(label) / 2));
        float y = box.bottom + dp(27);
        if (y > h - dp(70)) y = Math.max(dp(28), box.top - dp(14));
        canvas.drawText(label, x, y, paint);
    }

    private boolean closeTo(float x, float y, float cx, float cy) {
        return Math.abs(x - cx) <= touchRadius && Math.abs(y - cy) <= touchRadius;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = x; downY = y; start.set(box);
            if (closeTo(x, y, box.left, box.top)) activeHandle = 0;
            else if (closeTo(x, y, box.right, box.top)) activeHandle = 1;
            else if (closeTo(x, y, box.left, box.bottom)) activeHandle = 2;
            else if (closeTo(x, y, box.right, box.bottom)) activeHandle = 3;
            else activeHandle = -1;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = x - downX, dy = y - downY;
            if (activeHandle == -1) {
                float bw = start.width(), bh = start.height();
                float left = start.contains(downX, downY)
                        ? start.left + dx : x - bw / 2;
                float top = start.contains(downX, downY)
                        ? start.top + dy : y - bh / 2;
                box.set(left, top, left + bw, top + bh);
                clamp();
            } else if (activeHandle >= 0) {
                box.set(start);
                if (activeHandle == 0 || activeHandle == 2)
                    box.left = Math.min(box.right - minWidth, Math.max(0, start.left + dx));
                else
                    box.right = Math.max(box.left + minWidth,
                            Math.min(getWidth(), start.right + dx));
                if (activeHandle == 0 || activeHandle == 1)
                    box.top = Math.min(box.bottom - minHeight, Math.max(0, start.top + dy));
                else
                    box.bottom = Math.max(box.top + minHeight,
                            Math.min(getHeight(), start.bottom + dy));
            }
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pendingFraction.set(normalizedRect());
            activeHandle = -2;
            invalidate();
            return true;
        }
        return true;
    }
}
