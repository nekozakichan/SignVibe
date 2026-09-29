package com.ucucite.signvibe.ui.profile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ucucite.signvibe.SignVibeToast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.signature.ObjectKey;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.data.ProgressRepository;
import com.ucucite.signvibe.data.StudentProfile;
import com.ucucite.signvibe.ui.auth.LoginActivity;
import com.ucucite.signvibe.ui.game.LeaderboardAdapter;
import com.ucucite.signvibe.update.UpdateChecker;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";

    private ListenerRegistration lessonsListener;
    private ListenerRegistration progressListener;
    private ListenerRegistration quizResultsListener;   // feeds both the star total and My Quizzes

    private int totalLessons = 0;
    private int completedLessons = 0;
    private int totalStars = 0;

    private TextView txtLessonsFraction;
    private TextView txtProgressPercent;
    private ProgressBar progressOverall;
    private TextView txtStars;

    private ImageView imgAvatar;
    private ImageView imgAvatarPlaceholder;

    // Leaderboard
    private LeaderboardAdapter leaderboardAdapter;
    private TextView txtMyBest;
    private TextView txtLeaderboardEmpty;

    // My Quizzes
    private QuizHistoryAdapter quizHistoryAdapter;
    private TextView txtQuizSummary;
    private View quizSummaryDivider;
    private TextView txtQuizHistoryEmpty;
    private List<QuizHistoryItem> lastQuizItems = new ArrayList<>();
    private Map<String, String> moduleNames = new HashMap<>();   // moduleId -> display name

    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Must be registered before the fragment reaches STARTED, so do it here (not in onViewCreated).
        pickMedia = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) uploadProfilePhoto(uri);
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        txtLessonsFraction = view.findViewById(R.id.txtLessonsFraction);
        txtProgressPercent = view.findViewById(R.id.txtProgressPercent);
        progressOverall = view.findViewById(R.id.progressOverall);
        txtStars = view.findViewById(R.id.txtStars);   // may be null until added to layout — guarded below
        imgAvatar = view.findViewById(R.id.imgAvatar);
        imgAvatarPlaceholder = view.findViewById(R.id.imgAvatarPlaceholder);

        setupLeaderboard(view);
        setupQuizHistory(view);

        loadStudentProfile(view);
        listenToTotalLessons();
        listenToProgress();
        loadModuleNames();
        listenToQuizResults();

        view.findViewById(R.id.rowAbout).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AboutActivity.class)));

        view.findViewById(R.id.rowHowTo).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), HowToUseActivity.class)));

        setupCheckForUpdates(view);

        view.findViewById(R.id.cameraBadge).setOnClickListener(v ->
                pickMedia.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        view.findViewById(R.id.btnLogout).setOnClickListener(v -> logOut());
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh here so a new best set in Flappy Sign shows on return.
        loadLeaderboard();
    }

    private void setupLeaderboard(@NonNull View view) {
        txtMyBest = view.findViewById(R.id.txtMyBest);
        txtLeaderboardEmpty = view.findViewById(R.id.txtLeaderboardEmpty);
        RecyclerView recycler = view.findViewById(R.id.recyclerLeaderboard);
        if (recycler == null) return;   // guard if the section isn't in the layout yet

        leaderboardAdapter = new LeaderboardAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(leaderboardAdapter);
    }

    private void loadLeaderboard() {
        if (!isAdded() || leaderboardAdapter == null) return;

        LeaderboardRepositorySafeFetch();
    }

    /** Wrapper kept separate so both fetches are guarded by isAdded() on callback. */
    private void LeaderboardRepositorySafeFetch() {
        com.ucucite.signvibe.ui.game.LeaderboardRepository.fetchMyBest(best -> {
            if (!isAdded() || txtMyBest == null) return;
            txtMyBest.setText(getString(R.string.profile_flappy_best_fmt, best));
        });

        com.ucucite.signvibe.ui.game.LeaderboardRepository.fetchTop(10, entries -> {
            if (!isAdded() || leaderboardAdapter == null) return;

            if (entries == null) {
                // Fetch failed (often a missing index while it builds) — don't
                // claim "no scores"; leave whatever's shown and log it.
                Log.w(TAG, "Leaderboard fetch failed; not updating list.");
                return;
            }

            leaderboardAdapter.submit(entries);
            if (txtLeaderboardEmpty != null) {
                txtLeaderboardEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
    }

    private void setupCheckForUpdates(@NonNull View view) {
        String versionName = "1.0";
        try {
            versionName = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) { /* fall back to default */ }

        TextView txtVersion = view.findViewById(R.id.txtUpdateVersion);
        if (txtVersion != null) {
            txtVersion.setText("Version " + versionName);
        }

        // Footer line under Log Out — read the real installed version.
        TextView txtAppVersion = view.findViewById(R.id.txtAppVersion);
        if (txtAppVersion != null) {
            txtAppVersion.setText("SignVibe v" + versionName);
        }

        View row = view.findViewById(R.id.rowCheckUpdate);
        if (row != null) {
            row.setOnClickListener(v -> {
                if (!isAdded()) return;
                SignVibeToast.show(requireContext(), "Checking for updates…");
                // true = also tell the user when they're already up to date.
                UpdateChecker.checkForUpdate(requireActivity(), true);
            });
        }
    }

    private void loadStudentProfile(@NonNull View rootView) {
        TextView txtUserName = rootView.findViewById(R.id.txtUserName);
        TextView txtGradeSection = rootView.findViewById(R.id.txtGradeSection);

        // Fallback shown while Firestore loads (or if the fetch fails)
        txtUserName.setText("Learner");

        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!isAdded() || doc == null || !doc.exists()) return;

                    StudentProfile profile = StudentProfile.fromFirestore(doc);

                    txtUserName.setText(profile.getDisplayName());
                    // Show just the grade level (e.g. "Grade 1"), without the section name.
                    txtGradeSection.setText("Grade " + gradeLevelAsInt(profile.getGradeLevel()));

                    String photoUrl = doc.getString("photo_url");
                    if (photoUrl != null && !photoUrl.isEmpty()) {
                        // Use photo_updated_at as the Glide cache signature so a
                        // replaced photo re-renders. Older docs without the field
                        // fall back to the URL itself.
                        Long photoUpdatedAt = doc.getLong("photo_updated_at");
                        Object signature = photoUpdatedAt != null ? photoUpdatedAt : photoUrl;
                        loadAvatarPhoto(photoUrl, signature);
                    }
                });
    }

    private void loadAvatarPhoto(String url, Object cacheSignature) {
        if (!isAdded() || imgAvatar == null) return;
        imgAvatarPlaceholder.setVisibility(View.GONE);
        imgAvatar.setVisibility(View.VISIBLE);
        Glide.with(this)
                .load(url)
                .signature(new ObjectKey(cacheSignature))
                .circleCrop()
                .into(imgAvatar);
    }

    private void uploadProfilePhoto(Uri uri) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        SignVibeToast.show(requireContext(), "Uploading photo…");

        StorageReference ref = FirebaseStorage.getInstance()
                .getReference()
                .child("profile-photos")
                .child(uid + ".jpg");

        ref.putFile(uri)
                .continueWithTask(task -> {
                    if (!task.isSuccessful() && task.getException() != null) {
                        throw task.getException();
                    }
                    return ref.getDownloadUrl();
                })
                .addOnSuccessListener(downloadUri -> {
                    if (!isAdded()) return;
                    String url = downloadUri.toString();

                    // Fresh signature — changes on every upload, so a replaced
                    // photo re-renders immediately and on later loads instead of
                    // serving the stale cached image.
                    long updatedAt = System.currentTimeMillis();

                    // Show it immediately, then persist url + signature.
                    loadAvatarPhoto(url, updatedAt);

                    Map<String, Object> data = new HashMap<>();
                    data.put("photo_url", url);
                    data.put("photo_updated_at", updatedAt);
                    FirebaseFirestore.getInstance()
                            .collection("users")
                            .document(uid)
                            .set(data, SetOptions.merge())
                            .addOnSuccessListener(unused ->
                                    Log.d(TAG, "photo_url saved to users/" + uid))
                            .addOnFailureListener(e -> {
                                // Most likely PERMISSION_DENIED from Firestore security rules.
                                Log.e(TAG, "Failed to save photo_url — photo won't persist", e);
                                if (isAdded()) SignVibeToast.show(requireContext(),
                                        "Couldn't save photo. Please try again.",
                                        Toast.LENGTH_LONG);
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Photo upload failed", e);
                    if (isAdded()) SignVibeToast.show(requireContext(),
                            "Upload failed. Please try again.", Toast.LENGTH_SHORT);
                });
    }

    /** Same query LearnFragment runs, just summed into a single total instead of per-module. */
    private void listenToTotalLessons() {
        lessonsListener = FirebaseFirestore.getInstance()
                .collection("lessons")
                .whereEqualTo("status", "published")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null || !isAdded()) return;
                    totalLessons = snapshots.size();
                    refreshProgressDisplay();
                });
    }

    private void listenToProgress() {
        progressListener = ProgressRepository.listenToCompletedLessons(completedByModule -> {
            if (!isAdded()) return;
            int count = 0;
            for (Set<String> lessonIds : completedByModule.values()) {
                count += lessonIds.size();
            }
            completedLessons = count;
            refreshProgressDisplay();
        });
    }

    private void setupQuizHistory(@NonNull View view) {
        txtQuizSummary = view.findViewById(R.id.txtQuizSummary);
        quizSummaryDivider = view.findViewById(R.id.quizSummaryDivider);
        txtQuizHistoryEmpty = view.findViewById(R.id.txtQuizHistoryEmpty);
        RecyclerView recycler = view.findViewById(R.id.recyclerQuizHistory);
        if (recycler == null) return;   // guard if the section isn't in the layout yet

        quizHistoryAdapter = new QuizHistoryAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(quizHistoryAdapter);
    }

    /**
     * quiz_results only stores module_id, so read the module names once to show
     * "Greetings & Expressions" rather than "greetings_&_expressions". Uses the
     * same name -> id transform as LearnFragment / the web admin. If this read
     * fails, the adapter falls back to a prettified module_id.
     */
    private void loadModuleNames() {
        FirebaseFirestore.getInstance()
                .collection("modules")
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (!isAdded() || snapshots == null) return;
                    Map<String, String> names = new HashMap<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        String name = doc.getString("name");
                        if (name == null || name.isEmpty()) continue;
                        names.put(name.toLowerCase().replaceAll("\\s", "_"), name);
                    }
                    moduleNames = names;
                    refreshQuizHistory();
                })
                .addOnFailureListener(e -> Log.w(TAG, "Module names fetch failed", e));
    }

    /**
     * One listener on this student's quiz_results drives both the Stars Earned
     * total and the My Quizzes list. quiz_results is keep-best (one doc per
     * module), so each row is the student's best attempt at that quiz.
     */
    private void listenToQuizResults() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            refreshQuizHistory();   // show the empty state instead of a blank card
            return;
        }

        quizResultsListener = FirebaseFirestore.getInstance()
                .collection("quiz_results")
                .whereEqualTo("student_id", uid)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        Log.w(TAG, "quiz_results listener failed", error);
                        return;
                    }
                    if (snapshots == null || !isAdded()) return;

                    int stars = 0;
                    List<QuizHistoryItem> items = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        Long s = doc.getLong("stars_earned");
                        if (s != null) stars += s.intValue();

                        QuizHistoryItem item = QuizHistoryItem.fromFirestore(doc);
                        if (item != null) items.add(item);
                    }

                    // Most recent first; a missing date sinks to the bottom.
                    items.sort((a, b) -> {
                        Date da = a.getCompletedAt();
                        Date db = b.getCompletedAt();
                        if (da == null && db == null) return 0;
                        if (da == null) return 1;
                        if (db == null) return -1;
                        return db.compareTo(da);
                    });

                    totalStars = stars;
                    refreshStarsDisplay();

                    lastQuizItems = items;
                    refreshQuizHistory();
                });
    }

    private void refreshQuizHistory() {
        if (!isAdded() || quizHistoryAdapter == null) return;

        quizHistoryAdapter.submit(lastQuizItems, moduleNames);

        int taken = lastQuizItems.size();
        int passed = 0;
        for (QuizHistoryItem item : lastQuizItems) {
            if (item.isPassed()) passed++;
        }

        boolean empty = taken == 0;
        if (txtQuizHistoryEmpty != null) {
            txtQuizHistoryEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (quizSummaryDivider != null) {
            quizSummaryDivider.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
        if (txtQuizSummary != null) {
            txtQuizSummary.setVisibility(empty ? View.GONE : View.VISIBLE);
            String takenText = getResources().getQuantityString(
                    R.plurals.quiz_history_taken, taken, taken);
            txtQuizSummary.setText(getString(R.string.quiz_history_summary_fmt, takenText, passed));
        }
    }

    private void refreshProgressDisplay() {
        // A lesson removed/unpublished shouldn't inflate the count past the total.
        int completed = Math.min(completedLessons, totalLessons);
        int percent = totalLessons == 0 ? 0 : Math.round((completed / (float) totalLessons) * 100);

        if (txtLessonsFraction != null) {
            txtLessonsFraction.setText(String.format(Locale.US, "%d/%d", completed, totalLessons));
        }
        if (txtProgressPercent != null) {
            txtProgressPercent.setText(String.format(Locale.US, "%d%%", percent));
        }
        if (progressOverall != null) {
            progressOverall.setMax(100);
            // Animate the fill on API 24+, fall back to an instant set below it.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                progressOverall.setProgress(percent, true);
            } else {
                progressOverall.setProgress(percent);
            }
        }
    }

    private void refreshStarsDisplay() {
        if (txtStars != null) {
            txtStars.setText(String.valueOf(totalStars));
        }
    }

    private int gradeLevelAsInt(String gradeLevel) {
        try {
            return Integer.parseInt(gradeLevel);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void logOut() {
        FirebaseAuth.getInstance().signOut();

        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        requireActivity().finish();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        imgAvatar = null;
        imgAvatarPlaceholder = null;
        leaderboardAdapter = null;
        txtMyBest = null;
        txtLeaderboardEmpty = null;
        quizHistoryAdapter = null;
        txtQuizSummary = null;
        quizSummaryDivider = null;
        txtQuizHistoryEmpty = null;
        if (lessonsListener != null) {
            lessonsListener.remove();
            lessonsListener = null;
        }
        if (progressListener != null) {
            progressListener.remove();
            progressListener = null;
        }
        if (quizResultsListener != null) {
            quizResultsListener.remove();
            quizResultsListener = null;
        }
    }
}