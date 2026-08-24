package com.ucucite.signvibe.ui.quiz;

/** Shared star-tier logic for both quiz types. */
public final class QuizStars {

    private QuizStars() {}

    /**
     * Stars earned for a quiz percentage:
     *   100%     -> 3 stars
     *   80-99%   -> 2 stars
     *   70-79%   -> 1 star  (70 is the pass mark)
     *   below 70 -> 0 stars (failed)
     */
    public static int starsForPercentage(int percentage) {
        if (percentage >= 100) return 3;
        if (percentage >= 80) return 2;
        if (percentage >= 70) return 1;
        return 0;
    }
}