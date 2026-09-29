package com.ucucite.signvibe.ui.quiz;

import androidx.annotation.NonNull;

import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Reads settings/quiz_limits — the one document that decides how many items a
 * student answers, shared with the SignVibe web admin.
 *
 * Read fresh at the start of every quiz rather than cached for the life of the
 * app. A teacher who changes the numbers expects the next quiz to use them, and
 * a phone left running in a bag can keep an Android process alive for days —
 * caching would have meant that student sitting the old length indefinitely.
 * The cost is one small document read per quiz, which is nothing next to the
 * lesson videos the app already streams.
 *
 * Any failure falls back to {@link QuizLimits#defaults()}, so a quiz never fails
 * to start because a settings read didn't come back.
 */
public final class QuizLimitsRepository {

    public interface Callback {
        void onLoaded(@NonNull QuizLimits limits);
    }

    private static final String COLLECTION = "settings";
    private static final String DOCUMENT = "quiz_limits";

    private QuizLimitsRepository() { /* no instances */ }

    public static void load(@NonNull Callback callback) {
        FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .document(DOCUMENT)
                .get()
                .addOnSuccessListener(doc -> callback.onLoaded(QuizLimits.fromSnapshot(doc)))
                .addOnFailureListener(e -> callback.onLoaded(QuizLimits.defaults()));
    }
}
