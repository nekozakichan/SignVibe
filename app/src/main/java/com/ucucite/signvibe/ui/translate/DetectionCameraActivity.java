package com.ucucite.signvibe.ui.translate;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.ml.HandLandmarkerHelper;
import com.ucucite.signvibe.ml.LandmarkClassifier;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DetectionCameraActivity extends AppCompatActivity
        implements HandLandmarkerHelper.LandmarkerListener {

    public static final String EXTRA_TYPE = "extra_type"; // "number" or "alphabet"

    private static final float CONFIDENCE_THRESHOLD = 0.70f;

    private androidx.camera.view.PreviewView previewView;
    private TextView txtTitle;
    private TextView txtDetectedValue;
    private TextView txtConfidence;
    private TextView txtNoHand;
    private TextView txtPermissionDenied;
    private View bottomPanel;
    private HandLandmarkOverlayView overlayView;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview previewUseCase;
    private ImageAnalysis imageAnalysisUseCase;
    private ExecutorService cameraExecutor;

    private HandLandmarkerHelper handLandmarkerHelper;
    private LandmarkClassifier landmarkClassifier;
    private TextToSpeech tts;
    private boolean isTtsReady = false;

    private boolean isFrontCamera = false; // back camera by default
    private String lastDetectedLabel = "";
    private String type = "number"; // default

    private ActivityResultLauncher<String> cameraPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection_camera);

        String extraType = getIntent().getStringExtra(EXTRA_TYPE);
        if (extraType != null) type = extraType;

        bindViews();
        setupTitle();
        setupTts();
        setupClickListeners();

        cameraExecutor = Executors.newSingleThreadExecutor();

        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        initDetectionAndCamera();
                    } else {
                        showPermissionDenied();
                    }
                }
        );

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            initDetectionAndCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void bindViews() {
        previewView = findViewById(R.id.previewView);
        txtTitle = findViewById(R.id.txtTitle);
        txtDetectedValue = findViewById(R.id.txtDetectedValue);
        txtConfidence = findViewById(R.id.txtConfidence);
        txtNoHand = findViewById(R.id.txtNoHand);
        txtPermissionDenied = findViewById(R.id.txtPermissionDenied);
        bottomPanel = findViewById(R.id.bottomPanel);
        overlayView = findViewById(R.id.overlayView);
    }

    private void setupTitle() {
        txtTitle.setText(type.equals("alphabet")
                ? getString(R.string.translate_alphabet_title)
                : getString(R.string.translate_number_title));
    }

    private void setupClickListeners() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnStop).setOnClickListener(v -> finish());

        findViewById(R.id.btnSpeak).setOnClickListener(v -> speakDetected());
        findViewById(R.id.btnSpeakTop).setOnClickListener(v -> speakDetected());

        findViewById(R.id.btnClear).setOnClickListener(v -> clearResult());
        findViewById(R.id.btnFlip).setOnClickListener(v -> flipCamera());
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
        String modelFile = type.equals("alphabet")
                ? "fsl_alphabet.tflite"
                : "fsl_landmark_mlp.tflite";
        String scalerFile = type.equals("alphabet")
                ? "fsl_alphabet_scaler.json"
                : "fsl_landmark_scaler_params.json";

        // Model loading runs off the UI thread — MediaPipe init can take a moment.
        cameraExecutor.execute(() -> {
            handLandmarkerHelper = new HandLandmarkerHelper(getApplicationContext(), this);
            landmarkClassifier = new LandmarkClassifier(getApplicationContext(), modelFile, scalerFile);
            runOnUiThread(this::startCamera);
        });
    }

    private void showPermissionDenied() {
        previewView.setVisibility(View.GONE);
        bottomPanel.setVisibility(View.GONE);
        txtPermissionDenied.setVisibility(View.VISIBLE);
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (Exception e) {
                Toast.makeText(this, "Could not start camera", Toast.LENGTH_SHORT).show();
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
        imageAnalysisUseCase.setAnalyzer(cameraExecutor, imageProxy -> {
            if (handLandmarkerHelper != null) {
                handLandmarkerHelper.detectLiveStream(imageProxy, isFrontCamera);
            } else {
                imageProxy.close();
            }
        });

        camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, previewUseCase, imageAnalysisUseCase
        );
    }

    private void flipCamera() {
        isFrontCamera = !isFrontCamera;
        bindCameraUseCases();
    }

    private void clearResult() {
        lastDetectedLabel = "";
        runOnUiThread(() -> {
            txtDetectedValue.setText("—");
            txtConfidence.setVisibility(View.GONE);
        });
    }

    private void speakDetected() {
        if (isTtsReady && !lastDetectedLabel.isEmpty()) {
            tts.speak(lastDetectedLabel, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // ── HandLandmarkerHelper.LandmarkerListener ────────────────

    @Override
    public void onResults(@NonNull HandLandmarkerResult result, int imageWidth, int imageHeight) {
        List<NormalizedLandmark> hand = result.landmarks().get(0);

        runOnUiThread(() -> overlayView.setResults(hand, imageWidth, imageHeight));

        if (landmarkClassifier == null || !landmarkClassifier.isReady()) return;
        if (hand.size() != 21) return;

        float[] flat = new float[63];
        for (int i = 0; i < 21; i++) {
            NormalizedLandmark lm = hand.get(i);
            flat[i * 3] = lm.x();
            flat[i * 3 + 1] = lm.y();
            flat[i * 3 + 2] = lm.z();
        }

        LandmarkClassifier.Result classification = landmarkClassifier.classify(flat);
        if (classification == null) return;

        runOnUiThread(() -> {
            txtNoHand.setVisibility(View.GONE);

            if (classification.confidence >= CONFIDENCE_THRESHOLD) {
                boolean isNewDetection = !classification.label.equals(lastDetectedLabel);

                lastDetectedLabel = classification.label;
                txtDetectedValue.setText(classification.label);
                txtConfidence.setText(getString(
                        R.string.confidence_fmt, (int) (classification.confidence * 100)
                ));
                txtConfidence.setVisibility(View.VISIBLE);

                if (isNewDetection) {
                    speakDetected();
                }
            }
        });
    }

    @Override
    public void onEmpty() {
        runOnUiThread(() -> {
            txtNoHand.setVisibility(View.VISIBLE);
            txtConfidence.setVisibility(View.GONE);
            overlayView.clear();
        });
    }

    @Override
    public void onError(@NonNull String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    // ── Lifecycle ────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (handLandmarkerHelper != null) handLandmarkerHelper.close();
        if (landmarkClassifier != null) landmarkClassifier.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        cameraExecutor.shutdown();
    }
}