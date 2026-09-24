package com.ucucite.signvibe.ui.quiz;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.SignVibeToast;
import com.ucucite.signvibe.ui.game.VideoCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QuizChoiceActivity extends AppCompatActivity {

    public static final String EXTRA_MODULE_ID = "extra_module_id";
    public static final String EXTRA_MODULE_TITLE = "extra_module_title";

    private static final int PASS_PERCENTAGE = 70;
    private static final int POINTS_ON_PASS = 50;
    private static final long FEEDBACK_DELAY_MS = 1100;

    private static class Question {
        final String videoUrl;
        final String correctAnswer;
        final List<String> choices;

        Question(String videoUrl, String correctAnswer, List<String> choices) {
            this.videoUrl = videoUrl;
            this.correctAnswer = correctAnswer;
            this.choices = choices;
        }
    }

    private PlayerView playerView;
    private ExoPlayer player;
    private TextView txtProgress;
    private final TextView[] choiceButtons = new TextView[4];

    private String moduleId;
    private String moduleTitle;

    /** Decides how many items this student answers. See QuizLimits. */
    private String gradeLevel;
    private QuizLimits quizLimits = QuizLimits.defaults();

    private final List<Question> questions = new ArrayList<>();
    private int currentIndex = 0;
    private int correctCount = 0;
    private boolean answered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz_choice);

        moduleId = getIntent().getStringExtra(EXTRA_MODULE_ID);
        moduleTitle = getIntent().getStringExtra(EXTRA_MODULE_TITLE);

        ((TextView) findViewById(R.id.txtQuizTitle)).setText(
                getString(R.string.quiz_choice_title));
        txtProgress = findViewById(R.id.txtProgress);
        playerView = findViewById(R.id.playerView);

        choiceButtons[0] = findViewById(R.id.btnChoice1);
        choiceButtons[1] = findViewById(R.id.btnChoice2);
        choiceButtons[2] = findViewById(R.id.btnChoice3);
        choiceButtons[3] = findViewById(R.id.btnChoice4);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnReplay).setOnClickListener(v -> replayVideo());

        for (TextView btn : choiceButtons) {
            btn.setOnClickListener(v -> onChoiceTapped((TextView) v));
        }

        setupPlayer();
        loadLimitsThenQuestions();
    }

    /**
     * Quiz length comes from settings/quiz_limits (shared with the web admin)
     * combined with this student's grade. Both reads fall back to safe defaults
     * rather than blocking the quiz.
     */
    private void loadLimitsThenQuestions() {
        QuizLimitsRepository.load(limits -> {
            if (isFinishing()) return;
            quizLimits = limits;
            loadGradeThenQuestions();
        });
    }

    private void loadGradeThenQuestions() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            loadQuestions();
            return;
        }

        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener(doc -> {
                    if (isFinishing()) return;
                    if (doc != null && doc.exists()) {
                        gradeLevel = doc.getString("grade_level");
                    }
                    loadQuestions();
                })
                .addOnFailureListener(e -> {
                    if (isFinishing()) return;
                    loadQuestions();
                });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void setupPlayer() {
        // Cache-backed source (shared with lessons/games) + fast-start buffering,
        // so quiz clips load quickly and play from disk once cached/prefetched.
        CacheDataSource.Factory cacheFactory = new CacheDataSource.Factory()
                .setCache(VideoCache.get(this))
                .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheFactory))
                .setLoadControl(fastStartLoadControl())
                .build();
        playerView.setPlayer(player);
        playerView.setUseController(false);
        player.setVolume(0f); // mute — same clips as lessons; avoid background noise
        player.setPlayWhenReady(true);
    }

    /** Start playback after a tiny buffer instead of a big cushion (fast first paint). */
    private LoadControl fastStartLoadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 30000, 250, 500)
                .build();
    }

    private void loadQuestions() {
        FirebaseFirestore.getInstance()
                .collection("quiz_questions")
                .whereEqualTo("module_id", moduleId)
                .whereEqualTo("status", "published")
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (isFinishing()) return;

                    List<QueryDocumentSnapshot> docs = new ArrayList<>();
                    for (QueryDocumentSnapshot d : snapshots) docs.add(d);
                    Collections.sort(docs, (a, b) -> {
                        Long oa = a.getLong("order");
                        Long ob = b.getLong("order");
                        return Long.compare(oa != null ? oa : 0, ob != null ? ob : 0);
                    });

                    List<Question> loaded = new ArrayList<>();
                    for (QueryDocumentSnapshot d : docs) {
                        String videoUrl = d.getString("video_url");
                        String correct = d.getString("correct_answer");
                        @SuppressWarnings("unchecked")
                        List<String> choices = (List<String>) d.get("choices");
                        if (correct == null || choices == null || choices.isEmpty()) continue;
                        loaded.add(new Question(
                                videoUrl != null ? videoUrl : "",
                                correct,
                                new ArrayList<>(choices)));
                    }

                    if (loaded.isEmpty()) {
                        SignVibeToast.show(this, "No quiz questions yet for this module.",
                                Toast.LENGTH_SHORT);
                        finish();
                        return;
                    }

                    // Grade 1 sits 5 items, Grade 6 sits 20 — a random pick from
                    // the module, kept in the teacher's order.
                    questions.clear();
                    questions.addAll(quizLimits.limitForGrade(loaded, gradeLevel));
                    currentIndex = 0;
                    correctCount = 0;
                    showCurrentQuestion();
                })
                .addOnFailureListener(e -> {
                    if (isFinishing()) return;
                    SignVibeToast.show(this, "Couldn't load the quiz. Try again.",
                            Toast.LENGTH_SHORT);
                    finish();
                });
    }

    private void showCurrentQuestion() {
        answered = false;
        Question q = questions.get(currentIndex);

        txtProgress.setText(getString(
                R.string.quiz_tracing_progress_fmt, currentIndex + 1, questions.size()));

        if (!TextUtils.isEmpty(q.videoUrl)) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(q.videoUrl)));
            player.prepare();
            player.play();
        }

        List<String> shuffled = new ArrayList<>(q.choices);
        Collections.shuffle(shuffled);

        for (int i = 0; i < choiceButtons.length; i++) {
            TextView btn = choiceButtons[i];
            if (i < shuffled.size()) {
                btn.setVisibility(View.VISIBLE);
                btn.setText(shuffled.get(i));
                btn.setTag(shuffled.get(i));
                resetChoiceStyle(btn);
                btn.setEnabled(true);
            } else {
                btn.setVisibility(View.GONE);
            }
        }
    }

    private void onChoiceTapped(TextView tapped) {
        if (answered) return;
        answered = true;

        Question q = questions.get(currentIndex);
        String chosen = (String) tapped.getTag();
        boolean isCorrect = chosen != null && chosen.equals(q.correctAnswer);

        if (isCorrect) {
            correctCount++;
            styleCorrect(tapped);
        } else {
            styleWrong(tapped);
            for (TextView btn : choiceButtons) {
                if (q.correctAnswer.equals(btn.getTag())) {
                    styleCorrect(btn);
                }
            }
        }

        for (TextView btn : choiceButtons) btn.setEnabled(false);

        handler.postDelayed(() -> {
            if (isFinishing()) return;
            if (currentIndex < questions.size() - 1) {
                currentIndex++;
                showCurrentQuestion();
            } else {
                finishQuiz();
            }
        }, FEEDBACK_DELAY_MS);
    }

    private void finishQuiz() {
        int total = questions.size();
        int percentage = total == 0 ? 0 : Math.round((correctCount / (float) total) * 100);
        boolean passed = percentage >= PASS_PERCENTAGE;
        int stars = QuizStars.starsForPercentage(percentage);

        saveResult(total, percentage, passed, stars);
        showResultScreen(total, percentage, passed, stars);
        finish();
    }

    private void showResultScreen(int total, int percentage, boolean passed, int stars) {
        Intent intent = new Intent(this, QuizResultActivity.class);
        intent.putExtra(QuizResultActivity.EXTRA_CORRECT, correctCount);
        intent.putExtra(QuizResultActivity.EXTRA_TOTAL, total);
        intent.putExtra(QuizResultActivity.EXTRA_PERCENTAGE, percentage);
        intent.putExtra(QuizResultActivity.EXTRA_PASSED, passed);
        intent.putExtra(QuizResultActivity.EXTRA_STARS, stars);
        startActivity(intent);
    }

    private void saveResult(int total, int percentage, boolean passed, int stars) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String docId = uid + "_" + moduleId;

        db.collection("quiz_results").document(docId).get()
                .addOnSuccessListener(existing -> {
                    if (existing != null && existing.exists()) {
                        Long prev = existing.getLong("percentage");
                        if (prev != null && prev >= percentage) return;
                    }

                    Map<String, Object> data = new HashMap<>();
                    data.put("student_id", uid);
                    data.put("module_id", moduleId);
                    data.put("score", correctCount);
                    data.put("total_questions", total);
                    data.put("percentage", percentage);
                    data.put("passed", passed);
                    data.put("stars_earned", stars);
                    data.put("points_earned", passed ? 50 : 0);   // legacy, kept in sync
                    data.put("completed_at", FieldValue.serverTimestamp());

                    db.collection("quiz_results").document(docId).set(data);
                });
    }

    private void replayVideo() {
        if (player != null) {
            player.seekTo(0);
            player.play();
        }
    }

    private void resetChoiceStyle(TextView btn) {
        btn.setBackgroundResource(R.drawable.bg_choice_default);
        btn.setTextColor(getResources().getColor(R.color.teal_dark, null));
    }

    private void styleCorrect(TextView btn) {
        btn.setBackgroundResource(R.drawable.bg_choice_correct);
        btn.setTextColor(getResources().getColor(R.color.white, null));
    }

    private void styleWrong(TextView btn) {
        btn.setBackgroundResource(R.drawable.bg_choice_wrong);
        btn.setTextColor(getResources().getColor(R.color.white, null));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}