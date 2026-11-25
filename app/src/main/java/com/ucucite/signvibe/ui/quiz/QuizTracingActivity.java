package com.ucucite.signvibe.ui.quiz;

import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.ucucite.signvibe.ui.quiz.QuizResultActivity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.SignVibeToast;
import com.ucucite.signvibe.ui.learn.Lesson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tracing for the Alphabet and Numbers modules, in two modes:
 *
 *  - Quiz (default): a grade-sized random pick of letters/numbers. A trace is
 *    correct only when every numbered arrow was drawn in order (TracingView
 *    erases strokes that start at the wrong number or go the wrong way), with
 *    at most MAX_MISTAKES_PER_QUESTION wrong strokes. Result saved to
 *    quiz_results.
 *  - Practice (EXTRA_PRACTICE): every letter/number of the module in order,
 *    the next arrow highlighted with a dot showing how to draw it, praise on
 *    each finished glyph, and nothing saved.
 *
 * Characters without stroke-order arrows fall back to coverage judging.
 */
public class QuizTracingActivity extends AppCompatActivity {

    public static final String EXTRA_MODULE_ID = "extra_module_id";
    public static final String EXTRA_MODULE_TITLE = "extra_module_title";
    /** true = practice mode (no score, all glyphs in order). */
    public static final String EXTRA_PRACTICE = "extra_practice";

    // Judging + scoring config (tunable).
    private static final float COVERAGE_TO_PASS_QUESTION = 0.60f;  // 60% of the glyph covered (no-arrow glyphs)
    private static final int MAX_MISTAKES_PER_QUESTION = 2;        // wrong strokes allowed and still correct
    private static final int PASS_PERCENTAGE = 70;                 // 70% of questions correct to pass module
    private static final int POINTS_ON_PASS = 50;

    private TracingView tracingView;
    private TextView txtTargetGlyph;
    private TextView txtProgress;
    private TextView txtInstruction;
    private TextView btnNext;

    private TextToSpeech tts;
    private boolean isTtsReady = false;

    private String moduleId;
    private String moduleTitle;

    private final List<String> glyphs = new ArrayList<>();  // e.g. ["A","B","C",...]
    private int currentIndex = 0;
    private int correctCount = 0;

    private boolean practice = false;
    // Quiz: Next tapped once on an unfinished trace; a second tap skips it.
    private boolean skipArmed = false;

    /** Decides how many glyphs this student traces. See QuizLimits. */
    private String gradeLevel;
    private QuizLimits quizLimits = QuizLimits.defaults();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz_tracing);

        moduleId = getIntent().getStringExtra(EXTRA_MODULE_ID);
        moduleTitle = getIntent().getStringExtra(EXTRA_MODULE_TITLE);
        practice = getIntent().getBooleanExtra(EXTRA_PRACTICE, false);

        bindViews();
        setupTts();
        setupClickListeners();
        if (practice) {
            // Practice uses every glyph, so the grade limits aren't needed.
            loadGlyphsFromLessons();
        } else {
            loadLimitsThenGlyphs();
        }
    }

    /**
     * Quiz length comes from settings/quiz_limits (shared with the web admin)
     * combined with this student's grade. Both reads fall back to safe defaults
     * rather than blocking the quiz.
     */
    private void loadLimitsThenGlyphs() {
        QuizLimitsRepository.load(limits -> {
            if (isFinishing()) return;
            quizLimits = limits;
            loadGradeThenGlyphs();
        });
    }

    private void loadGradeThenGlyphs() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            loadGlyphsFromLessons();
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
                    loadGlyphsFromLessons();
                })
                .addOnFailureListener(e -> {
                    if (isFinishing()) return;
                    loadGlyphsFromLessons();
                });
    }

    private void bindViews() {
        tracingView = findViewById(R.id.tracingView);
        txtTargetGlyph = findViewById(R.id.txtTargetGlyph);
        txtProgress = findViewById(R.id.txtProgress);
        txtInstruction = findViewById(R.id.txtInstruction);
        btnNext = findViewById(R.id.btnNext);

        ((TextView) findViewById(R.id.txtQuizTitle)).setText(
                getString(practice ? R.string.tracing_practice_title : R.string.quiz_tracing_title));

        tracingView.setPracticeMode(practice);
        tracingView.setListener(new TracingView.Listener() {
            @Override
            public void onStrokeAccepted(int strokeNumber, boolean glyphComplete) {
                skipArmed = false;
                if (glyphComplete) onGlyphComplete();
            }

            @Override
            public void onStrokeRejected(int expectedNumber, boolean wrongStart) {
                SignVibeToast.show(QuizTracingActivity.this,
                        getString(wrongStart ? R.string.tracing_start_at_fmt : R.string.tracing_follow_arrow_fmt,
                                expectedNumber),
                        Toast.LENGTH_SHORT);
            }
        });

        // "Trace the letter" for Alphabet, "Trace the number" for Numbers.
        boolean isNumbers = "numbers".equals(moduleId);
        txtInstruction.setText(isNumbers
                ? getString(R.string.quiz_tracing_instruction_number)
                : getString(R.string.quiz_tracing_instruction));
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.85f);
                isTtsReady = true;
            }
        });
    }

    private void setupClickListeners() {
        findViewById(R.id.btnBack).setOnClickListener(v -> confirmExit());

        findViewById(R.id.btnListen).setOnClickListener(v -> speakCurrentGlyph());

        findViewById(R.id.btnClear).setOnClickListener(v -> tracingView.clear());

        btnNext.setOnClickListener(v -> onNextTapped());
    }

    /**
     * Each published lesson in this module contributes one glyph to trace,
     * taken from the lesson's short label ("Letter A" -> "A", "Number 1" -> "1"),
     * ordered by the lesson's order field — same source the Learn tab uses.
     */
    private void loadGlyphsFromLessons() {
        FirebaseFirestore.getInstance()
                .collection("lessons")
                .whereEqualTo("module_id", moduleId)
                .whereEqualTo("status", "published")
                .get()
                .addOnSuccessListener(snapshots -> {
                    if (isFinishing()) return;

                    List<Lesson> lessons = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshots) {
                        lessons.add(Lesson.fromFirestore(doc));
                    }
                    Collections.sort(lessons, Comparator.comparingInt(Lesson::getOrder));

                    List<String> allGlyphs = new ArrayList<>();
                    for (Lesson l : lessons) {
                        String label = l.getShortLabel();
                        if (!TextUtils.isEmpty(label)) {
                            allGlyphs.add(label);
                        }
                    }

                    // Quiz: Grade 1 traces 5 glyphs, Grade 6 traces 20 — a random
                    // pick from the module, kept in the lessons' order.
                    // Practice: every glyph, in order.
                    glyphs.clear();
                    glyphs.addAll(practice ? allGlyphs : quizLimits.limitForGrade(allGlyphs, gradeLevel));

                    if (glyphs.isEmpty()) {
                        SignVibeToast.show(this,
                                "No lessons to build a quiz from yet.",
                                Toast.LENGTH_SHORT);
                        finish();
                        return;
                    }

                    currentIndex = 0;
                    correctCount = 0;
                    showCurrentGlyph();
                })
                .addOnFailureListener(e -> {
                    if (isFinishing()) return;
                    SignVibeToast.show(this, "Couldn't load the quiz. Try again.",
                            Toast.LENGTH_SHORT);
                    finish();
                });
    }

    private void showCurrentGlyph() {
        String glyph = glyphs.get(currentIndex);
        txtTargetGlyph.setText(glyph);
        tracingView.setGlyph(glyph);

        txtProgress.setText(getString(
                R.string.quiz_tracing_progress_fmt, currentIndex + 1, glyphs.size()));
        skipArmed = false;

        // Last question shows "Finish" ("Done" in practice) instead of "Next".
        boolean isLast = currentIndex == glyphs.size() - 1;
        btnNext.setText(getString(isLast
                ? (practice ? R.string.tracing_practice_done : R.string.quiz_finish)
                : R.string.quiz_next));

        speakCurrentGlyph();  // auto-say the letter/number when it appears
    }

    /** Every arrow traced in order. */
    private void onGlyphComplete() {
        if (practice) {
            if (isTtsReady) tts.speak(getString(R.string.tracing_praise_spoken), TextToSpeech.QUEUE_FLUSH, null, null);
            SignVibeToast.show(this, getString(R.string.tracing_praise), Toast.LENGTH_SHORT);
        } else {
            SignVibeToast.show(this, getString(R.string.tracing_done_tap_next), Toast.LENGTH_SHORT);
        }
    }

    private void onNextTapped() {
        // Practice: move on freely — nothing is scored.
        if (practice) {
            if (currentIndex < glyphs.size() - 1) {
                currentIndex++;
                showCurrentGlyph();
            } else {
                finish();
            }
            return;
        }

        // Require at least an attempt before advancing.
        if (!tracingView.hasDrawn()) {
            SignVibeToast.show(this, "Try tracing the letter first!", Toast.LENGTH_SHORT);
            return;
        }

        boolean correct;
        if (tracingView.hasStrokeOrder()) {
            // Unfinished trace: say which arrow is next; a second tap skips (counted wrong).
            if (!tracingView.isComplete() && !skipArmed) {
                skipArmed = true;
                SignVibeToast.show(this,
                        getString(R.string.tracing_not_finished_fmt, tracingView.getNextStrokeNumber()),
                        Toast.LENGTH_LONG);
                return;
            }
            // Correct = every arrow traced in order, with only a few wrong strokes.
            correct = tracingView.isComplete()
                    && tracingView.getMistakeCount() <= MAX_MISTAKES_PER_QUESTION;
        } else {
            // No arrows for this character: judge by coverage of the guide glyph.
            correct = tracingView.computeCoverage() >= COVERAGE_TO_PASS_QUESTION;
        }
        if (correct) {
            correctCount++;
        }

        if (currentIndex < glyphs.size() - 1) {
            currentIndex++;
            showCurrentGlyph();
        } else {
            finishQuiz();
        }
    }

    private void finishQuiz() {
        int total = glyphs.size();
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

    /**
     * Writes to quiz_results. Keep-best by percentage so a weaker retake never
     * lowers the record. Writes stars_earned (0-3); points_earned kept in sync
     * (50 on any pass) only so nothing reading the old field breaks mid-migration.
     */
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

    private void speakCurrentGlyph() {
        if (isTtsReady && currentIndex < glyphs.size()) {
            tts.speak(glyphs.get(currentIndex), TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    private void confirmExit() {
        // Leaving mid-quiz just discards progress; nothing is saved until finish
        // (and practice never saves anything).
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}