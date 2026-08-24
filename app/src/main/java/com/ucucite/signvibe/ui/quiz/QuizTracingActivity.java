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
import com.ucucite.signvibe.ui.learn.Lesson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class QuizTracingActivity extends AppCompatActivity {

    public static final String EXTRA_MODULE_ID = "extra_module_id";
    public static final String EXTRA_MODULE_TITLE = "extra_module_title";

    // Judging + scoring config (tunable).
    private static final float COVERAGE_TO_PASS_QUESTION = 0.60f;  // 60% of the glyph covered
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz_tracing);

        moduleId = getIntent().getStringExtra(EXTRA_MODULE_ID);
        moduleTitle = getIntent().getStringExtra(EXTRA_MODULE_TITLE);

        bindViews();
        setupTts();
        setupClickListeners();
        loadGlyphsFromLessons();
    }

    private void bindViews() {
        tracingView = findViewById(R.id.tracingView);
        txtTargetGlyph = findViewById(R.id.txtTargetGlyph);
        txtProgress = findViewById(R.id.txtProgress);
        txtInstruction = findViewById(R.id.txtInstruction);
        btnNext = findViewById(R.id.btnNext);

        ((TextView) findViewById(R.id.txtQuizTitle)).setText(
                getString(R.string.quiz_tracing_title));

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

                    glyphs.clear();
                    for (Lesson l : lessons) {
                        String label = l.getShortLabel();
                        if (!TextUtils.isEmpty(label)) {
                            glyphs.add(label);
                        }
                    }

                    if (glyphs.isEmpty()) {
                        Toast.makeText(this,
                                "No lessons to build a quiz from yet.",
                                Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }

                    currentIndex = 0;
                    correctCount = 0;
                    showCurrentGlyph();
                })
                .addOnFailureListener(e -> {
                    if (isFinishing()) return;
                    Toast.makeText(this, "Couldn't load the quiz. Try again.",
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
    }

    private void showCurrentGlyph() {
        String glyph = glyphs.get(currentIndex);
        txtTargetGlyph.setText(glyph);
        tracingView.setGlyph(glyph);

        txtProgress.setText(getString(
                R.string.quiz_tracing_progress_fmt, currentIndex + 1, glyphs.size()));

        // Last question shows "Finish" instead of "Next".
        boolean isLast = currentIndex == glyphs.size() - 1;
        btnNext.setText(getString(isLast ? R.string.quiz_finish : R.string.quiz_next));

        speakCurrentGlyph();  // auto-say the letter/number when it appears
    }

    private void onNextTapped() {
        // Require at least an attempt before advancing.
        if (!tracingView.hasDrawn()) {
            Toast.makeText(this, "Try tracing the letter first!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Judge this trace by coverage of the guide glyph.
        float coverage = tracingView.computeCoverage();
        if (coverage >= COVERAGE_TO_PASS_QUESTION) {
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
        // Leaving mid-quiz just discards progress; nothing is saved until finish.
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