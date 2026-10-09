package com.ucucite.signvibe.ui.quiz;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
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

    // Answer feedback timing.
    private static final long ANSWER_FLASH_MS = 450;       // let the green/red button register first
    private static final long CORRECT_FEEDBACK_MS = 2300;  // "Awesome!" closes itself after this
    private static final long OOPS_FALLBACK_MS = 6000;     // if the oops clip can't play, don't get stuck

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

    /** Which feedback screen is showing, if any. */
    private enum FeedbackStep { NONE, CORRECT, OOPS, ANSWER }

    private PlayerView playerView;
    private ExoPlayer player;
    private TextView txtProgress;
    private final TextView[] choiceButtons = new TextView[4];

    // Feedback overlay.
    private View feedbackOverlay;
    private View feedbackCard;
    private View feedbackCircle;
    private View feedbackAnswerCard;
    private TextView txtFeedbackTitle;
    private TextView btnFeedbackOk;
    private ImageView imgFeedbackGif;
    private PlayerView feedbackCirclePlayer;
    private PlayerView feedbackAnswerPlayer;
    private ConfettiView confettiView;
    private ExoPlayer feedbackPlayer;            // plays the oops clip, then the answer's sign video
    private FeedbackStep feedbackStep = FeedbackStep.NONE;
    private Question feedbackQuestion;           // the question the wrong answer was for

    private final Runnable advanceRunnable = this::closeFeedbackAndAdvance;
    private final Runnable showAnswerRunnable = this::showAnswerStep;

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

        bindFeedbackViews();
        setupPlayer();
        loadLimitsThenQuestions();
    }

    private void bindFeedbackViews() {
        feedbackOverlay = findViewById(R.id.feedbackOverlay);
        feedbackCard = findViewById(R.id.feedbackCard);
        feedbackCircle = findViewById(R.id.feedbackCircle);
        feedbackAnswerCard = findViewById(R.id.feedbackAnswerCard);
        txtFeedbackTitle = findViewById(R.id.txtFeedbackTitle);
        btnFeedbackOk = findViewById(R.id.btnFeedbackOk);
        imgFeedbackGif = findViewById(R.id.imgFeedbackGif);
        feedbackCirclePlayer = findViewById(R.id.feedbackCirclePlayer);
        feedbackAnswerPlayer = findViewById(R.id.feedbackAnswerPlayer);
        confettiView = findViewById(R.id.confettiView);

        // Crop the video to the circle / rounded frame. Set in code because the
        // android:clipToOutline XML attribute is ignored below Android 12.
        feedbackCircle.setClipToOutline(true);
        feedbackAnswerCard.setClipToOutline(true);

        btnFeedbackOk.setOnClickListener(v -> closeFeedbackAndAdvance());

        // Tapping the dimmed screen: skips "Awesome!", or jumps from the oops clip
        // straight to the answer. The answer screen itself waits for Ok.
        feedbackOverlay.setOnClickListener(v -> {
            if (feedbackStep == FeedbackStep.CORRECT) {
                closeFeedbackAndAdvance();
            } else if (feedbackStep == FeedbackStep.OOPS) {
                showAnswerStep();
            }
        });
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

    /**
     * Second player for the feedback screen. Its upstream also understands
     * android.resource:// URIs, so it can play the bundled oops clip as well as
     * the (already cached) sign video for the correct answer.
     */
    @OptIn(markerClass = UnstableApi.class)
    private ExoPlayer feedbackPlayer() {
        if (feedbackPlayer != null) return feedbackPlayer;

        CacheDataSource.Factory cacheFactory = new CacheDataSource.Factory()
                .setCache(VideoCache.get(this))
                .setUpstreamDataSourceFactory(
                        new DefaultDataSource.Factory(this, new DefaultHttpDataSource.Factory()))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        feedbackPlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheFactory))
                .setLoadControl(fastStartLoadControl())
                .build();
        feedbackPlayer.setVolume(0f);
        feedbackPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                // Oops clip finished -> show the correct answer.
                if (state == Player.STATE_ENDED && feedbackStep == FeedbackStep.OOPS) {
                    showAnswerStep();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                // Never leave the student stuck on a broken clip.
                if (feedbackStep == FeedbackStep.OOPS) showAnswerStep();
            }
        });
        return feedbackPlayer;
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
            if (isCorrect) {
                showCorrectFeedback();
            } else {
                showOopsFeedback(q);
            }
        }, ANSWER_FLASH_MS);
    }

    // ===== Answer feedback =====

    /** "Awesome!" + thumbs-up GIF + party-popper confetti, then on to the next question. */
    private void showCorrectFeedback() {
        feedbackStep = FeedbackStep.CORRECT;
        if (player != null) player.pause();

        txtFeedbackTitle.setText(R.string.quiz_feedback_correct);
        feedbackCircle.setVisibility(View.VISIBLE);
        imgFeedbackGif.setVisibility(View.VISIBLE);
        feedbackCirclePlayer.setVisibility(View.GONE);
        feedbackAnswerCard.setVisibility(View.GONE);
        btnFeedbackOk.setVisibility(View.INVISIBLE);

        Glide.with(this).asGif().load(R.raw.quiz_awesome).into(imgFeedbackGif);

        showOverlay();
        // Wait one layout pass so the card's corners are known, then pop the cones.
        feedbackOverlay.post(this::burstConfetti);
        handler.postDelayed(advanceRunnable, CORRECT_FEEDBACK_MS);
    }

    /** Two party-popper cones at the card's lower corners, firing up and inward. */
    private void burstConfetti() {
        if (feedbackStep != FeedbackStep.CORRECT || isFinishing()) return;
        float density = getResources().getDisplayMetrics().density;
        float inset = 30f * density;
        float mouthY = feedbackCard.getBottom() - 70f * density;

        confettiView.burstFromCone(feedbackCard.getLeft() + inset, mouthY, -62f, 70);
        handler.postDelayed(() -> {
            if (feedbackStep != FeedbackStep.CORRECT) return;
            confettiView.burstFromCone(feedbackCard.getRight() - inset, mouthY, -118f, 70);
        }, 140);
    }

    /** "Oops, that is not correct" + the oops clip; the correct answer follows. */
    private void showOopsFeedback(Question q) {
        feedbackStep = FeedbackStep.OOPS;
        feedbackQuestion = q;
        if (player != null) player.pause();

        txtFeedbackTitle.setText(R.string.quiz_feedback_wrong);
        feedbackCircle.setVisibility(View.VISIBLE);
        imgFeedbackGif.setVisibility(View.GONE);
        feedbackCirclePlayer.setVisibility(View.VISIBLE);
        feedbackAnswerCard.setVisibility(View.GONE);
        btnFeedbackOk.setVisibility(View.INVISIBLE);

        ExoPlayer fp = feedbackPlayer();
        feedbackAnswerPlayer.setPlayer(null);
        feedbackCirclePlayer.setPlayer(fp);
        fp.setRepeatMode(Player.REPEAT_MODE_OFF);
        fp.setMediaItem(MediaItem.fromUri(rawUri(R.raw.quiz_oops_sorry)));
        fp.prepare();
        fp.play();

        showOverlay();
        handler.postDelayed(showAnswerRunnable, OOPS_FALLBACK_MS);
    }

    /** "The answer is ___" with that sign's video on loop, until the student taps Ok. */
    private void showAnswerStep() {
        if (feedbackStep != FeedbackStep.OOPS || feedbackQuestion == null) return;
        handler.removeCallbacks(showAnswerRunnable);
        feedbackStep = FeedbackStep.ANSWER;

        String answer = feedbackQuestion.correctAnswer;
        txtFeedbackTitle.setText(answerTitle(answer));

        feedbackCircle.setVisibility(View.GONE);
        feedbackCirclePlayer.setPlayer(null);

        ExoPlayer fp = feedbackPlayer();
        if (!TextUtils.isEmpty(feedbackQuestion.videoUrl)) {
            feedbackAnswerCard.setVisibility(View.VISIBLE);
            feedbackAnswerPlayer.setPlayer(fp);
            fp.setRepeatMode(Player.REPEAT_MODE_ONE);
            fp.setMediaItem(MediaItem.fromUri(Uri.parse(feedbackQuestion.videoUrl)));
            fp.prepare();
            fp.play();
        } else {
            fp.stop();
            feedbackAnswerCard.setVisibility(View.GONE);
        }

        btnFeedbackOk.setVisibility(View.VISIBLE);

        // Soft pop so the change from "Oops" to the answer is noticeable.
        feedbackCard.setScaleX(0.94f);
        feedbackCard.setScaleY(0.94f);
        feedbackCard.animate().scaleX(1f).scaleY(1f).setDuration(260)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
    }

    /** "The answer is " + the answer word in bold, light yellow. */
    private CharSequence answerTitle(String answer) {
        String full = getString(R.string.quiz_feedback_answer_fmt, answer);
        SpannableStringBuilder sb = new SpannableStringBuilder(full);
        int start = full.lastIndexOf(answer);
        if (start >= 0) {
            int end = start + answer.length();
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(0xFFFFF59D), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private void closeFeedbackAndAdvance() {
        if (feedbackStep == FeedbackStep.NONE) return;
        feedbackStep = FeedbackStep.NONE;
        feedbackQuestion = null;
        handler.removeCallbacks(advanceRunnable);
        handler.removeCallbacks(showAnswerRunnable);

        if (feedbackPlayer != null) {
            feedbackPlayer.stop();
            feedbackPlayer.clearMediaItems();
        }
        feedbackCirclePlayer.setPlayer(null);
        feedbackAnswerPlayer.setPlayer(null);
        confettiView.clear();
        Glide.with(this).clear(imgFeedbackGif);

        hideOverlay(this::goToNextQuestion);
    }

    private void goToNextQuestion() {
        if (isFinishing()) return;
        if (currentIndex < questions.size() - 1) {
            currentIndex++;
            showCurrentQuestion();
        } else {
            finishQuiz();
        }
    }

    private void showOverlay() {
        feedbackOverlay.animate().cancel();
        feedbackOverlay.setAlpha(0f);
        feedbackOverlay.setVisibility(View.VISIBLE);
        feedbackOverlay.animate().alpha(1f).setDuration(180).start();

        feedbackCard.setScaleX(0.85f);
        feedbackCard.setScaleY(0.85f);
        feedbackCard.animate().scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(new OvershootInterpolator(1.4f)).start();
    }

    private void hideOverlay(Runnable after) {
        feedbackOverlay.animate().cancel();
        feedbackOverlay.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            feedbackOverlay.setVisibility(View.GONE);
            after.run();
        }).start();
    }

    private Uri rawUri(int rawResId) {
        return Uri.parse("android.resource://" + getPackageName() + "/" + rawResId);
    }

    // ===== Finish & save =====

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
                    data.put("points_earned", passed ? POINTS_ON_PASS : 0);   // legacy, kept in sync
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
        if (feedbackPlayer != null) feedbackPlayer.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Pick up where the student left off: the feedback clip if it was showing,
        // otherwise the question's sign video.
        if (feedbackStep == FeedbackStep.OOPS || feedbackStep == FeedbackStep.ANSWER) {
            if (feedbackPlayer != null) feedbackPlayer.play();
        } else if (feedbackStep == FeedbackStep.NONE && player != null
                && player.getMediaItemCount() > 0) {
            player.play();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        if (feedbackPlayer != null) {
            feedbackPlayer.release();
            feedbackPlayer = null;
        }
    }
}
