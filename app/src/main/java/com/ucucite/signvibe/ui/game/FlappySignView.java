package com.ucucite.signvibe.ui.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Flappy Sign game engine. World is a logical 400x600 locked to a 2:3 ratio
 * (see onMeasure) so one scale factor S maps logical units to pixels and the
 * physics never distorts across devices.
 *
 * The view owns all gameplay + drawing. Start / game-over UI lives in the
 * hosting Activity as normal Android views layered on top (better for SPED
 * accessibility than canvas-drawn menus). Results leave via GameListener.
 */
public class FlappySignView extends SurfaceView implements SurfaceHolder.Callback {

    public interface GameListener {
        void onGameOver(int score, int stars);
        /** Fired when the flyer passes through the pipe carrying the target sign. */
        void onTargetCaught();
        /** Fired when the flyer clears any pipe that is NOT the target sign. */
        void onPipePassed();
    }

    private static final int READY = 0, PLAYING = 1, PAUSED = 2, OVER = 3;
    private static final float REF_W = 400f, REF_H = 600f;

    private final Random rnd = new Random();
    private final String[] SIGNS;

    private GameListener listener;
    private String flyer = "\uD83E\uDD1F"; // 🤟

    private int state = READY;
    private float S = 1f;
    private int W = (int) REF_W, H = (int) REF_H;

    private float groundH, pipeW, birdX, birdR;
    private float birdY, birdVy, birdRot, idleT;

    private float gapLogical = 175f, speedLogical = 2.3f;

    private int score;
    private String target = "A";

    private final List<Pipe> pipes = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private float cloudX = 0;
    private Flash flash;

    // Countdown fields
    private int countdown = 0;        // 3,2,1 → 0 means "go"
    private float countdownT = 0f;    // frames elapsed in the current number
    private static final float COUNTDOWN_STEP = 45f; // ~0.75s per number at 60fps

    private final Paint sky = new Paint();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private GameThread thread;

    public FlappySignView(Context c, AttributeSet a) {
        super(c, a);
        getHolder().addCallback(this);
        setFocusable(true);

        List<String> s = new ArrayList<>();
        for (char ch = 'A'; ch <= 'Z'; ch++) s.add(String.valueOf(ch));
        for (int i = 1; i <= 20; i++) s.add(String.valueOf(i));
        SIGNS = s.toArray(new String[0]);
        target = pick();

        stroke.setStyle(Paint.Style.STROKE);
        textFill.setTextAlign(Paint.Align.CENTER);
        textFill.setFakeBoldText(true);
        textStroke.setTextAlign(Paint.Align.CENTER);
        textStroke.setStyle(Paint.Style.STROKE);
        textStroke.setFakeBoldText(true);
        emojiPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setListener(GameListener l) { this.listener = l; }
    public void setFlyer(String emoji) { this.flyer = emoji; }
    public int getScore() { return score; }

    private String pick() { return SIGNS[rnd.nextInt(SIGNS.length)]; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int aw = MeasureSpec.getSize(wSpec);
        int ah = MeasureSpec.getSize(hSpec);
        int w, h;
        int hFromW = Math.round(aw * REF_H / REF_W);
        if (hFromW <= ah) { w = aw; h = hFromW; }
        else { h = ah; w = Math.round(ah * REF_W / REF_H); }
        setMeasuredDimension(w, h);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        thread = new GameThread(holder);
        thread.setRunning(true);
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        W = w; H = h; S = H / REF_H;
        groundH = 70 * S; pipeW = 72 * S; birdX = 96 * S; birdR = 18 * S;
        if (state == READY) resetWorld();
        if (state == PLAYING) birdVy = 0; // kindness: don't insta-drop after a surface recreate
        sky.setShader(new LinearGradient(0, 0, 0, H,
                Color.parseColor("#8ed7ff"), Color.parseColor("#c9f0ff"), Shader.TileMode.CLAMP));
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (thread != null) {
            thread.setRunning(false);
            boolean retry = true;
            while (retry) { try { thread.join(); retry = false; } catch (InterruptedException e) { } }
            thread = null;
        }
    }

    public void startGame() {
        resetWorld();
        state = PLAYING;
    }

    public void pauseGame() {
        if (state == PLAYING) state = PAUSED;
    }

    /** Begin a 3-2-1 countdown; physics stays frozen until it reaches 0. */
    public void resumeGame() {
        if (state == PAUSED) {
            countdown = 3;
            countdownT = 0f;
            state = PLAYING;   // state is PLAYING, but update() gates on countdown
        }
    }

    public boolean isPlaying() { return state == PLAYING; }
    public boolean isPaused()  { return state == PAUSED; }
    public boolean isCountingDown() { return countdown > 0; }

    private void resetWorld() {
        score = 0;
        gapLogical = 175f; speedLogical = 2.3f;
        birdY = H / 2f; birdVy = 0; birdRot = 0;
        pipes.clear(); particles.clear(); flash = null;
        target = pick();
        countdown = 0;
        countdownT = 0f;
    }

    public void flap() {
        if (state == PLAYING && countdown == 0) birdVy = -7.6f * S;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) { flap(); return true; }
        return super.onTouchEvent(e);
    }

    private void update(float dt) {
        cloudX -= 0.15f * S * dt;
        if (cloudX < -W) cloudX += W;

        if (state == READY) {
            idleT += dt;
            birdY = H / 2f + (float) Math.sin(idleT * 0.08f) * 8f * S;
            stepParticles(dt);
            return;
        }

        if (state != PLAYING) return;

        // Resume countdown: keep the scene frozen (still draw), tick the timer only.
        if (countdown > 0) {
            countdownT += dt;
            if (countdownT >= COUNTDOWN_STEP) {
                countdownT = 0f;
                countdown--;
            }
            stepParticles(dt);   // let any leftover sparkle settle; harmless
            return;              // no gravity/pipes/collision yet
        }

        birdVy += 0.42f * S * dt;
        birdY += birdVy * dt;
        birdRot = clamp(birdVy / (12f * S), -0.5f, 1.1f);

        float gapPx = gapLogical * S;
        float spawnGap = 210f * S;
        if (pipes.isEmpty() || pipes.get(pipes.size() - 1).x <= W - spawnGap) spawnPipe(gapPx);

        float speedPx = speedLogical * S;
        for (int i = pipes.size() - 1; i >= 0; i--) {
            Pipe pi = pipes.get(i);
            pi.x -= speedPx * dt;

            if (!pi.passed && pi.x + pipeW < birdX) {
                pi.passed = true;
                score += 1;
                boolean caughtTarget = pi.label.equals(target);
                if (caughtTarget) {
                    score += 5;
                    burst(birdX, birdY);
                    flash = new Flash("\u2728 " + target + " +5");
                    target = pick();
                }
                // One haptic tick per pipe cleared: a firmer one for the target
                // sign, a lighter one for every other pipe.
                if (listener != null) {
                    final boolean caught = caughtTarget;
                    post(() -> {
                        if (caught) listener.onTargetCaught();
                        else listener.onPipePassed();
                    });
                }
                speedLogical = Math.min(3.8f, 2.3f + score * 0.02f);
                gapLogical = Math.max(150f, 175f - score * 0.4f);
            }

            float gy = pi.gapY, gyB = pi.gapY + gapPx;
            boolean inX = birdX + birdR > pi.x && birdX - birdR < pi.x + pipeW;
            if (inX && (birdY - birdR < gy || birdY + birdR > gyB)) { doGameOver(); return; }

            if (pi.x + pipeW < -10) pipes.remove(i);
        }

        if (birdY + birdR >= H - groundH) { birdY = H - groundH - birdR; doGameOver(); return; }
        if (birdY - birdR < 0) { birdY = birdR; birdVy = 0; }

        stepParticles(dt);
        if (flash != null) { flash.life -= 0.012f * dt; if (flash.life <= 0) flash = null; }
    }

    private void spawnPipe(float gapPx) {
        float min = 90 * S, max = H - groundH - 90 * S - gapPx;
        if (max < min + 10) max = min + 10;
        Pipe np = new Pipe();
        np.x = W + pipeW;
        np.gapY = min + rnd.nextFloat() * (max - min);
        np.label = (rnd.nextFloat() < 0.35f) ? target : pick();
        pipes.add(np);
    }

    private void doGameOver() {
        state = OVER;
        final int fs = score;
        final int stars = fs >= 45 ? 3 : fs >= 25 ? 2 : fs >= 10 ? 1 : 0;
        if (listener != null) post(() -> listener.onGameOver(fs, stars));
    }

    private void stepParticles(float dt) {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle q = particles.get(i);
            q.x += q.vx * dt; q.y += q.vy * dt; q.vy += 0.15f * S * dt; q.life -= 0.02f * dt;
            if (q.life <= 0) particles.remove(i);
        }
    }

    private void burst(float x, float y) {
        for (int i = 0; i < 14; i++) {
            Particle q = new Particle();
            q.x = x; q.y = y;
            q.vx = (rnd.nextFloat() * 6 - 3) * S;
            q.vy = (rnd.nextFloat() * 5 - 4) * S;
            q.life = 1f;
            particles.add(q);
        }
    }

    private static float clamp(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }

    private static int withAlpha(int rgb, float life) {
        int a = (int) (clamp(life, 0, 1) * 255);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private void drawGame(Canvas c) {
        c.drawRect(0, 0, W, H, sky);

        p.setColor(0xB3FFFFFF);
        drawCloud(c, 80 * S + cloudX, 110 * S, 26 * S);
        drawCloud(c, 300 * S + cloudX, 90 * S, 20 * S);
        drawCloud(c, 180 * S + cloudX + W, 160 * S, 22 * S);
        drawCloud(c, 120 * S + cloudX + W, 80 * S, 18 * S);

        float gapPx = gapLogical * S;
        for (Pipe pi : pipes) drawPipe(c, pi, gapPx);

        p.setColor(Color.parseColor("#e7c27d"));
        c.drawRect(0, H - groundH, W, H, p);
        p.setColor(Color.parseColor("#66bb3c"));
        c.drawRect(0, H - groundH, W, H - groundH + 14 * S, p);

        for (Particle q : particles) {
            p.setColor(withAlpha(0xFFD23F, q.life));
            c.drawCircle(q.x, q.y, 4 * S, p);
        }

        drawFlyer(c);

        if (state == PLAYING || state == PAUSED || state == OVER) {
            textStroke.setColor(0xCC12324A); textStroke.setStrokeWidth(6 * S); textStroke.setTextSize(40 * S);
            textFill.setColor(Color.WHITE); textFill.setTextSize(40 * S);
            c.drawText(String.valueOf(score), W / 2f, 66 * S, textStroke);
            c.drawText(String.valueOf(score), W / 2f, 66 * S, textFill);

            float bw = 160 * S, bh = 44 * S, bx = (W - bw) / 2f, by = 78 * S;
            tmp.set(bx, by, bx + bw, by + bh);
            p.setColor(0xEBFF5A7A);
            c.drawRoundRect(tmp, 14 * S, 14 * S, p);
            textFill.setColor(Color.WHITE); textFill.setTextSize(15 * S);
            textFill.setTextAlign(Paint.Align.LEFT);
            c.drawText("CATCH", bx + 16 * S, by + 27 * S, textFill);
            textFill.setTextAlign(Paint.Align.CENTER);
            textFill.setColor(0xFFFFD23F); textFill.setTextSize(28 * S);
            c.drawText(target, bx + bw - 32 * S, by + 31 * S, textFill);
        }

        if (flash != null) {
            float a = clamp(flash.life, 0, 1);
            textStroke.setColor(withAlpha(0xFFFFFF, a)); textStroke.setStrokeWidth(5 * S); textStroke.setTextSize(34 * S);
            textFill.setColor(withAlpha(0xFF5A7A, a)); textFill.setTextSize(34 * S);
            float fy = 200 * S - (1 - flash.life) * 40 * S;
            c.drawText(flash.text, W / 2f, fy, textStroke);
            c.drawText(flash.text, W / 2f, fy, textFill);
        }

        // Draw countdown overlay if active
        if (countdown > 0) {
            // Dim the frozen scene a touch so the number reads clearly.
            p.setColor(0x55000000);
            c.drawRect(0, 0, W, H, p);

            // Number shrinks/fades as its slice of time elapses.
            float prog = countdownT / COUNTDOWN_STEP;      // 0 → 1 within this number
            float scale = 1.4f - 0.4f * prog;
            int alpha = (int) (clamp(1f - prog * 0.6f, 0f, 1f) * 255);

            textStroke.setColor((alpha << 24) | 0xFFFFFF);
            textStroke.setStrokeWidth(8 * S);
            textStroke.setTextSize(120 * S * scale);
            textFill.setColor((alpha << 24) | (0xFF5A7A));
            textFill.setTextSize(120 * S * scale);

            float cy = H / 2f - (textFill.ascent() + textFill.descent()) / 2f;
            String n = String.valueOf(countdown);
            c.drawText(n, W / 2f, cy, textStroke);
            c.drawText(n, W / 2f, cy, textFill);
        }
    }

    private void drawCloud(Canvas c, float x, float y, float r) {
        c.drawCircle(x, y, r, p);
        c.drawCircle(x + r, y + 6 * S, r * 0.8f, p);
        c.drawCircle(x - r, y + 6 * S, r * 0.8f, p);
        c.drawCircle(x + r * 0.4f, y - r * 0.5f, r * 0.7f, p);
    }

    private void drawPipe(Canvas c, Pipe pi, float gapPx) {
        float topH = pi.gapY, botY = pi.gapY + gapPx;
        p.setColor(Color.parseColor("#2ec4b6"));
        stroke.setColor(Color.parseColor("#1a9e91")); stroke.setStrokeWidth(4 * S);

        c.drawRect(pi.x, 0, pi.x + pipeW, topH, p);
        c.drawRect(pi.x, 0, pi.x + pipeW, topH, stroke);
        c.drawRect(pi.x - 6 * S, topH - 26 * S, pi.x + pipeW + 6 * S, topH, p);
        c.drawRect(pi.x - 6 * S, topH - 26 * S, pi.x + pipeW + 6 * S, topH, stroke);

        c.drawRect(pi.x, botY, pi.x + pipeW, H - groundH, p);
        c.drawRect(pi.x, botY, pi.x + pipeW, H - groundH, stroke);
        c.drawRect(pi.x - 6 * S, botY, pi.x + pipeW + 6 * S, botY + 26 * S, p);
        c.drawRect(pi.x - 6 * S, botY, pi.x + pipeW + 6 * S, botY + 26 * S, stroke);

        float cx = pi.x + pipeW / 2f, cy = topH + gapPx / 2f;
        boolean isT = pi.label.equals(target);
        p.setColor(isT ? 0xFFFFD23F : 0xFFFFFFFF);
        c.drawCircle(cx, cy, 26 * S, p);
        stroke.setColor(isT ? 0xFFE0A500 : 0xFF2EC4B6); stroke.setStrokeWidth(4 * S);
        c.drawCircle(cx, cy, 26 * S, stroke);

        textFill.setColor(0xFF12324A);
        textFill.setTextSize((pi.label.length() > 1 ? 24 : 30) * S);
        float baseline = cy - (textFill.ascent() + textFill.descent()) / 2f;
        c.drawText(pi.label, cx, baseline, textFill);
    }

    private void drawFlyer(Canvas c) {
        c.save();
        c.translate(birdX, birdY);
        c.rotate((float) Math.toDegrees(birdRot * 0.4f));
        emojiPaint.setTextSize(36 * S);
        float baseline = -(emojiPaint.ascent() + emojiPaint.descent()) / 2f;
        c.drawText(flyer, 0, baseline, emojiPaint);
        c.restore();
    }

    static class Pipe { float x, gapY; String label; boolean passed; }
    static class Particle { float x, y, vx, vy, life; }
    static class Flash { String text; float life = 1f; Flash(String t) { text = t; } }

    private class GameThread extends Thread {
        private final SurfaceHolder holder;
        private volatile boolean running = false;
        GameThread(SurfaceHolder h) { holder = h; }
        void setRunning(boolean r) { running = r; }

        @Override
        public void run() {
            long last = SystemClock.elapsedRealtime();
            while (running) {
                long now = SystemClock.elapsedRealtime();
                float dt = (now - last) / 16.6667f;
                last = now;
                if (dt > 3) dt = 3;
                update(dt);
                Canvas c = null;
                try {
                    c = holder.lockCanvas();
                    if (c != null) synchronized (holder) { drawGame(c); }
                } finally {
                    if (c != null) try { holder.unlockCanvasAndPost(c); } catch (Exception e) { }
                }
                try { Thread.sleep(4); } catch (InterruptedException e) { }
            }
        }
    }
}