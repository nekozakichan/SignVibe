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
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A tracing canvas for a single letter/number. Draws a large faint guide glyph
 * with numbered stroke-order arrows on top, and lets the student draw strokes
 * over it with a finger.
 *
 * Stroke order is enforced: each finger stroke must match the next numbered
 * arrow — start near its number, travel the way the arrow points, and follow it
 * to the end. A matching stroke stays (and its arrow fades); a stroke that
 * doesn't match is erased and reported to the {@link Listener} so the screen
 * can say which number to start at. Matching is lenient (SPED-friendly): the
 * finger only has to stay roughly inside the letter's own thickness.
 *
 * Characters with no stroke guide (see {@link StrokeGuides}) fall back to the
 * old free tracing, judged by {@link #computeCoverage()}.
 *
 * Practice mode highlights only the next arrow and runs a dot along it to show
 * how to draw it.
 */
public class TracingView extends View {

    /** Told about each finished finger stroke when stroke order is enforced. */
    public interface Listener {
        /** The stroke matched arrow {@code strokeNumber}; {@code glyphComplete} when it was the last one. */
        void onStrokeAccepted(int strokeNumber, boolean glyphComplete);

        /**
         * The stroke didn't match arrow {@code expectedNumber} and was erased.
         * {@code wrongStart} = it began away from that arrow's number.
         */
        void onStrokeRejected(int expectedNumber, boolean wrongStart);
    }

    // How much of the view height the guide glyph fills. Used in BOTH the guide
    // paint and the coverage measurement — they must always match, so it lives
    // in one constant.
    private static final float GLYPH_HEIGHT_FRACTION = 0.9f;

    // Stroke-order arrow colours, by stroke number: 1 orange, 2 purple, 3 blue, 4 green.
    // Kept away from the teal student ink so the two never get confused.
    private static final int[] GUIDE_COLORS = {
            0xFFF4511E, 0xFF8E24AA, 0xFF1E88E5, 0xFF43A047
    };

    // ── Stroke matching (tunable) ──
    // Each guide stroke is resampled to this many evenly spaced points.
    private static final int GUIDE_SAMPLES = 48;
    // How far (as a share of the letter's height) the finger may stray from the
    // arrow's centre line and still count as on it. Roughly the letter's own
    // stroke thickness plus some slack for small fingers.
    private static final float TOLERANCE_OF_HEIGHT = 0.16f;
    // The stroke must begin within this many tolerances of the arrow's start.
    private static final float START_TOLERANCE = 1.4f;
    // The finger must get at least this far along the arrow (0..1).
    private static final float MIN_PROGRESS = 0.82f;
    // At most this share of the finger's path may wander off the arrow.
    private static final float MAX_OFF_TRACK = 0.35f;
    // How long the next number pulses after a wrong stroke.
    private static final long HINT_PULSE_MS = 1800L;
    // One trip of the practice demo dot along the arrow.
    private static final long DEMO_LOOP_MS = 1900L;

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
    private final Paint demoDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<GuideStroke> guideStrokes = new ArrayList<>();
    private boolean showStrokeGuide = true;

    // Student strokes are drawn onto this offscreen bitmap so we can measure
    // coverage pixel-by-pixel without the guide glyph interfering.
    @Nullable private Bitmap strokeBitmap;
    @Nullable private Canvas strokeCanvas;
    private final Path activePath = new Path();
    // Every touch point of the finger stroke in progress (historical points
    // included), used to match it against the next arrow.
    private final List<PointF> touchPoints = new ArrayList<>();

    private float lastX, lastY;
    private boolean hasDrawnAnything = false;

    // Stroke-order progress for the current glyph.
    private int nextStroke = 0;          // index into guideStrokes
    private int mistakes = 0;            // rejected strokes since setGlyph()
    private long hintUntil = 0L;         // pulse the next number until this uptime
    private boolean practiceMode = false;
    @Nullable private Listener listener;

    private static final float TOUCH_TOLERANCE = 4f;

    /** One numbered arrow: dashed path, arrowhead, number badge, and its points for matching. */
    private static final class GuideStroke {
        final Path line;
        final Path head;
        final float badgeX, badgeY, badgeRadius, lineWidth;
        final int number, color;
        final float[] xs, ys;   // GUIDE_SAMPLES evenly spaced points along the arrow
        final float tolerance;  // px

        GuideStroke(Path line, Path head, float badgeX, float badgeY, float badgeRadius,
                    float lineWidth, int number, int color, float[] xs, float[] ys, float tolerance) {
            this.line = line;
            this.head = head;
            this.badgeX = badgeX;
            this.badgeY = badgeY;
            this.badgeRadius = badgeRadius;
            this.lineWidth = lineWidth;
            this.number = number;
            this.color = color;
            this.xs = xs;
            this.ys = ys;
            this.tolerance = tolerance;
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

        demoDotPaint.setStyle(Paint.Style.FILL);
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /** Practice mode: only the next arrow is bright, with a dot showing how to draw it. */
    public void setPracticeMode(boolean practice) {
        practiceMode = practice;
        invalidate();
    }

    /** Sets the character to trace and clears any existing strokes and mistakes. */
    public void setGlyph(@NonNull String glyph) {
        this.glyph = glyph;
        mistakes = 0;
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

    /**
     * Wipes the student's strokes and starts the stroke order again from 1
     * ("Clear" button). Mistakes made on this glyph are kept, so clearing
     * doesn't wipe the record of wrong strokes.
     */
    public void clear() {
        if (strokeBitmap != null) {
            strokeBitmap.eraseColor(Color.TRANSPARENT);
        }
        activePath.reset();
        touchPoints.clear();
        hasDrawnAnything = false;
        nextStroke = 0;
        hintUntil = 0L;
        invalidate();
    }

    /** True when this glyph has numbered arrows, so stroke order is enforced. */
    public boolean hasStrokeOrder() {
        return !guideStrokes.isEmpty();
    }

    /** True once every arrow has been traced in order. */
    public boolean isComplete() {
        return hasStrokeOrder() && nextStroke >= guideStrokes.size();
    }

    /** Wrong strokes on this glyph since it was shown. */
    public int getMistakeCount() {
        return mistakes;
    }

    /** The number shown on the arrow to trace next (1, 2, 3 …), or 0 when done. */
    public int getNextStrokeNumber() {
        return nextStroke < guideStrokes.size() ? guideStrokes.get(nextStroke).number : 0;
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
            // The new bitmap is empty, so the stroke order starts over too.
            nextStroke = 0;
            hasDrawnAnything = false;
        }
    }

    /**
     * Places the stroke-order arrows on the guide glyph as it is actually drawn:
     * each character's tight ink box comes from the same paint used to draw the
     * guide, so the arrows sit inside the letter's strokes. Multi-character
     * glyphs ("10", "12") get arrows for each digit, traced left digit first.
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
            float tolerance = Math.max(boxH * TOLERANCE_OF_HEIGHT, 24f);

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

                float[][] even = resample(xs, ys, GUIDE_SAMPLES);
                guideStrokes.add(new GuideStroke(line, head, badge.x, badge.y, badgeR, lineWidth,
                        s + 1, GUIDE_COLORS[s % GUIDE_COLORS.length], even[0], even[1], tolerance));
            }
        }
    }

    /** {xs, ys} of {@code count} points spaced evenly along the polyline. */
    private static float[][] resample(float[] xs, float[] ys, int count) {
        int n = xs.length;
        float[] cum = new float[n];
        for (int k = 1; k < n; k++) {
            cum[k] = cum[k - 1] + (float) Math.hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1]);
        }
        float total = cum[n - 1];
        float[] ox = new float[count];
        float[] oy = new float[count];
        int seg = 1;
        for (int i = 0; i < count; i++) {
            float target = total * i / (count - 1);
            while (seg < n - 1 && cum[seg] < target) seg++;
            float segLen = cum[seg] - cum[seg - 1];
            float t = segLen > 0f ? (target - cum[seg - 1]) / segLen : 0f;
            t = Math.max(0f, Math.min(1f, t));
            ox[i] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t;
            oy[i] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t;
        }
        return new float[][]{ox, oy};
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
        boolean animating = false;
        if (showStrokeGuide) animating = drawStrokeGuide(canvas);

        // Draw the student's committed strokes, then the in-progress one.
        if (strokeBitmap != null) {
            canvas.drawBitmap(strokeBitmap, 0, 0, null);
        }
        canvas.drawPath(activePath, strokePaint);

        // Keep the pulse / demo dot moving.
        if (animating) postInvalidateOnAnimation();
    }

    /**
     * Draws the arrows. Arrows already traced are faded; in practice mode the
     * arrows after the next one are faded too. Returns true while something is
     * animating (the hint pulse or the practice demo dot).
     */
    private boolean drawStrokeGuide(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        boolean hinting = now < hintUntil;
        boolean demo = practiceMode && !isComplete() && nextStroke < guideStrokes.size()
                && touchPoints.isEmpty();

        // Lines and arrowheads first, then every badge, so numbers are never covered.
        for (int i = 0; i < guideStrokes.size(); i++) {
            GuideStroke g = guideStrokes.get(i);
            int alpha = alphaFor(i);
            arrowLinePaint.setColor(g.color);
            arrowLinePaint.setAlpha(alpha);
            arrowLinePaint.setStrokeWidth(g.lineWidth);
            arrowLinePaint.setPathEffect(new DashPathEffect(
                    new float[]{g.lineWidth * 2.2f, g.lineWidth * 1.8f}, 0f));
            canvas.drawPath(g.line, arrowLinePaint);

            arrowHeadPaint.setColor(g.color);
            arrowHeadPaint.setAlpha(alpha);
            canvas.drawPath(g.head, arrowHeadPaint);
        }
        for (int i = 0; i < guideStrokes.size(); i++) {
            GuideStroke g = guideStrokes.get(i);
            int alpha = alphaFor(i);
            float r = g.badgeRadius;
            // The next number gently pulses after a wrong stroke, and always in practice.
            if (i == nextStroke && (hinting || practiceMode)) {
                double phase = (now % 900L) / 900.0 * 2 * Math.PI;
                r *= 1f + 0.18f * (float) (0.5 + 0.5 * Math.sin(phase));
            }
            badgePaint.setColor(g.color);
            badgePaint.setAlpha(alpha);
            canvas.drawCircle(g.badgeX, g.badgeY, r, badgePaint);

            badgeRingPaint.setAlpha(alpha);
            badgeRingPaint.setStrokeWidth(Math.max(2f, r * 0.15f));
            canvas.drawCircle(g.badgeX, g.badgeY, r, badgeRingPaint);

            badgeTextPaint.setAlpha(alpha);
            badgeTextPaint.setTextSize(r * 1.3f);
            Paint.FontMetrics tm = badgeTextPaint.getFontMetrics();
            float textY = g.badgeY - (tm.ascent + tm.descent) / 2f;
            canvas.drawText(String.valueOf(g.number), g.badgeX, textY, badgeTextPaint);
        }

        // Practice: a dot runs along the next arrow to show how to draw it.
        if (demo) {
            GuideStroke g = guideStrokes.get(nextStroke);
            float t = (now % DEMO_LOOP_MS) / (float) DEMO_LOOP_MS;
            int idx = Math.min(g.xs.length - 1, Math.round(t * (g.xs.length - 1)));
            float dotR = Math.max(g.lineWidth * 2.4f, 10f);
            demoDotPaint.setColor(Color.WHITE);
            canvas.drawCircle(g.xs[idx], g.ys[idx], dotR * 1.35f, demoDotPaint);
            demoDotPaint.setColor(g.color);
            canvas.drawCircle(g.xs[idx], g.ys[idx], dotR, demoDotPaint);
        }
        return hinting || (practiceMode && !isComplete());
    }

    private int alphaFor(int strokeIndex) {
        if (!hasStrokeOrder()) return 255;
        if (strokeIndex < nextStroke) return 55;                      // already traced
        if (practiceMode && strokeIndex > nextStroke) return 80;      // not yet — keep focus on the next one
        return 255;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Every arrow is traced: nothing more to draw on this glyph.
        if (isComplete() && event.getAction() == MotionEvent.ACTION_DOWN) return false;

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                activePath.reset();
                activePath.moveTo(x, y);
                touchPoints.clear();
                touchPoints.add(new PointF(x, y));
                lastX = x;
                lastY = y;
                hasDrawnAnything = true;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                // Points the system batched between frames — fast fingers would
                // otherwise leave gaps that confuse the stroke matching.
                for (int h = 0; h < event.getHistorySize(); h++) {
                    touchPoints.add(new PointF(event.getHistoricalX(h), event.getHistoricalY(h)));
                }
                touchPoints.add(new PointF(x, y));
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
                touchPoints.add(new PointF(x, y));
                finishStroke();
                activePath.reset();
                touchPoints.clear();
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                activePath.reset();
                touchPoints.clear();
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** The finger lifted: keep the stroke if it matches the next arrow, otherwise erase it. */
    private void finishStroke() {
        if (!hasStrokeOrder()) {
            // No arrows for this character: free tracing, judged by coverage.
            if (strokeCanvas != null) strokeCanvas.drawPath(activePath, strokePaint);
            return;
        }
        if (nextStroke >= guideStrokes.size()) return;

        GuideStroke expected = guideStrokes.get(nextStroke);
        int result = matchStroke(touchPoints, expected);
        if (result == MATCH_TOO_SHORT) return;   // a tap or a slip — ignore, not a mistake

        if (result == MATCH_OK) {
            if (strokeCanvas != null) strokeCanvas.drawPath(activePath, strokePaint);
            nextStroke++;
            if (listener != null) listener.onStrokeAccepted(expected.number, isComplete());
        } else {
            mistakes++;
            hintUntil = SystemClock.uptimeMillis() + HINT_PULSE_MS;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (listener != null) listener.onStrokeRejected(expected.number, result == MATCH_WRONG_START);
        }
    }

    private static final int MATCH_OK = 0;
    private static final int MATCH_WRONG_START = 1;
    private static final int MATCH_OFF_PATH = 2;
    private static final int MATCH_TOO_SHORT = 3;

    /**
     * Does the finger path follow this arrow, from its start, in its direction?
     *
     * Walks along the arrow's evenly spaced points: each finger point may move
     * the "how far along" marker forward to the nearest arrow point a little
     * ahead of it, never backward. Going the wrong way, or starting elsewhere,
     * never moves the marker, so direction is checked without any angle maths —
     * and loops like O and 0 work, because the marker can only travel forward
     * from the start.
     */
    static int matchStroke(List<PointF> raw, GuideStroke g) {
        if (raw.isEmpty()) return MATCH_TOO_SHORT;
        float tol = g.tolerance;

        // Total finger travel: tiny strokes are taps, not attempts.
        float travel = 0f;
        for (int i = 1; i < raw.size(); i++) {
            travel += Math.hypot(raw.get(i).x - raw.get(i - 1).x, raw.get(i).y - raw.get(i - 1).y);
        }
        if (travel < tol * 0.6f) return MATCH_TOO_SHORT;

        PointF first = raw.get(0);
        if (Math.hypot(first.x - g.xs[0], first.y - g.ys[0]) > tol * START_TOLERANCE) {
            return MATCH_WRONG_START;
        }

        // Fill gaps so every stretch of the finger path is looked at.
        List<PointF> pts = densify(raw, tol / 3f);

        int n = g.xs.length;
        int window = Math.max(3, n / 5);
        int marker = 0;
        int offTrack = 0;
        for (PointF p : pts) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            int end = Math.min(n - 1, marker + window);
            for (int j = marker; j <= end; j++) {
                float d = (float) Math.hypot(p.x - g.xs[j], p.y - g.ys[j]);
                if (d <= tol && d < bestD) {
                    bestD = d;
                    best = j;
                }
            }
            if (best > marker) {
                marker = best;
            } else if (best < 0 && distanceToStroke(p, g) > tol * 1.5f) {
                offTrack++;
            }
        }

        float progress = marker / (float) (n - 1);
        float offShare = offTrack / (float) pts.size();
        return progress >= MIN_PROGRESS && offShare <= MAX_OFF_TRACK ? MATCH_OK : MATCH_OFF_PATH;
    }

    private static List<PointF> densify(List<PointF> pts, float step) {
        List<PointF> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            PointF a = pts.get(i - 1);
            PointF b = pts.get(i);
            float d = (float) Math.hypot(b.x - a.x, b.y - a.y);
            int extra = (int) (d / step);
            for (int k = 1; k <= extra; k++) {
                float t = k / (float) (extra + 1);
                out.add(new PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t));
            }
            out.add(b);
        }
        return out;
    }

    private static float distanceToStroke(PointF p, GuideStroke g) {
        float best = Float.MAX_VALUE;
        for (int j = 0; j < g.xs.length; j++) {
            best = Math.min(best, (float) Math.hypot(p.x - g.xs[j], p.y - g.ys[j]));
        }
        return best;
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
     * Used for characters that have no stroke-order arrows.
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
