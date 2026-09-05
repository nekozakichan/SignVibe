package com.ucucite.signvibe.ui.learn;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.data.ProgressRepository;
import com.ucucite.signvibe.ui.game.VideoCache;
import com.ucucite.signvibe.ui.quiz.QuizChoiceActivity;
import com.ucucite.signvibe.ui.quiz.QuizTracingActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LessonListActivity extends AppCompatActivity {

    public static final String EXTRA_MODULE_ID = "extra_module_id";
    public static final String EXTRA_MODULE_TITLE = "extra_module_title";
    public static final String EXTRA_MODULE_ICON = "extra_module_icon";

    // Modules that use the tracing quiz (auto-built). Everything else uses the
    // teacher-authored choice quiz.
    private static final Set<String> TRACING_QUIZ_MODULES =
            new HashSet<>(Arrays.asList("alphabet", "numbers"));

    private RecyclerView recyclerLessons;
    private TextView txtEmptyState;
    private LinearLayout btnTakeQuiz;
    private TextView txtQuizLabel;
    private ListenerRegistration lessonsListener;
    private ListenerRegistration progressListener;
    private ListenerRegistration quizResultListener;
    private ListenerRegistration quizQuestionsListener;

    // Background warmer for lesson clips, so tapping a lesson usually plays from disk.
    private ExecutorService prefetchPool;

    private String moduleId;
    private String moduleTitle;

    // Latest snapshot of each data source; UI is rebuilt whenever any changes.
    private List<Lesson> lastLessons = new ArrayList<>();
    private Set<String> lastCompletedLessonIds = new HashSet<>();

    // This student's quiz result for this module.
    private boolean quizPassed = false;
    private int quizPercentage = 0;

    // For choice-quiz modules: how many published questions exist. -1 = not yet
    // loaded. Tracing modules ignore this (they auto-build from lessons).
    private int publishedQuestionCount = -1;

    private boolean isTracingModule() {
        return moduleId != null && TRACING_QUIZ_MODULES.contains(moduleId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lesson_list);

        moduleId = getIntent().getStringExtra(EXTRA_MODULE_ID);
        moduleTitle = getIntent().getStringExtra(EXTRA_MODULE_TITLE);
        String moduleIcon = getIntent().getStringExtra(EXTRA_MODULE_ICON);

        ((TextView) findViewById(R.id.txtModuleIcon)).setText(moduleIcon);
        ((TextView) findViewById(R.id.txtModuleTitle)).setText(moduleTitle);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        recyclerLessons = findViewById(R.id.recyclerLessons);
        // Alphabet & Numbers are single glyphs -> 4 columns fit. Word-based modules
        // (Colors, Greetings, etc.) need wider cells for longer labels -> 2 columns.
        int spanCount = isTracingModule() ? 4 : 2;
        recyclerLessons.setLayoutManager(new GridLayoutManager(this, spanCount));
        recyclerLessons.addItemDecoration(new GridSpacingDecoration(spanCount, dpToPx(12)));
        recyclerLessons.setClipToPadding(false);

        txtEmptyState = findViewById(R.id.txtEmptyState);
        btnTakeQuiz = findViewById(R.id.btnTakeQuiz);
        txtQuizLabel = findViewById(R.id.txtQuizLabel);
        setupQuizButton();

        listenToLessons();
        listenToProgress();
        listenToQuizResult();
        listenToQuizQuestions();
    }

    private void setupQuizButton() {
        btnTakeQuiz.setOnClickListener(v -> {
            if (!btnTakeQuiz.isEnabled()) return;

            Intent intent = isTracingModule()
                    ? new Intent(this, QuizTracingActivity.class)
                    : new Intent(this, QuizChoiceActivity.class);
            intent.putExtra(QuizTracingActivity.EXTRA_MODULE_ID, moduleId);
            intent.putExtra(QuizTracingActivity.EXTRA_MODULE_TITLE, moduleTitle);
            startActivity(intent);
        });
    }

    private void listenToLessons() {
        lessonsListener = FirebaseFirestore.getInstance()
                .collection("lessons")
                .whereEqualTo("module_id", moduleId)
                .whereEqualTo("status", "published")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null || isFinishing()) return;

                    List<Lesson> lessons = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        lessons.add(Lesson.fromFirestore(doc));
                    }
                    Collections.sort(lessons, Comparator.comparingInt(Lesson::getOrder));

                    lastLessons = lessons;
                    prefetchLessonVideos(lessons);
                    refreshLessons();
                });
    }

    /**
     * Warm the shared video cache for every lesson clip in this module, in the
     * background, as soon as the list loads. By the time a learner taps a lesson
     * the clip is usually already on disk, so it plays right away instead of
     * downloading from scratch — the big win on slow or unstable connections.
     * Clips already cached (e.g. from the games) are skipped automatically.
     */
    @OptIn(markerClass = UnstableApi.class)
    private void prefetchLessonVideos(List<Lesson> lessons) {
        if (lessons == null || lessons.isEmpty()) return;
        if (prefetchPool == null || prefetchPool.isShutdown()) {
            prefetchPool = Executors.newFixedThreadPool(2);
        }

        CacheDataSource.Factory factory = new CacheDataSource.Factory()
                .setCache(VideoCache.get(this))
                .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        for (Lesson lesson : lessons) {
            final String url = lesson.getVideoUrl();
            if (TextUtils.isEmpty(url)) continue;
            prefetchPool.execute(() -> {
                try {
                    DataSpec spec = new DataSpec(Uri.parse(url));
                    new CacheWriter(factory.createDataSource(), spec, null, null).cache();
                } catch (Exception ignored) {
                    // Offline or cancelled — the player still fetches on demand when opened.
                }
            });
        }
    }

    private void listenToProgress() {
        progressListener = ProgressRepository.listenToCompletedLessons(completedByModule -> {
            if (isFinishing()) return;
            Set<String> completedIds = completedByModule.get(moduleId);
            lastCompletedLessonIds = completedIds != null ? completedIds : new HashSet<>();
            refreshLessons();
        });
    }

    private void listenToQuizResult() {
        if (moduleId == null) return;
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        String docId = uid + "_" + moduleId;
        quizResultListener = FirebaseFirestore.getInstance()
                .collection("quiz_results")
                .document(docId)
                .addSnapshotListener((doc, error) -> {
                    if (error != null || isFinishing()) return;
                    if (doc != null && doc.exists()) {
                        Boolean passed = doc.getBoolean("passed");
                        Long pct = doc.getLong("percentage");
                        quizPassed = passed != null && passed;
                        quizPercentage = pct != null ? pct.intValue() : 0;
                    } else {
                        quizPassed = false;
                        quizPercentage = 0;
                    }
                    refreshLessons();
                });
    }

    /**
     * For choice-quiz modules, watch how many published questions exist so the
     * button can hide when the teacher hasn't authored any yet. Tracing modules
     * don't need this (they build questions from lessons), so we skip the listen.
     */
    private void listenToQuizQuestions() {
        if (moduleId == null || isTracingModule()) {
            publishedQuestionCount = -1;   // not applicable for tracing
            return;
        }
        quizQuestionsListener = FirebaseFirestore.getInstance()
                .collection("quiz_questions")
                .whereEqualTo("module_id", moduleId)
                .whereEqualTo("status", "published")
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null || snapshots == null || isFinishing()) return;
                    publishedQuestionCount = snapshots.size();
                    refreshLessons();
                });
    }

    private void refreshLessons() {
        // Mark completion, then apply sequential locking: the first lesson is
        // always open; every later lesson unlocks only once the one before it
        // (in order) is completed. lastLessons is already sorted by order.
        for (int i = 0; i < lastLessons.size(); i++) {
            Lesson lesson = lastLessons.get(i);
            lesson.setCompleted(lastCompletedLessonIds.contains(lesson.getId()));

            if (i == 0) {
                lesson.setLocked(false);   // first lesson always open
            } else {
                Lesson prev = lastLessons.get(i - 1);
                boolean prevDone = lastCompletedLessonIds.contains(prev.getId());
                lesson.setLocked(!prevDone);
            }
        }

        if (lastLessons.isEmpty()) {
            recyclerLessons.setVisibility(View.GONE);
            btnTakeQuiz.setVisibility(View.GONE);
            txtEmptyState.setVisibility(View.VISIBLE);
        } else {
            recyclerLessons.setVisibility(View.VISIBLE);
            txtEmptyState.setVisibility(View.GONE);
            recyclerLessons.setAdapter(new LessonAdapter(lastLessons, this::onLessonClick));
            updateQuizButtonState();
        }
    }

    /**
     * Decides whether the quiz button shows, and in what state.
     *
     * Hidden entirely when a choice-quiz module has no published questions yet.
     * Otherwise visible, with three states:
     *   - already passed  -> locked, "Already completed — NN%"
     *   - lessons pending -> locked, "Finish all lessons to unlock the quiz"
     *   - ready           -> enabled, "Take Module Quiz"
     * A failed attempt does NOT lock it — retry until passed.
     */
    private void updateQuizButtonState() {
        if (!isTracingModule() && publishedQuestionCount == 0) {
            btnTakeQuiz.setVisibility(View.GONE);
            return;
        }
        if (!isTracingModule() && publishedQuestionCount < 0) {
            btnTakeQuiz.setVisibility(View.GONE);
            return;
        }

        btnTakeQuiz.setVisibility(View.VISIBLE);

        boolean allLessonsDone = !lastLessons.isEmpty();
        for (Lesson lesson : lastLessons) {
            if (!lastCompletedLessonIds.contains(lesson.getId())) {
                allLessonsDone = false;
                break;
            }
        }

        if (quizPassed) {
            btnTakeQuiz.setEnabled(false);
            btnTakeQuiz.setAlpha(0.6f);
            if (txtQuizLabel != null) {
                txtQuizLabel.setText(getString(R.string.quiz_already_completed_fmt, quizPercentage));
            }
        } else if (!allLessonsDone) {
            btnTakeQuiz.setEnabled(false);
            btnTakeQuiz.setAlpha(0.5f);
            if (txtQuizLabel != null) {
                txtQuizLabel.setText(getString(R.string.quiz_locked_hint));
            }
        } else {
            btnTakeQuiz.setEnabled(true);
            btnTakeQuiz.setAlpha(1f);
            if (txtQuizLabel != null) {
                txtQuizLabel.setText(getString(R.string.take_module_quiz));
            }
        }
    }

    private void onLessonClick(Lesson lesson) {
        // Locked lessons don't fire clicks, but guard anyway.
        if (lesson.isLocked()) return;

        Intent intent = new Intent(this, LessonDetailActivity.class);
        intent.putExtra(LessonDetailActivity.EXTRA_MODULE_ID, moduleId);
        intent.putExtra(LessonDetailActivity.EXTRA_LESSON_ID, lesson.getId());
        intent.putExtra(LessonDetailActivity.EXTRA_LESSON_TITLE, lesson.getTitle());
        intent.putExtra(LessonDetailActivity.EXTRA_LESSON_DESCRIPTION, lesson.getDescription());
        intent.putExtra(LessonDetailActivity.EXTRA_LESSON_VIDEO_URL, lesson.getVideoUrl());
        intent.putExtra(LessonDetailActivity.EXTRA_LESSON_SHORT_LABEL, lesson.getShortLabel());
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lessonsListener != null) { lessonsListener.remove(); lessonsListener = null; }
        if (progressListener != null) { progressListener.remove(); progressListener = null; }
        if (quizResultListener != null) { quizResultListener.remove(); quizResultListener = null; }
        if (quizQuestionsListener != null) { quizQuestionsListener.remove(); quizQuestionsListener = null; }
        if (prefetchPool != null) { prefetchPool.shutdownNow(); prefetchPool = null; }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    /** Even spacing between grid cells and around the grid edges. */
    private static class GridSpacingDecoration extends RecyclerView.ItemDecoration {
        private final int spanCount;
        private final int spacing;

        GridSpacingDecoration(int spanCount, int spacing) {
            this.spanCount = spanCount;
            this.spacing = spacing;
        }

        @Override
        public void getItemOffsets(@NonNull android.graphics.Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            int position = parent.getChildAdapterPosition(view);
            int column = position % spanCount;

            outRect.left = spacing - column * spacing / spanCount;
            outRect.right = (column + 1) * spacing / spanCount;

            if (position < spanCount) {
                outRect.top = spacing;
            }
            outRect.bottom = spacing;
        }
    }
}