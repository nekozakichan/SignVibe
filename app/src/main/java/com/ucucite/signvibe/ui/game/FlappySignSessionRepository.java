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
 * Writes Flappy Sign results to the `game_sessions` collection.
 *
 * Same collection and event-log discipline as GameSessionRepository
 * (memory_match): one auto-ID document per finished round, append-only,
 * never a composite overwrite.
 *
 * SHARED fields match the memory writer exactly so the web query
 *   where('student_id','==',studentId)   still catches these rows:
 *     student_id, game_type, score, stars, duration_ms, played_at
 *
 * FLAPPY-SPECIFIC raw fields (web derives accuracy from these):
 *     pipes_passed, targets_shown, targets_caught
 *
 * DASHBOARD ACTION REQUIRED (write-before-read): StudentProgress.jsx must
 * add a game_type === 'flappy_sign' branch computing
 *     accuracy = targets_shown > 0 ? targets_caught / targets_shown : 0
 * Without it the write succeeds but the accuracy bar stays empty — the
 * project's recurring stale-/wrong-field trap.
 */
public final class FlappySignSessionRepository {

    private static final String TAG = "FlappySignSessionRepo";
    private static final String COLLECTION = "game_sessions";
    private static final String GAME_TYPE = "flappy_sign";

    public interface Callback {
        void onResult(boolean success);
    }

    private FlappySignSessionRepository() {}

    /** Fire-and-forget record of one finished round. Safe to call from the UI thread. */
    public static void record(@NonNull FlappySignSession session, Callback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "No signed-in user — Flappy Sign session not recorded.");
            if (callback != null) callback.onResult(false);
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("student_id", user.getUid());              // = uid, matches web query
        data.put("game_type", GAME_TYPE);
        data.put("score", session.getScore());
        data.put("stars", session.getStars());
        data.put("pipes_passed", session.getPipesPassed());
        data.put("targets_shown", session.getTargetsShown());
        data.put("targets_caught", session.getTargetsCaught());
        data.put("duration_ms", session.getDurationMs());   // foreground play time
        data.put("played_at", FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .add(data)   // auto-ID: append, never overwrite
                .addOnSuccessListener(ref ->
                        Log.d(TAG, "Flappy Sign session recorded: " + ref.getId()))
                .addOnFailureListener(e ->
                        Log.e(TAG, "Failed to record Flappy Sign session", e))
                .addOnCompleteListener(t -> {
                    if (callback != null) callback.onResult(t.isSuccessful());
                });
    }
}