package com.ucucite.signvibe.ui.quiz;

import androidx.annotation.NonNull;

import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Reads settings/quiz_limits — the one document that decides how many items a
 * student answers, shared with the SignVibe web admin.
 *
 * Cached for the life of the process: the numbers change rarely, and a student
 * opening three quizzes in a row shouldn't cost three reads. Any failure falls
 * back to {@link QuizLimits#defaults()} so a quiz never fails to start because
 * a settings read didn't come back.
 */
public final class QuizLimitsRepository {

    public interface Callback {
        void onLoaded(@NonNull QuizLimits limits);
    }

    private static final String COLLECTION = "settings";
    private static final String DOCUMENT = "quiz_limits";

    private static QuizLimits cached;

    private QuizLimitsRepository() { /* no instances */ }

    public static void load(@NonNull Callback callback) {
        if (cached != null) {
            callback.onLoaded(cached);
            return;
        }

        FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(DOCUMENT)
                .get()
                .addOnSuccessListener(doc -> {
                    cached = QuizLimits.fromSnapshot(doc);
                    callback.onLoaded(cached);
                })
                .addOnFailureListener(e -> callback.onLoaded(QuizLimits.defaults()));
    }

    /** Drop the cache so the next load re-reads the document. */
    public static void invalidate() {
        cached = null;
    }
}
