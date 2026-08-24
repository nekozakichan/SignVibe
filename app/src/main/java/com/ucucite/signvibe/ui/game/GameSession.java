package com.ucucite.signvibe.ui.game;

/**
 * One completed round of Memory Match — pure gameplay result.
 * Identity (student_id), game_type, and played_at are stamped by
 * GameSessionRepository at write time, so this holder stays about
 * the round itself and nothing else.
 */
public class GameSession {

    private final String difficulty;   // "easy" | "medium" | "hard"
    private final int pairs;           // pairs actually on the board this round
    private final int moves;           // tries taken (minimum possible == pairs)
    private final int mistakes;        // extra tries beyond the minimum
    private final int score;           // computed points (always >= 0)
    private final int stars;           // 0–3, identical to the win-screen stars
    private final long durationMs;     // foreground time to finish (excludes backgrounded time)

    public GameSession(String difficulty, int pairs, int moves,
                       int mistakes, int score, int stars, long durationMs) {
        this.difficulty = difficulty;
        this.pairs = pairs;
        this.moves = moves;
        this.mistakes = mistakes;
        this.score = score;
        this.stars = stars;
        this.durationMs = durationMs;
    }

    public String getDifficulty() { return difficulty; }
    public int getPairs() { return pairs; }
    public int getMoves() { return moves; }
    public int getMistakes() { return mistakes; }
    public int getScore() { return score; }
    public int getStars() { return stars; }
    public long getDurationMs() { return durationMs; }
}