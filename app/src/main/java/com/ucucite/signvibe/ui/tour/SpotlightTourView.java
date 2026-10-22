package com.ucucite.signvibe.ui.tour;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.ucucite.signvibe.R;

import java.util.List;

/**
 * Full-screen coach-mark tour. Dims the screen, cuts a rounded "spotlight" hole
 * around the current target with a soft pulsing ring, and shows a tip card with
 * Skip / Next next to it. Tapping anywhere on the dimmed area also goes to the
 * next step, which is easier for young children than hitting a small button.
 *
 * Added on top of the activity's content view by {@link #show}; removes itself
 * when finished or skipped.
 */
public class SpotlightTourView extends FrameLayout {

    public interface OnFinished {
        /** @param completed true if the student reached the last step, false if skipped */
        void onFinished(boolean completed);
    }

    private static final int DIM_COLOR = 0xD90B2B2D;   // dark teal, ~85% opaque
    private static final long MOVE_MS = 300;

    private final Activity activity;
    private final List<TourStep> steps;
    private final OnFinished onFinished;
    private final float density;

    private final View card;
    private final TextView txtStep;
    private final TextView txtTitle;
    private final TextView txtBody;
    private final TextView btnNext;
    private final TextView btnSkip;

    private final RectF hole = new RectF();       // drawn (animated) spotlight
    private final RectF holeFrom = new RectF();
    private final RectF holeTo = new RectF();     // where the current step's spotlight ends up
    private final RectF ringRect = new RectF();
    private final Path dimPath = new Path();
    private final Path pointerPath = new Path();
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ValueAnimator moveAnim;
    private ValueAnimator pulseAnim;
    private float pulse;            // 0..1, drives the ring around the spotlight
    private int index = -1;
    private boolean finishing = false;

    /** Starts the tour over the given activity. */
    @NonNull
    public static SpotlightTourView show(@NonNull Activity activity,
                                         @NonNull List<TourStep> steps,
                                         @NonNull OnFinished onFinished) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        SpotlightTourView tour = new SpotlightTourView(activity, steps, onFinished);
        root.addView(tour, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        tour.setAlpha(0f);
        tour.animate().alpha(1f).setDuration(200).start();
        tour.post(() -> tour.goTo(0));   // after layout, so target positions are known
        return tour;
    }

    private SpotlightTourView(Activity activity, List<TourStep> steps, OnFinished onFinished) {
        super(activity);
        this.activity = activity;
        this.steps = steps;
        this.onFinished = onFinished;
        this.density = activity.getResources().getDisplayMetrics().density;

        setWillNotDraw(false);
        setClickable(true);       // swallow touches so the app underneath can't be tapped
        setFocusable(true);
        setElevation(dp(30));
        setOnClickListener(v -> next());

        dimPaint.setColor(DIM_COLOR);
        dimPaint.setStyle(Paint.Style.FILL);
        ringPaint.setColor(0xFFFFFFFF);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3));
        pointerPaint.setColor(0xFFFFFFFF);
        pointerPaint.setStyle(Paint.Style.FILL);

        card = LayoutInflater.from(activity).inflate(R.layout.view_tour_card, this, false);
        card.setClickable(true);  // taps on the card itself don't skip ahead
        addView(card);

        txtStep = card.findViewById(R.id.txtTourStep);
        txtTitle = card.findViewById(R.id.txtTourTitle);
        txtBody = card.findViewById(R.id.txtTourBody);
        btnNext = card.findViewById(R.id.btnTourNext);
        btnSkip = card.findViewById(R.id.btnTourSkip);
        btnNext.setOnClickListener(v -> next());
        btnSkip.setOnClickListener(v -> finish(false));
    }

    private float dp(float v) {
        return v * density;
    }

    /** Ends the tour early (e.g. the back button). */
    public void dismiss() {
        finish(false);
    }

    private void next() {
        if (finishing) return;
        if (index < steps.size() - 1) {
            goTo(index + 1);
        } else {
            finish(true);
        }
    }

    private void goTo(int newIndex) {
        if (finishing || newIndex < 0 || newIndex >= steps.size()) return;
        index = newIndex;
        TourStep step = steps.get(index);
        boolean last = index == steps.size() - 1;

        Context c = getContext();
        txtStep.setText(c.getString(R.string.tour_step_fmt, index + 1, steps.size()));
        txtTitle.setText(step.title);
        txtBody.setText(step.body);
        btnNext.setText(last ? R.string.tour_done : R.string.tour_next);
        btnSkip.setVisibility(last ? View.INVISIBLE : View.VISIBLE);

        computeTargetRect(step, holeTo);
        animateHoleTo(holeTo);

        // Re-place the card for this step and fade it in.
        card.setAlpha(0f);
        requestLayout();
        card.animate().alpha(1f).setDuration(220).setStartDelay(80).start();
    }

    /** Target's bounds in this overlay's coordinates, padded; empty when there's no target. */
    private void computeTargetRect(TourStep step, RectF out) {
        View target = step.targetId != 0 ? activity.findViewById(step.targetId) : null;
        if (target == null || !target.isShown() || target.getWidth() == 0) {
            out.set(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f, getHeight() / 2f);
            return;
        }
        int[] me = new int[2];
        int[] it = new int[2];
        getLocationInWindow(me);
        target.getLocationInWindow(it);
        float left = it[0] - me[0];
        float top = it[1] - me[1];
        float pad = dp(4);
        out.set(left - pad, top - pad,
                left + target.getWidth() + pad, top + target.getHeight() + pad);
    }

    private void animateHoleTo(RectF to) {
        if (moveAnim != null) moveAnim.cancel();
        if (hole.isEmpty() && index == 0) {
            // First step: start from a dot at the target's centre, not from the corner.
            hole.set(to.centerX(), to.centerY(), to.centerX(), to.centerY());
        }
        holeFrom.set(hole);
        moveAnim = ValueAnimator.ofFloat(0f, 1f);
        moveAnim.setDuration(MOVE_MS);
        moveAnim.setInterpolator(new DecelerateInterpolator());
        moveAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            hole.set(lerp(holeFrom.left, to.left, t), lerp(holeFrom.top, to.top, t),
                    lerp(holeFrom.right, to.right, t), lerp(holeFrom.bottom, to.bottom, t));
            invalidate();
        });
        moveAnim.start();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        int cardW = (int) Math.min(w - dp(40), dp(340));
        card.measure(MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
    }

    /** Card goes centred when nothing is highlighted, else just above/below the spotlight. */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        int cw = card.getMeasuredWidth();
        int ch = card.getMeasuredHeight();
        float margin = dp(20);
        float gap = dp(18);

        float x;
        float y;
        if (holeTo.width() < 1f) {
            x = (w - cw) / 2f;
            y = (h - ch) / 2f;
        } else {
            x = clamp(holeTo.centerX() - cw / 2f, margin, w - cw - margin);
            boolean targetInLowerHalf = holeTo.centerY() > h / 2f;
            y = targetInLowerHalf ? holeTo.top - gap - ch : holeTo.bottom + gap;
            y = clamp(y, margin, h - ch - margin);
        }
        card.layout((int) x, (int) y, (int) x + cw, (int) y + ch);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Dim everything except the spotlight hole.
        dimPath.reset();
        dimPath.setFillType(Path.FillType.EVEN_ODD);
        dimPath.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        boolean hasHole = hole.width() > 1f && hole.height() > 1f;
        float radius = Math.min(dp(20), Math.min(hole.width(), hole.height()) / 2f);
        if (hasHole) dimPath.addRoundRect(hole, radius, radius, Path.Direction.CW);
        canvas.drawPath(dimPath, dimPaint);

        // Soft ring that grows and fades around the spotlight.
        if (hasHole) {
            float grow = pulse * dp(12);
            ringRect.set(hole.left - grow, hole.top - grow, hole.right + grow, hole.bottom + grow);
            ringPaint.setAlpha((int) (220 * (1f - pulse)));
            canvas.drawRoundRect(ringRect, radius + grow, radius + grow, ringPaint);
        }

        super.dispatchDraw(canvas);   // the tip card

        // Little white pointer from the card toward the spotlight.
        if (hasHole && holeTo.width() >= 1f && card.getAlpha() > 0.05f) {
            float size = dp(10);
            float px = clamp(holeTo.centerX(), card.getLeft() + dp(24), card.getRight() - dp(24));
            pointerPath.reset();
            if (card.getBottom() <= holeTo.top) {          // card above the target
                float py = card.getBottom();
                pointerPath.moveTo(px - size, py - 1);
                pointerPath.lineTo(px + size, py - 1);
                pointerPath.lineTo(px, py + size);
            } else if (card.getTop() >= holeTo.bottom) {   // card below the target
                float py = card.getTop();
                pointerPath.moveTo(px - size, py + 1);
                pointerPath.lineTo(px + size, py + 1);
                pointerPath.lineTo(px, py - size);
            }
            pointerPath.close();
            pointerPaint.setAlpha((int) (255 * card.getAlpha()));
            canvas.drawPath(pointerPath, pointerPaint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        pulseAnim = ValueAnimator.ofFloat(0f, 1f);
        pulseAnim.setDuration(1300);
        pulseAnim.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnim.addUpdateListener(a -> {
            pulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (pulseAnim != null) pulseAnim.cancel();
        if (moveAnim != null) moveAnim.cancel();
    }

    private void finish(boolean completed) {
        if (finishing) return;
        finishing = true;
        animate().alpha(0f).setDuration(180).withEndAction(() -> {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);
        }).start();
        onFinished.onFinished(completed);
    }
}
