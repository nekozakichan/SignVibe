package com.ucucite.signvibe.ui.quiz;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A tracing canvas for a single letter/number. Draws a large faint guide glyph
 * and lets the student draw strokes over it with a finger. Coverage of the
 * guide by the student's strokes can be queried to judge the trace.
 *
 * Judging is intentionally lenient (SPED-friendly): we measure roughly how much
 * of the guide glyph the student's strokes come near, not exact-path match.
 */
public class TracingView extends View {

    // How much of the view height the guide glyph fills. Used in BOTH the guide
    // paint and the coverage measurement — they must always match, so it lives
    // in one constant.
    private static final float GLYPH_HEIGHT_FRACTION = 0.9f;

    // The character being traced, e.g. "A" or "5".
    private String glyph = "";

    // Guide glyph paint (faint grey, filled text).
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // The student's stroke paint (bright teal).
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Student strokes are drawn onto this offscreen bitmap so we can measure
    // coverage pixel-by-pixel without the guide glyph interfering.
    @Nullable private Bitmap strokeBitmap;
    @Nullable private Canvas strokeCanvas;
    private final Path activePath = new Path();

    private float lastX, lastY;
    private boolean hasDrawnAnything = false;

    private static final float TOUCH_TOLERANCE = 4f;

    public TracingView(Context context) {
        super(context);
        init();
    }

    public TracingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        guidePaint.setColor(Color.parseColor("#D9DEDE"));  // faint grey guide
        guidePaint.setStyle(Paint.Style.FILL);
        guidePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        guidePaint.setTextAlign(Paint.Align.CENTER);

        strokePaint.setColor(Color.parseColor("#00838A"));  // teal, matches app
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeWidth(26f);
    }

    /** Sets the character to trace and clears any existing strokes. */
    public void setGlyph(@NonNull String glyph) {
        this.glyph = glyph;
        clear();
    }

    /** Wipes the student's strokes (e.g. a "Clear" button, or on new question). */
    public void clear() {
        if (strokeBitmap != null) {
            strokeBitmap.eraseColor(Color.TRANSPARENT);
        }
        activePath.reset();
        hasDrawnAnything = false;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            strokeBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            strokeCanvas = new Canvas(strokeBitmap);
            // Size the guide glyph to fill the view height.
            guidePaint.setTextSize(h * GLYPH_HEIGHT_FRACTION);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (glyph.isEmpty()) return;

        // Draw the faint guide glyph, vertically centered.
        Paint.FontMetrics fm = guidePaint.getFontMetrics();
        float centerX = getWidth() / 2f;
        float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(glyph, centerX, baseline, guidePaint);

        // Draw the student's committed strokes, then the in-progress one.
        if (strokeBitmap != null) {
            canvas.drawBitmap(strokeBitmap, 0, 0, null);
        }
        canvas.drawPath(activePath, strokePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activePath.reset();
                activePath.moveTo(x, y);
                lastX = x;
                lastY = y;
                hasDrawnAnything = true;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(x - lastX);
                float dy = Math.abs(y - lastY);
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    activePath.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2);
                    lastX = x;
                    lastY = y;
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                // Commit this stroke onto the offscreen bitmap.
                if (strokeCanvas != null) {
                    strokeCanvas.drawPath(activePath, strokePaint);
                }
                activePath.reset();
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** True once the student has drawn at least one stroke. */
    public boolean hasDrawn() {
        return hasDrawnAnything;
    }

    /**
     * Fraction (0..1) of the guide glyph covered by the student's strokes.
     * For each guide pixel, we count it as "covered" if any student stroke
     * pixel falls within a small radius — this tolerates the natural
     * imprecision of finger tracing instead of demanding exact overlap.
     */
    public float computeCoverage() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || strokeBitmap == null || glyph.isEmpty()) return 0f;

        // Render the guide glyph alone, at the SAME size/position as the visible one.
        Bitmap guideBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas guideCanvas = new Canvas(guideBmp);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.BLACK);
        fill.setStyle(Paint.Style.FILL);
        fill.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        fill.setTextAlign(Paint.Align.CENTER);
        fill.setTextSize(h * GLYPH_HEIGHT_FRACTION);
        Paint.FontMetrics fm = fill.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        guideCanvas.drawText(glyph, w / 2f, baseline, fill);

        // Pull both bitmaps into int arrays once (getPixel per-pixel is slow).
        int[] guidePx = new int[w * h];
        int[] strokePx = new int[w * h];
        guideBmp.getPixels(guidePx, 0, w, 0, 0, w, h);
        strokeBitmap.getPixels(strokePx, 0, w, 0, 0, w, h);
        guideBmp.recycle();

        int step = 4;               // sample density
        int radius = 18;            // "near" tolerance in px — forgiving of imprecision
        int guidePixels = 0;
        int coveredPixels = 0;

        for (int py = 0; py < h; py += step) {
            for (int px = 0; px < w; px += step) {
                int gi = py * w + px;
                if (Color.alpha(guidePx[gi]) <= 40) continue;  // not part of the glyph
                guidePixels++;

                // Is there a student stroke within `radius` of this guide pixel?
                boolean covered = false;
                for (int dy = -radius; dy <= radius && !covered; dy += step) {
                    int ny = py + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -radius; dx <= radius; dx += step) {
                        int nx = px + dx;
                        if (nx < 0 || nx >= w) continue;
                        if (Color.alpha(strokePx[ny * w + nx]) > 40) {
                            covered = true;
                            break;
                        }
                    }
                }
                if (covered) coveredPixels++;
            }
        }

        if (guidePixels == 0) return 0f;
        return coveredPixels / (float) guidePixels;
    }
}