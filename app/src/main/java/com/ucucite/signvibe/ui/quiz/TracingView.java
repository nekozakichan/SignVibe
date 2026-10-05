package com.ucucite.signvibe.ui.quiz;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A tracing canvas for a single letter/number. Draws a large faint guide glyph
 * with numbered stroke-order arrows on top, and lets the student draw strokes
 * over it with a finger. Coverage of the guide by the student's strokes can be
 * queried to judge the trace.
 *
 * Judging is intentionally lenient (SPED-friendly): we measure roughly how much
 * of the guide glyph the student's strokes come near, not exact-path match.
 * The arrows are a visual guide only — they never affect the score.
 */
public class TracingView extends View {

    // How much of the view height the guide glyph fills. Used in BOTH the guide
    // paint and the coverage measurement — they must always match, so it lives
    // in one constant.
    private static final float GLYPH_HEIGHT_FRACTION = 0.9f;

    // Stroke-order arrow colours, by stroke number: 1 orange, 2 purple, 3 blue, 4 green.
    // Kept away from the teal student ink so the two never get confused.
    private static final int[] GUIDE_COLORS = {
            0xFFF4511E, 0xFF8E24AA, 0xFF1E88E5, 0xFF43A047
    };

    // The character being traced, e.g. "A" or "5".
    private String glyph = "";

    // Guide glyph paint (faint grey, filled text).
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // The student's stroke paint (bright teal).
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Stroke-order arrows.
    private final Paint arrowLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowHeadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<GuideStroke> guideStrokes = new ArrayList<>();
    private boolean showStrokeGuide = true;

    // Student strokes are drawn onto this offscreen bitmap so we can measure
    // coverage pixel-by-pixel without the guide glyph interfering.
    @Nullable private Bitmap strokeBitmap;
    @Nullable private Canvas strokeCanvas;
    private final Path activePath = new Path();

    private float lastX, lastY;
    private boolean hasDrawnAnything = false;

    private static final float TOUCH_TOLERANCE = 4f;

    /** One numbered arrow: dashed path, arrowhead, and the number badge at its start. */
    private static final class GuideStroke {
        final Path line;
        final Path head;
        final float badgeX, badgeY, badgeRadius, lineWidth;
        final int number, color;

        GuideStroke(Path line, Path head, float badgeX, float badgeY, float badgeRadius,
                    float lineWidth, int number, int color) {
            this.line = line;
            this.head = head;
            this.badgeX = badgeX;
            this.badgeY = badgeY;
            this.badgeRadius = badgeRadius;
            this.lineWidth = lineWidth;
            this.number = number;
            this.color = color;
        }
    }

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

        arrowLinePaint.setStyle(Paint.Style.STROKE);
        arrowLinePaint.setStrokeCap(Paint.Cap.ROUND);
        arrowLinePaint.setStrokeJoin(Paint.Join.ROUND);

        arrowHeadPaint.setStyle(Paint.Style.FILL);

        badgePaint.setStyle(Paint.Style.FILL);

        badgeRingPaint.setStyle(Paint.Style.STROKE);
        badgeRingPaint.setColor(Color.WHITE);

        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextAlign(Paint.Align.CENTER);
        badgeTextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    /** Sets the character to trace and clears any existing strokes. */
    public void setGlyph(@NonNull String glyph) {
        this.glyph = glyph;
        layoutStrokeGuide();
        clear();
    }

    /** Show or hide the numbered stroke-order arrows (shown by default). */
    public void setShowStrokeGuide(boolean show) {
        if (showStrokeGuide == show) return;
        showStrokeGuide = show;
        invalidate();
    }

    public boolean isShowingStrokeGuide() {
        return showStrokeGuide;
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
            layoutStrokeGuide();
        }
    }

    /**
     * Places the stroke-order arrows on the guide glyph as it is actually drawn:
     * each character's tight ink box comes from the same paint used to draw the
     * guide, so the arrows sit inside the letter's strokes. Multi-character
     * glyphs ("10", "12") get arrows for each digit.
     */
    private void layoutStrokeGuide() {
        guideStrokes.clear();
        int viewW = getWidth();
        int viewH = getHeight();
        if (glyph.isEmpty() || viewW <= 0 || viewH <= 0) return;

        Paint.FontMetrics fm = guidePaint.getFontMetrics();
        float baseline = viewH / 2f - (fm.ascent + fm.descent) / 2f;
        // Text is drawn centre-aligned, so the string starts half its width left of centre.
        float textStartX = viewW / 2f - guidePaint.measureText(glyph) / 2f;

        Rect bounds = new Rect();
        List<PointF> placedBadges = new ArrayList<>();

        for (int i = 0; i < glyph.length(); i++) {
            List<float[]> strokes = StrokeGuides.strokesFor(glyph.charAt(i));
            if (strokes.isEmpty()) continue;

            float originX = textStartX + guidePaint.measureText(glyph, 0, i);
            guidePaint.getTextBounds(glyph, i, i + 1, bounds);
            float boxLeft = originX + bounds.left;
            float boxTop = baseline + bounds.top;
            float boxW = bounds.width();
            float boxH = bounds.height();
            if (boxW <= 0 || boxH <= 0) continue;

            float lineWidth = Math.max(3f, boxH * 0.022f);
            float headSize = boxH * 0.07f;
            float badgeR = boxH * 0.055f;

            for (int s = 0; s < strokes.size(); s++) {
                float[] p = strokes.get(s);
                int n = p.length / 2;
                float[] xs = new float[n];
                float[] ys = new float[n];
                for (int k = 0; k < n; k++) {
                    xs[k] = boxLeft + p[2 * k] / 100f * boxW;
                    ys[k] = boxTop + p[2 * k + 1] / 100f * boxH;
                }

                Path line = new Path();
                line.moveTo(xs[0], ys[0]);
                for (int k = 1; k < n; k++) line.lineTo(xs[k], ys[k]);

                Path head = arrowHead(xs, ys, headSize);
                PointF badge = placeBadge(xs, ys, badgeR, placedBadges, viewW, viewH);
                placedBadges.add(badge);

                guideStrokes.add(new GuideStroke(line, head, badge.x, badge.y, badgeR, lineWidth,
                        s + 1, GUIDE_COLORS[s % GUIDE_COLORS.length]));
            }
        }
    }

    /** Filled triangle at the end of the stroke, pointing the way the stroke travels. */
    private static Path arrowHead(float[] xs, float[] ys, float size) {
        int last = xs.length - 1;
        int prev = last - 1;
        // Walk back past any duplicate points so the direction is well defined.
        while (prev > 0 && Math.hypot(xs[last] - xs[prev], ys[last] - ys[prev]) < 0.5f) prev--;
        double angle = Math.atan2(ys[last] - ys[prev], xs[last] - xs[prev]);
        double spread = 0.45;

        Path head = new Path();
        head.moveTo(xs[last], ys[last]);
        head.lineTo((float) (xs[last] - size * Math.cos(angle - spread)),
                (float) (ys[last] - size * Math.sin(angle - spread)));
        head.lineTo((float) (xs[last] - size * Math.cos(angle + spread)),
                (float) (ys[last] - size * Math.sin(angle + spread)));
        head.close();
        return head;
    }

    /**
     * Puts the number badge just behind where the stroke starts. When two strokes
     * start at the same corner (A, B, E, M ...) the later badge slides sideways
     * to whichever side is clearer, so the numbers never sit on top of each other.
     */
    private static PointF placeBadge(float[] xs, float[] ys, float r,
                                     List<PointF> placed, int viewW, int viewH) {
        // Direction the stroke heads in, measured a badge-width along it.
        int k = 1;
        while (k < xs.length - 1 && Math.hypot(xs[k] - xs[0], ys[k] - ys[0]) < r) k++;
        float ux = xs[k] - xs[0];
        float uy = ys[k] - ys[0];
        float len = (float) Math.hypot(ux, uy);
        if (len == 0f) len = 1f;
        ux /= len;
        uy /= len;

        float bx = xs[0] - ux * r * 1.2f;
        float by = ys[0] - uy * r * 1.2f;

        float minGap = 2.1f * r;
        if (clearance(bx, by, placed) < minGap) {
            float c1x = bx - uy * minGap, c1y = by + ux * minGap;
            float c2x = bx + uy * minGap, c2y = by - ux * minGap;
            if (clearance(c1x, c1y, placed) >= clearance(c2x, c2y, placed)) {
                bx = c1x;
                by = c1y;
            } else {
                bx = c2x;
                by = c2y;
            }
        }

        bx = Math.max(r, Math.min(viewW - r, bx));
        by = Math.max(r, Math.min(viewH - r, by));
        return new PointF(bx, by);
    }

    private static float clearance(float x, float y, List<PointF> placed) {
        float best = Float.MAX_VALUE;
        for (PointF p : placed) {
            best = Math.min(best, (float) Math.hypot(p.x - x, p.y - y));
        }
        return best;
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

        // Stroke-order arrows sit on the guide, underneath the student's ink.
        if (showStrokeGuide) drawStrokeGuide(canvas);

        // Draw the student's committed strokes, then the in-progress one.
        if (strokeBitmap != null) {
            canvas.drawBitmap(strokeBitmap, 0, 0, null);
        }
        canvas.drawPath(activePath, strokePaint);
    }

    private void drawStrokeGuide(Canvas canvas) {
        // Lines and arrowheads first, then every badge, so numbers are never covered.
        for (GuideStroke g : guideStrokes) {
            arrowLinePaint.setColor(g.color);
            arrowLinePaint.setStrokeWidth(g.lineWidth);
            arrowLinePaint.setPathEffect(new DashPathEffect(
                    new float[]{g.lineWidth * 2.2f, g.lineWidth * 1.8f}, 0f));
            canvas.drawPath(g.line, arrowLinePaint);

            arrowHeadPaint.setColor(g.color);
            canvas.drawPath(g.head, arrowHeadPaint);
        }
        for (GuideStroke g : guideStrokes) {
            badgePaint.setColor(g.color);
            canvas.drawCircle(g.badgeX, g.badgeY, g.badgeRadius, badgePaint);

            badgeRingPaint.setStrokeWidth(Math.max(2f, g.badgeRadius * 0.15f));
            canvas.drawCircle(g.badgeX, g.badgeY, g.badgeRadius, badgeRingPaint);

            badgeTextPaint.setTextSize(g.badgeRadius * 1.3f);
            Paint.FontMetrics tm = badgeTextPaint.getFontMetrics();
            float textY = g.badgeY - (tm.ascent + tm.descent) / 2f;
            canvas.drawText(String.valueOf(g.number), g.badgeX, textY, badgeTextPaint);
        }
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
