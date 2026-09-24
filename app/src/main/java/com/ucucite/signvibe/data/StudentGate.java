package com.ucucite.signvibe.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

/**
 * Checks that the signed-in account is actually a student's.
 *
 * Firebase Authentication is one shared pool: the same email/password that
 * opens the SignVibe web admin will also satisfy signInWithEmailAndPassword
 * here. Authentication only proves who you are — it says nothing about which
 * app you belong in — so the app has to check the role itself, against the
 * users/{uid} document the web admin writes.
 *
 * Used at both doors: after a fresh login, and on the splash screen for a
 * session that is already signed in.
 */
public final class StudentGate {

    public interface Callback {
        /** @param reason a message to show the user when allowed is false. */
        void onResult(boolean allowed, @Nullable String reason);
    }

    private StudentGate() { /* no instances */ }

    public static void check(@NonNull Callback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onResult(false, "Please sign in to continue.");
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(user.getUid())
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc == null || !doc.exists()) {
                        callback.onResult(false,
                                "This account isn't set up for the SignVibe app.");
                        return;
                    }

                    String role = doc.getString("role");
                    if (!"student".equals(role)) {
                        callback.onResult(false,
                                "This is a staff account. The SignVibe app is for students — "
                                        + "please use the web portal instead.");
                        return;
                    }

                    String status = doc.getString("status");
                    if ("archived".equals(status)) {
                        callback.onResult(false,
                                "This student account has been archived. Please ask your teacher.");
                        return;
                    }

                    callback.onResult(true, null);
                })
                .addOnFailureListener(e -> callback.onResult(false,
                        "Couldn't verify your account. Check your connection and try again."));
    }

    /** Drop a session that failed the check, so the app doesn't stay half-signed-in. */
    public static void signOut() {
        FirebaseAuth.getInstance().signOut();
    }
}
