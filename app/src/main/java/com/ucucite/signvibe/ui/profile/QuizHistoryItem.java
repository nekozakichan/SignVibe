package com.ucucite.signvibe.ui.profile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.ucucite.signvibe.ui.quiz.QuizStars;

import java.util.Date;

/**
 * One row in the Profile tab's "My Quizzes" list, read from quiz_results.
 *
 * quiz_results holds ONE doc per student per module ({uid}_{moduleId}) and is
 * keep-best — a weaker retake never overwrites it — so each item is the
 * student's best attempt at that module's quiz.
 *
 * Older docs may be missing stars_earned / percentage / passed; those are
 * derived from score + total_questions so legacy rows still display correctly.
 */
public class QuizHistoryItem {

    private static final int PASS_PERCENTAGE = 70;   // same pass mark the quizzes use

    private final String moduleId;
    private final int score;
    private final int total;
    private final int percentage;
    private final int stars;
    private final boolean passed;
    @Nullable private final Date completedAt;   // null only if the timestamp is missing

    private QuizHistoryItem(String moduleId, int score, int total, int percentage,
                            int stars, boolean passed, @Nullable Date completedAt) {
        this.moduleId = moduleId;
        this.score = score;
        this.total = total;
        this.percentage = percentage;
        this.stars = stars;
        this.passed = passed;
        this.completedAt = completedAt;
    }

    @Nullable
    public static QuizHistoryItem fromFirestore(@NonNull DocumentSnapshot doc) {
        String moduleId = doc.getString("module_id");
        if (moduleId == null || moduleId.isEmpty()) return null;

        int score = intOf(doc.getLong("score"), 0);
        int total = intOf(doc.getLong("total_questions"), 0);

        Long pct = doc.getLong("percentage");
        int percentage = pct != null
                ? pct.intValue()
                : (total == 0 ? 0 : Math.round((score / (float) total) * 100));

        Long st = doc.getLong("stars_earned");
        int stars = st != null ? st.intValue() : QuizStars.starsForPercentage(percentage);
        stars = Math.max(0, Math.min(3, stars));

        Boolean p = doc.getBoolean("passed");
        boolean passed = p != null ? p : percentage >= PASS_PERCENTAGE;

        // ESTIMATE so a result saved seconds ago (server timestamp still pending)
        // shows a date instead of nothing.
        Timestamp ts = doc.getTimestamp("completed_at",
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE);
        Date completedAt = ts != null ? ts.toDate() : null;

        return new QuizHistoryItem(moduleId, score, total, percentage, stars, passed, completedAt);
    }

    private static int intOf(@Nullable Long value, int fallback) {
        return value != null ? value.intValue() : fallback;
    }

    public String getModuleId() { return moduleId; }
    public int getScore() { return score; }
    public int getTotal() { return total; }
    public int getPercentage() { return percentage; }
    public int getStars() { return stars; }
    public boolean isPassed() { return passed; }
    @Nullable public Date getCompletedAt() { return completedAt; }
}
