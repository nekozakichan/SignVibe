package com.ucucite.signvibe.ui.game;

/**
 * One completed round of Flappy Sign — pure gameplay result.
 * Identity (student_id), game_type, and played_at are stamped by
 * FlappySignSessionRepository at write time, mirroring GameSession.
 *
 * Flappy Sign is endless (no "win"), so:
 *   - stars come from score milestones (computed in FlappySignView)
 *   - accuracy is NOT stored here; the dashboard derives it from
 *     targets_caught / targets_shown, exactly as it derives Memory Match
 *     accuracy from raw fields. Store raw, derive on read.
 */
public class FlappySignSession {

    private final int score;          // final score (pipes + target bonuses)
    private final int stars;          // 0–3, from score milestones
    private final int pipesPassed;    // total gaps cleared this round
    private final int targetsShown;   // how many CATCH targets appeared
    private final int targetsCaught;  // targets flown through correctly
    private final long durationMs;    // foreground play time (excludes backgrounded)

    public FlappySignSession(int score, int stars, int pipesPassed,
                             int targetsShown, int targetsCaught, long durationMs) {
        this.score = score;
        this.stars = stars;
        this.pipesPassed = pipesPassed;
        this.targetsShown = targetsShown;
        this.targetsCaught = targetsCaught;
        this.durationMs = durationMs;
    }

    public int getScore() { return score; }
    public int getStars() { return stars; }
    public int getPipesPassed() { return pipesPassed; }
    public int getTargetsShown() { return targetsShown; }
    public int getTargetsCaught() { return targetsCaught; }
    public long getDurationMs() { return durationMs; }
}