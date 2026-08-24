package com.ucucite.signvibe.ui.game;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes Memory Match results to the `game_sessions` collection.
 *
 * One document per completed round (auto-ID) — game_sessions is an EVENT LOG,
 * not a per-student overwrite. A composite ID like {uid}_{difficulty} would
 * clobber replays, and the web "per session" chart would only ever show one bar.
 *
 * Field names here MUST match the web read side (StudentProgress.jsx):
 *   student_id   ← queried with where('student_id','==',studentId)
 *   score        ← still stored (kid win screen); web now charts accuracy/time
 *   duration_ms  ← foreground completion time, plotted in the Time trend
 * Get these wrong and the write "succeeds" while the chart stays empty —
 * the project's recurring stale-/wrong-field trap.
 */
public final class GameSessionRepository {

    private static final String TAG = "GameSessionRepo";
    private static final String COLLECTION = "game_sessions";
    private static final String GAME_TYPE = "memory_match";

    public interface Callback {
        void onResult(boolean success);
    }

    private GameSessionRepository() {}

    /** Fire-and-forget record of one finished round. Safe to call from the UI thread. */
    public static void record(@NonNull GameSession session, Callback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "No signed-in user — game session not recorded.");
            if (callback != null) callback.onResult(false);
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("student_id", user.getUid());              // = uid, matches web query
        data.put("game_type", GAME_TYPE);
        data.put("difficulty", session.getDifficulty());
        data.put("pairs", session.getPairs());
        data.put("moves", session.getMoves());
        data.put("mistakes", session.getMistakes());
        data.put("score", session.getScore());
        data.put("stars", session.getStars());
        data.put("duration_ms", session.getDurationMs());   // foreground completion time
        data.put("played_at", FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .add(data)   // auto-ID: append, never overwrite
                .addOnSuccessListener(ref ->
                        Log.d(TAG, "Game session recorded: " + ref.getId()))
                .addOnFailureListener(e ->
                        // Never silent — a rejected write must surface, not look like success.
                        Log.e(TAG, "Failed to record game session", e))
                .addOnCompleteListener(t -> {
                    if (callback != null) callback.onResult(t.isSuccessful());
                });
    }
}