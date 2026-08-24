package com.ucucite.signvibe.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes a student's lesson-completion progress.
 *
 * Firestore path: student_progress/{docId}   (top-level collection, matches
 * the schema the web admin's StudentProgress.jsx already queries)
 *   - student_id   (string)  - matches the pattern of game_sessions
 *   - lesson_id    (string)
 *   - module_id    (string)  - not read by the admin panel today, but kept
 *                              so the app can group completion by module
 *   - is_completed (boolean)
 *   - score        (number)  - left unset for now; reserved for quizzes
 *   - completed_at (server timestamp)
 *
 * docId is deterministic ({uid}_{lessonId}) purely so re-marking a lesson
 * complete updates the same doc instead of creating duplicates - the admin
 * panel doesn't care about the ID, only the fields above.
 */
public class ProgressRepository {

    public interface OnProgressLoaded {
        /** moduleId -> set of completed lessonIds within that module */
        void onLoaded(@NonNull Map<String, Set<String>> completedByModule);
    }

    private static String docId(String uid, String lessonId) {
        return uid + "_" + lessonId;
    }

    @Nullable
    private static String currentUid() {
        return FirebaseAuth.getInstance().getUid();
    }

    /** Marks a single lesson as completed for the signed-in student. */
    public static void markLessonComplete(@NonNull String moduleId, @NonNull String lessonId) {
        String uid = currentUid();
        if (uid == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("student_id", uid);
        data.put("lesson_id", lessonId);
        data.put("module_id", moduleId);
        data.put("is_completed", true);
        data.put("completed_at", FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance()
                .collection("student_progress")
                .document(docId(uid, lessonId))
                .set(data, SetOptions.merge());
    }

    /**
     * Listens to the signed-in student's completed-lesson docs and calls back
     * with the full moduleId -> completed lessonIds map whenever it changes.
     * Returns null if no student is signed in. Caller must remove the
     * returned listener (e.g. in onDestroy/onDestroyView).
     */
    @Nullable
    public static ListenerRegistration listenToCompletedLessons(@NonNull OnProgressLoaded callback) {
        String uid = currentUid();
        if (uid == null) return null;

        return FirebaseFirestore.getInstance()
                .collection("student_progress")
                .whereEqualTo("student_id", uid)
                .whereEqualTo("is_completed", true)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) return;

                    Map<String, Set<String>> completedByModule = new HashMap<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        String moduleId = doc.getString("module_id");
                        String lessonId = doc.getString("lesson_id");
                        if (moduleId == null || lessonId == null) continue;

                        completedByModule
                                .computeIfAbsent(moduleId, k -> new HashSet<>())
                                .add(lessonId);
                    }
                    callback.onLoaded(completedByModule);
                });
    }
}