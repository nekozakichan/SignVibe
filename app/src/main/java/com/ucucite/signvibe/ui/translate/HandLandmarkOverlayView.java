package com.ucucite.signvibe.ui.translate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

/**
 * Draws the 21 hand landmarks + skeleton connections on top of the camera preview.
 * Coordinate mapping matches Google's official MediaPipe sample's OverlayView pattern:
 * PreviewView uses FILL_CENTER (crop-to-fill), so we scale by the larger ratio of
 * (viewSize / imageSize) and center-offset the difference.
 */
public class HandLandmarkOverlayView extends View {

    // Standard 21-point MediaPipe hand connections (wrist=0, thumb=1-4,
    // index=5-8, middle=9-12, ring=13-16, pinky=17-20, plus palm base).
    private static final int[][] CONNECTIONS = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4},         // Thumb
            {0, 5}, {5, 6}, {6, 7}, {7, 8},         // Index
            {5, 9}, {9, 10}, {10, 11}, {11, 12},    // Middle
            {9, 13}, {13, 14}, {14, 15}, {15, 16},  // Ring
            {13, 17}, {17, 18}, {18, 19}, {19, 20}, // Pinky
            {0, 17},                                // Palm base
    };

    private List<NormalizedLandmark> landmarks = null;
    private int imageWidth = 1;
    private int imageHeight = 1;
    private float scaleFactor = 1f;
    private float offsetX = 0f;
    private float offsetY = 0f;

    private final Paint dotPaint = new Paint();
    private final Paint linePaint = new Paint();

    public HandLandmarkOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dotPaint.setColor(Color.parseColor("#00777F"));
        dotPaint.setStyle(Paint.Style.FILL);

        linePaint.setColor(Color.parseColor("#B3FFFFFF"));
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);
    }

    /** Call after each detection result; pass null to clear when no hand is present. */
    public void setResults(@Nullable List<NormalizedLandmark> landmarks, int imageWidth, int imageHeight) {
        this.landmarks = landmarks;
        this.imageWidth = Math.max(imageWidth, 1);
        this.imageHeight = Math.max(imageHeight, 1);
        computeScale();
        postInvalidate();
    }

    public void clear() {
        landmarks = null;
        postInvalidate();
    }

    private void computeScale() {
        if (getWidth() == 0 || getHeight() == 0) return;
        // FILL_CENTER: scale by the larger ratio so the image fully covers the view (crop).
        float scaleX = getWidth() / (float) imageWidth;
        float scaleY = getHeight() / (float) imageHeight;
        scaleFactor = Math.max(scaleX, scaleY);
        offsetX = (getWidth() - imageWidth * scaleFactor) / 2f;
        offsetY = (getHeight() - imageHeight * scaleFactor) / 2f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeScale();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (landmarks == null || landmarks.size() != 21) return;

        float[] xs = new float[21];
        float[] ys = new float[21];
        for (int i = 0; i < 21; i++) {
            NormalizedLandmark lm = landmarks.get(i);
            xs[i] = lm.x() * imageWidth * scaleFactor + offsetX;
            ys[i] = lm.y() * imageHeight * scaleFactor + offsetY;
        }

        for (int[] pair : CONNECTIONS) {
            canvas.drawLine(xs[pair[0]], ys[pair[0]], xs[pair[1]], ys[pair[1]], linePaint);
        }
        for (int i = 0; i < 21; i++) {
            canvas.drawCircle(xs[i], ys[i], 7f, dotPaint);
        }
    }
}