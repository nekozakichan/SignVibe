package com.ucucite.signvibe.ui.quiz;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Party-popper confetti for the "Awesome!" screen. Each burst draws a little
 * party-popper cone that pops and shoots a spray of paper confetti, which then
 * flutters down with gravity, spin and a paper-flip wobble.
 *
 * Purely decorative: it never takes touches, so taps pass through to the views
 * underneath. No library needed — it animates itself with
 * postInvalidateOnAnimation() only while something is on screen.
 */
public class ConfettiView extends View {

    private static final int[] CONFETTI_COLORS = {
            0xFFFFC107, 0xFFE91E63, 0xFF00BCD4, 0xFF8BC34A,
            0xFFFF5722, 0xFF9C27B0, 0xFFFFEB3B, 0xFF2196F3
    };

    // Physics, in dp so it feels the same on every screen density.
    private static final float SPEED_MIN_DP = 1100f;     // launch speed, dp/s
    private static final float SPEED_MAX_DP = 1800f;
    private static final float SPREAD_DEG = 24f;          // half-width of the spray
    private static final float GRAVITY_DP = 1200f;        // dp/s²
    private static final float DRAG = 1.3f;               // per second; paper slows quickly
    private static final float TERMINAL_FALL_DP = 230f;   // flutters down, never plummets
    private static final float SWAY_DP = 40f;

    private static final float CONE_LIFETIME_S = 2.0f;
    private static final float CONE_FADE_S = 0.4f;

    private final List<Particle> particles = new ArrayList<>();
    private final List<Cone> cones = new ArrayList<>();
    private final Random rnd = new Random();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stripePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path conePath = new Path();
    private final RectF rect = new RectF();
    private final float density;

    private long lastFrameNanos = 0L;

    private static final class Particle {
        float x, y, vx, vy;
        float rotation, spin;          // degrees, degrees/s
        float flip, flipSpeed;         // radians, radians/s (the paper-turning effect)
        float width, height;
        float age, lifetime, swayPhase;
        int color;
        boolean round;
    }

    private static final class Cone {
        float x, y, angle;             // mouth position, direction it fires (degrees)
        float age;
    }

    public ConfettiView(Context context) {
        this(context, null);
    }

    public ConfettiView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        stripePaint.setStyle(Paint.Style.STROKE);
        stripePaint.setColor(0xFFE91E63);
    }

    private float dp(float v) {
        return v * density;
    }

    /**
     * Pops a party-popper cone with its mouth at (x, y) and fires confetti.
     *
     * @param angleDeg direction the cone fires: 0 = right, -90 = straight up,
     *                 -60 = up and to the right, -120 = up and to the left
     * @param count    number of confetti pieces
     */
    public void burstFromCone(float x, float y, float angleDeg, int count) {
        Cone cone = new Cone();
        cone.x = x;
        cone.y = y;
        cone.angle = angleDeg;
        cones.add(cone);

        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            double dir = Math.toRadians(angleDeg + (rnd.nextFloat() * 2f - 1f) * SPREAD_DEG);
            float speed = dp(SPEED_MIN_DP + rnd.nextFloat() * (SPEED_MAX_DP - SPEED_MIN_DP));
            p.x = x + dp(rnd.nextFloat() * 8f - 4f);
            p.y = y + dp(rnd.nextFloat() * 8f - 4f);
            p.vx = (float) (Math.cos(dir) * speed);
            p.vy = (float) (Math.sin(dir) * speed);
            p.rotation = rnd.nextFloat() * 360f;
            p.spin = (rnd.nextFloat() * 2f - 1f) * 540f;
            p.flip = rnd.nextFloat() * (float) Math.PI;
            p.flipSpeed = 6f + rnd.nextFloat() * 8f;
            p.round = rnd.nextInt(5) == 0;                 // mostly paper strips, a few dots
            p.width = dp(p.round ? 7f : 7f + rnd.nextFloat() * 5f);
            p.height = dp(p.round ? 7f : 4f + rnd.nextFloat() * 3f);
            p.lifetime = 2.6f + rnd.nextFloat() * 0.9f;
            p.swayPhase = rnd.nextFloat() * 6.28f;
            p.color = CONFETTI_COLORS[rnd.nextInt(CONFETTI_COLORS.length)];
            particles.add(p);
        }

        lastFrameNanos = 0L;   // restart the clock so the first frame isn't a huge jump
        postInvalidateOnAnimation();
    }

    /** Removes everything immediately (e.g. when the feedback screen closes). */
    public void clear() {
        particles.clear();
        cones.clear();
        lastFrameNanos = 0L;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (particles.isEmpty() && cones.isEmpty()) return;

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        dt = Math.min(dt, 0.05f);   // a dropped frame shouldn't teleport the confetti

        updateCones(dt);
        updateParticles(dt);

        for (Cone c : cones) drawCone(canvas, c);
        for (Particle p : particles) drawParticle(canvas, p);

        if (!particles.isEmpty() || !cones.isEmpty()) {
            postInvalidateOnAnimation();
        } else {
            lastFrameNanos = 0L;
        }
    }

    private void updateCones(float dt) {
        Iterator<Cone> it = cones.iterator();
        while (it.hasNext()) {
            Cone c = it.next();
            c.age += dt;
            if (c.age > CONE_LIFETIME_S) it.remove();
        }
    }

    private void updateParticles(float dt) {
        float gravity = dp(GRAVITY_DP);
        float terminal = dp(TERMINAL_FALL_DP);
        float sway = dp(SWAY_DP);
        float dragFactor = (float) Math.exp(-DRAG * dt);
        int h = getHeight();

        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.age += dt;

            p.vx *= dragFactor;
            p.vy = p.vy * dragFactor + gravity * dt;
            if (p.vy > terminal) p.vy = terminal;
            // Gentle side-to-side drift once it's falling, like real paper.
            float drift = p.vy > 0 ? (float) Math.sin(p.age * 5f + p.swayPhase) * sway : 0f;

            p.x += (p.vx + drift) * dt;
            p.y += p.vy * dt;
            p.rotation += p.spin * dt;
            p.flip += p.flipSpeed * dt;

            if (p.age > p.lifetime || (h > 0 && p.y > h + dp(40))) it.remove();
        }
    }

    private void drawParticle(Canvas canvas, Particle p) {
        float fadeStart = p.lifetime - 0.6f;
        int alpha = p.age < fadeStart ? 255
                : (int) (255 * Math.max(0f, 1f - (p.age - fadeStart) / 0.6f));

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(p.color);
        paint.setAlpha(alpha);

        canvas.save();
        canvas.translate(p.x, p.y);
        canvas.rotate(p.rotation);
        // Squash one axis with cos(flip) so the paper looks like it's turning over.
        canvas.scale(1f, Math.max(0.12f, Math.abs((float) Math.cos(p.flip))));
        if (p.round) {
            canvas.drawCircle(0f, 0f, p.width / 2f, paint);
        } else {
            rect.set(-p.width / 2f, -p.height / 2f, p.width / 2f, p.height / 2f);
            canvas.drawRoundRect(rect, dp(1.5f), dp(1.5f), paint);
        }
        canvas.restore();
    }

    /**
     * A gold party-popper cone with pink stripes. Local coordinates: the mouth is
     * at the origin facing +x (the firing direction), the pointy end behind it.
     * It kicks back and swells as it pops, then fades out.
     */
    private void drawCone(Canvas canvas, Cone c) {
        float length = dp(62f);
        float mouth = dp(21f);

        float pop;   // 0..1 progress of the pop animation
        float scale;
        float recoil;
        if (c.age < 0.12f) {
            pop = c.age / 0.12f;
            scale = 0.7f + 0.45f * pop;               // swell...
            recoil = -dp(10f) * pop;                  // ...and kick backwards
        } else if (c.age < 0.35f) {
            pop = (c.age - 0.12f) / 0.23f;
            scale = 1.15f - 0.15f * pop;              // settle back to normal
            recoil = -dp(10f) * (1f - pop);
        } else {
            scale = 1f;
            recoil = 0f;
        }
        float fadeStart = CONE_LIFETIME_S - CONE_FADE_S;
        int alpha = c.age < fadeStart ? 255
                : (int) (255 * Math.max(0f, 1f - (c.age - fadeStart) / CONE_FADE_S));

        canvas.save();
        canvas.translate(c.x, c.y);
        canvas.rotate(c.angle);
        canvas.translate(recoil, 0f);
        canvas.scale(scale, scale);

        conePath.reset();
        conePath.moveTo(-length, 0f);          // pointy end
        conePath.lineTo(0f, -mouth);
        conePath.lineTo(0f, mouth);
        conePath.close();

        // Body.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFB300);
        paint.setAlpha(alpha);
        canvas.drawPath(conePath, paint);

        // Diagonal stripes, clipped to the cone.
        canvas.save();
        canvas.clipPath(conePath);
        stripePaint.setStrokeWidth(dp(6f));
        stripePaint.setAlpha(alpha);
        for (float sx = -length; sx < dp(20f); sx += dp(15f)) {
            canvas.drawLine(sx, mouth * 1.2f, sx + dp(18f), -mouth * 1.2f, stripePaint);
        }
        canvas.restore();

        // Rim of the mouth.
        paint.setColor(0xFFFF8F00);
        paint.setAlpha(alpha);
        rect.set(-dp(4f), -mouth, dp(4f), mouth);
        canvas.drawOval(rect, paint);

        canvas.restore();
    }
}
