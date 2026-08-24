package com.ucucite.signvibe.ml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

/**
 * Wraps MediaPipe's HandLandmarker Task API in LIVE_STREAM mode for real-time
 * camera detection. Conversion pattern (ImageProxy -> Bitmap -> rotate -> MPImage)
 * follows Google's own official HandLandmarkerHelper.kt reference implementation,
 * which is the proven, tested approach for handling CameraX rotation correctly.
 */
public class HandLandmarkerHelper {

    private static final String TAG = "HandLandmarkerHelper";
    private static final String MODEL_ASSET_PATH = "hand_landmarker.task";

    public interface LandmarkerListener {
        void onResults(@NonNull HandLandmarkerResult result, int imageWidth, int imageHeight);
        void onEmpty();
        void onError(@NonNull String message);
    }

    private final LandmarkerListener listener;
    private HandLandmarker handLandmarker;

    public HandLandmarkerHelper(@NonNull Context context, @NonNull LandmarkerListener listener) {
        this.listener = listener;
        setupHandLandmarker(context);
    }

    private void setupHandLandmarker(@NonNull Context context) {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build();

            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(0.5f)
                    .setMinHandPresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setNumHands(1)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
                    .build();

            handLandmarker = HandLandmarker.createFromOptions(context, options);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize HandLandmarker", e);
            listener.onError("Failed to load hand detection model: " + e.getMessage());
        }
    }

    /**
     * Feed one CameraX frame to the detector. Non-blocking — results arrive later
     * via the LandmarkerListener passed to the constructor.
     */
    public void detectLiveStream(@NonNull ImageProxy imageProxy, boolean isFrontCamera) {
        if (handLandmarker == null) {
            imageProxy.close();
            return;
        }

        int frameWidth = imageProxy.getWidth();
        int frameHeight = imageProxy.getHeight();

        Bitmap bitmapBuffer = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
        bitmapBuffer.copyPixelsFromBuffer(imageProxy.getPlanes()[0].getBuffer());

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        imageProxy.close();

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        if (isFrontCamera) {
            matrix.postScale(-1f, 1f, frameWidth, frameHeight);
        }

        Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.getWidth(), bitmapBuffer.getHeight(), matrix, true
        );

        MPImage mpImage = new BitmapImageBuilder(rotatedBitmap).build();

        long frameTimeMs = System.currentTimeMillis();
        handLandmarker.detectAsync(mpImage, frameTimeMs);
    }

    private void returnLivestreamResult(@NonNull HandLandmarkerResult result, @NonNull MPImage inputImage) {
        if (result.landmarks().isEmpty()) {
            listener.onEmpty();
        } else {
            listener.onResults(result, inputImage.getWidth(), inputImage.getHeight());
        }
    }

    private void returnLivestreamError(@NonNull RuntimeException error) {
        Log.e(TAG, "MediaPipe detection error", error);
        listener.onError(error.getMessage() != null ? error.getMessage() : "Unknown detection error");
    }

    public void close() {
        if (handLandmarker != null) {
            handLandmarker.close();
            handLandmarker = null;
        }
    }
}