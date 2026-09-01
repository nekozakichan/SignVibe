package com.ucucite.signvibe.ui.translate;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.ucucite.signvibe.SignVibeToast;

import com.google.common.util.concurrent.ListenableFuture;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.ml.HolisticFeatureExtractor;
import com.ucucite.signvibe.ml.WordSequenceClassifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WordDetectionActivity extends AppCompatActivity {

    private static final long  RECORD_MS            = 2500;  // capture window (matches collection)
    private static final float CONFIDENCE_THRESHOLD = 0.60f; // below this -> ask to retry
    private static final int   MIN_FRAMES           = 8;     // too few -> hands/body weren't in view

    private androidx.camera.view.PreviewView previewView;
    private TextView txtTitle, txtInstruction, txtCountdown, txtStatus;
    private TextView txtResultWord, txtResultConfidence, txtPermissionDenied;
    private View recPill, resultPanel, progressBar, btnSpeak;
    private android.widget.Button btnStart;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview previewUseCase;
    private ImageAnalysis imageAnalysisUseCase;
    private ExecutorService cameraExecutor;

    private HolisticFeatureExtractor extractor;
    private WordSequenceClassifier classifier;
    private TextToSpeech tts;
    private boolean isTtsReady = false;

    private boolean isFrontCamera = true;     // kids sign at themselves; training was front-cam
    private boolean ready = false;            // models loaded
    private boolean busy = false;             // a capture is in progress
    private volatile boolean capturing = false;

    private final List<float[]> buffer = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String lastSpoken = "";

    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_word_detection);

        bindViews();
        setupTts();
        setupClickListeners();
        setStatus(getString(R.string.word_loading));

        cameraExecutor = Executors.newSingleThreadExecutor();

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) initDetectionAndCamera(); else showPermissionDenied(); });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            initDetectionAndCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void bindViews() {
        previewView         = findViewById(R.id.previewView);
        txtTitle            = findViewById(R.id.txtTitle);
        txtInstruction      = findViewById(R.id.txtInstruction);
        txtCountdown        = findViewById(R.id.txtCountdown);
        txtStatus           = findViewById(R.id.txtStatus);
        txtResultWord       = findViewById(R.id.txtResultWord);
        txtResultConfidence = findViewById(R.id.txtResultConfidence);
        txtPermissionDenied = findViewById(R.id.txtPermissionDenied);
        recPill             = findViewById(R.id.recPill);
        resultPanel         = findViewById(R.id.resultPanel);
        progressBar         = findViewById(R.id.progressBar);
        btnStart            = findViewById(R.id.btnStart);
        btnSpeak            = findViewById(R.id.btnSpeak);

        txtTitle.setText(getString(R.string.translate_word_title));
    }

    private void setupClickListeners() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnFlip).setOnClickListener(v -> flipCamera());
        btnStart.setOnClickListener(v -> startCapture());
        btnSpeak.setOnClickListener(v -> speakLast());
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                isTtsReady = true;
            }
        });
    }

    private void initDetectionAndCamera() {
        // Loading Pose + Hand + the TFLite model can take a moment -> off the UI thread.
        cameraExecutor.execute(() -> {
            extractor  = new HolisticFeatureExtractor(getApplicationContext());
            classifier = new WordSequenceClassifier(getApplicationContext());
            boolean ok = classifier.isReady();
            runOnUiThread(() -> {
                ready = ok;
                if (ok) {
                    setStatus("");
                    txtInstruction.setVisibility(View.VISIBLE);
                    btnStart.setEnabled(true);
                    startCamera();
                } else {
                    setStatus(getString(R.string.word_model_error));
                }
            });
        });
    }

    // ── Capture flow ─────────────────────────────────────────────────────────

    private void startCapture() {
        if (!ready || busy) return;
        busy = true;
        btnStart.setEnabled(false);
        hideResult();
        txtInstruction.setVisibility(View.GONE);
        runCountdown();
    }

    private void runCountdown() {
        showCountdown("3");
        ui.postDelayed(() -> showCountdown("2"), 700);
        ui.postDelayed(() -> showCountdown("1"), 1400);
        ui.postDelayed(() -> { hideCountdown(); beginRecording(); }, 2100);
    }

    private void beginRecording() {
        synchronized (buffer) { buffer.clear(); }
        capturing = true;
        recPill.setVisibility(View.VISIBLE);
        setStatus(getString(R.string.word_sign_now));
        ui.postDelayed(this::finishRecording, RECORD_MS);
    }

    private void finishRecording() {
        capturing = false;
        recPill.setVisibility(View.GONE);
        setStatus(getString(R.string.word_reading));
        progressBar.setVisibility(View.VISIBLE);

        // Queue classification behind any in-flight frame on the same executor,
        // so the snapshot can't race a concurrent add.
        cameraExecutor.execute(() -> {
            List<float[]> snapshot;
            synchronized (buffer) { snapshot = new ArrayList<>(buffer); }
            WordSequenceClassifier.Result r = classifier.classify(snapshot);
            int frames = snapshot.size();
            runOnUiThread(() -> showResult(r, frames));
        });
    }

    private void showResult(WordSequenceClassifier.Result r, int frames) {
        busy = false;
        progressBar.setVisibility(View.GONE);
        btnStart.setEnabled(true);
        btnStart.setText(getString(R.string.word_sign_again));

        if (frames < MIN_FRAMES || r == null) {
            setStatus(getString(R.string.word_not_captured)); // keep hands + upper body in view
            return;
        }
        if (r.confidence < CONFIDENCE_THRESHOLD) {
            setStatus(getString(R.string.word_unsure));        // try again a bit slower
            return;
        }

        setStatus("");
        resultPanel.setVisibility(View.VISIBLE);
        txtResultWord.setText(r.display);
        txtResultConfidence.setText(getString(
                R.string.confidence_fmt, (int) (r.confidence * 100)));
        lastSpoken = r.display;
        speakLast();
    }

    private void speakLast() {
        if (isTtsReady && !lastSpoken.isEmpty())
            tts.speak(lastSpoken, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

    private void setStatus(String s) {
        txtStatus.setText(s);
        txtStatus.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void showCountdown(String s) {
        txtCountdown.setText(s);
        txtCountdown.setVisibility(View.VISIBLE);
    }
    private void hideCountdown() { txtCountdown.setVisibility(View.GONE); }
    private void hideResult()    { resultPanel.setVisibility(View.GONE); }

    private void showPermissionDenied() {
        previewView.setVisibility(View.GONE);
        btnStart.setVisibility(View.GONE);
        txtPermissionDenied.setVisibility(View.VISIBLE);
    }

    // ── Camera ───────────────────────────────────────────────────────────────

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                SignVibeToast.show(this, "Could not start camera", Toast.LENGTH_SHORT);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(isFrontCamera
                        ? CameraSelector.LENS_FACING_FRONT
                        : CameraSelector.LENS_FACING_BACK)
                .build();

        ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build();

        previewUseCase = new Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build();
        previewUseCase.setSurfaceProvider(previewView.getSurfaceProvider());

        imageAnalysisUseCase = new ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysisUseCase.setAnalyzer(cameraExecutor, proxy -> {
            try {
                if (capturing && extractor != null) {
                    float[] f = extractor.process(proxy);   // 86-dim, or null if no pose
                    if (f != null) synchronized (buffer) { buffer.add(f); }
                }
            } catch (Exception ignored) {
                // one bad frame shouldn't kill the capture
            } finally {
                proxy.close();
            }
        });

        camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, previewUseCase, imageAnalysisUseCase);
    }

    private void flipCamera() {
        if (busy) return; // don't switch mid-capture
        isFrontCamera = !isFrontCamera;
        bindCameraUseCases();
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (extractor != null) extractor.close();
        if (classifier != null) classifier.close();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}