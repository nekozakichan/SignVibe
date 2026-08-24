package com.ucucite.signvibe.ui.game;

/** One row in the Flappy Sign leaderboard (read model). */
public class LeaderboardEntry {

    private final String studentId;
    private final String displayName;
    private final int bestScore;
    private int rank;            // 1-based, assigned after ordering
    private boolean currentUser; // true if this row is the signed-in student

    public LeaderboardEntry(String studentId, String displayName, int bestScore) {
        this.studentId = studentId;
        this.displayName = displayName;
        this.bestScore = bestScore;
    }

    public String getStudentId()  { return studentId; }
    public String getDisplayName(){ return displayName; }
    public int getBestScore()     { return bestScore; }
    public int getRank()          { return rank; }
    public void setRank(int rank) { this.rank = rank; }
    public boolean isCurrentUser(){ return currentUser; }
    public void setCurrentUser(boolean v){ currentUser = v; }
}