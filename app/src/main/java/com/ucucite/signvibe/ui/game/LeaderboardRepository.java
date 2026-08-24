package com.ucucite.signvibe.ui.game;

import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.ucucite.signvibe.data.StudentProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Best-score leaderboard for Flappy Sign.
 *
 * Storage model is KEEP-BEST (one doc per user), NOT the append-only
 * event log of game_sessions:
 *   collection: leaderboard
 *   doc id:     flappy_sign_{uid}   (composite, mirrors quiz_results)
 *
 * submitScore() only writes when the new round beats the stored best,
 * so replays never lower a rank and the list stays "one row per person".
 *
 * display_name is DENORMALIZED into the leaderboard doc at write time so
 * fetchTop() is a single ordered query — no N extra reads of users docs.
 * The name is resolved via StudentProfile (same source as the profile
 * header) so the leaderboard shows exactly the name the child sees.
 */
public final class LeaderboardRepository {

    private static final String TAG = "LeaderboardRepo";
    private static final String COLLECTION = "leaderboard";
    private static final String GAME_TYPE = "flappy_sign";

    public interface SubmitCallback { void onResult(boolean success, boolean newBest); }
    public interface FetchCallback  { void onResult(List<LeaderboardEntry> entries); }
    public interface MyBestCallback { void onResult(int bestScore); }

    private LeaderboardRepository() {}

    private static String docId(String uid) { return GAME_TYPE + "_" + uid; }

    /** Keep-best write. Reads current best first; only writes if score is higher. */
    public static void submitScore(int score, SubmitCallback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.w(TAG, "No signed-in user — score not submitted.");
            if (cb != null) cb.onResult(false, false);
            return;
        }
        final String uid = user.getUid();
        final FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection(COLLECTION).document(docId(uid)).get()
                .addOnSuccessListener(snap -> {
                    long existing = (snap.exists() && snap.getLong("best_score") != null)
                            ? snap.getLong("best_score") : -1;

                    if (existing >= score) {
                        if (cb != null) cb.onResult(true, false);   // not a new best
                        return;
                    }
                    // New best: resolve name from users (via StudentProfile), then upsert.
                    db.collection("users").document(uid).get()
                            .addOnSuccessListener(u -> {
                                String name = fallbackName(user);
                                try {
                                    StudentProfile prof = StudentProfile.fromFirestore(u);
                                    if (prof != null && prof.getDisplayName() != null
                                            && !prof.getDisplayName().trim().isEmpty()) {
                                        name = prof.getDisplayName();
                                    }
                                } catch (Exception ignored) { /* keep fallback */ }
                                writeBest(db, uid, score, name, cb);
                            })
                            .addOnFailureListener(e -> writeBest(db, uid, score, fallbackName(user), cb));
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to read current best", e);
                    if (cb != null) cb.onResult(false, false);
                });
    }

    private static void writeBest(FirebaseFirestore db, String uid, int score,
                                  String name, SubmitCallback cb) {
        Map<String, Object> data = new HashMap<>();
        data.put("student_id", uid);
        data.put("game_type", GAME_TYPE);
        data.put("display_name", name);
        data.put("best_score", score);
        data.put("updated_at", FieldValue.serverTimestamp());

        db.collection(COLLECTION).document(docId(uid))
                .set(data)
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "New best recorded: " + score);
                    if (cb != null) cb.onResult(true, true);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to write best score", e);
                    if (cb != null) cb.onResult(false, false);
                });
    }

    private static String fallbackName(FirebaseUser user) {
        String n = user.getDisplayName();
        return (n != null && !n.trim().isEmpty()) ? n : "Player";
    }

    /**
     * Top-N leaderboard, ordered high→low, with rank + "you" flag filled in.
     * Orders by best_score only (auto single-field index) and filters game_type
     * client-side, so it needs NO composite index.
     */
    public static void fetchTop(int limit, FetchCallback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        final String myUid = user != null ? user.getUid() : null;

        FirebaseFirestore.getInstance()
                .collection(COLLECTION)
                .orderBy("best_score", Query.Direction.DESCENDING)
                .limit(100)   // grab a chunk; filter to this game below
                .get()
                .addOnSuccessListener(qs -> {
                    List<LeaderboardEntry> list = new ArrayList<>();
                    int rank = 1;
                    for (QueryDocumentSnapshot d : qs) {
                        // Client-side filter keeps this a single-field query.
                        if (!GAME_TYPE.equals(d.getString("game_type"))) continue;
                        if (list.size() >= limit) break;

                        Long bs = d.getLong("best_score");
                        LeaderboardEntry e = new LeaderboardEntry(
                                d.getString("student_id"),
                                d.getString("display_name"),
                                bs != null ? bs.intValue() : 0);
                        e.setRank(rank++);
                        e.setCurrentUser(myUid != null && myUid.equals(e.getStudentId()));
                        list.add(e);
                    }
                    Log.d(TAG, "Leaderboard fetched rows: " + list.size());
                    if (cb != null) cb.onResult(list);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to fetch leaderboard", e);
                    if (cb != null) cb.onResult(null);   // null = error, distinct from empty list
                });
    }

    /** The signed-in student's own best (0 if they've never played). */
    public static void fetchMyBest(MyBestCallback cb) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) { if (cb != null) cb.onResult(0); return; }

        FirebaseFirestore.getInstance()
                .collection(COLLECTION).document(docId(user.getUid())).get()
                .addOnSuccessListener(snap -> {
                    Long bs = snap.exists() ? snap.getLong("best_score") : null;
                    if (cb != null) cb.onResult(bs != null ? bs.intValue() : 0);
                })
                .addOnFailureListener(e -> { if (cb != null) cb.onResult(0); });
    }
}