package com.ucucite.signvibe.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which modules students may see.
 *
 * Teachers show/hide a module from the web panel (Manage Modules → eye button),
 * which saves {@code visible: false} on the module document. Modules created
 * before that switch existed have no "visible" field and count as visible —
 * which is why this checks for an explicit {@code false} and why no screen
 * should query with {@code whereEqualTo("visible", true)}.
 *
 * Archived modules (status "archived") are also unavailable to students, as
 * LearnFragment has always done.
 */
public final class ModuleVisibility {

    private ModuleVisibility() {}

    public interface OnUnavailableModules {
        /** Module IDs (e.g. "greetings") students must not see right now. */
        void onLoaded(@NonNull Set<String> unavailableModuleIds);
    }

    /** True when a teacher has hidden this module from students. */
    public static boolean isHidden(@NonNull DocumentSnapshot moduleDoc) {
        return Boolean.FALSE.equals(moduleDoc.getBoolean("visible"));
    }

    /** True when students must not see this module: hidden or archived. */
    public static boolean isUnavailable(@NonNull DocumentSnapshot moduleDoc) {
        return isHidden(moduleDoc) || "archived".equals(moduleDoc.getString("status"));
    }

    /**
     * Module ID from its name — same transform as the web admin and
     * LearnFragment ("Common Words" → "common_words"), which is what lessons
     * store as module_id.
     */
    @NonNull
    public static String moduleIdOf(@Nullable String name) {
        if (name == null) return "";
        return name.toLowerCase().replaceAll("\\s", "_");
    }

    /**
     * Live: calls back now and whenever a teacher hides/shows/archives a
     * module. Caller must remove() the returned registration.
     */
    @NonNull
    public static ListenerRegistration listenToUnavailableModuleIds(@NonNull OnUnavailableModules callback) {
        return FirebaseFirestore.getInstance()
                .collection("modules")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null) return;
                    callback.onLoaded(collect(snapshots));
                });
    }

    /** One-off read. On failure, reports an empty set so the screen still works. */
    public static void fetchUnavailableModuleIds(@NonNull OnUnavailableModules callback) {
        FirebaseFirestore.getInstance()
                .collection("modules")
                .get()
                .addOnSuccessListener(snapshots -> callback.onLoaded(collect(snapshots)))
                .addOnFailureListener(e -> callback.onLoaded(Collections.emptySet()));
    }

    @NonNull
    private static Set<String> collect(@NonNull Iterable<QueryDocumentSnapshot> snapshots) {
        Set<String> ids = new HashSet<>();
        for (QueryDocumentSnapshot doc : snapshots) {
            if (isUnavailable(doc)) ids.add(moduleIdOf(doc.getString("name")));
        }
        return ids;
    }
}
