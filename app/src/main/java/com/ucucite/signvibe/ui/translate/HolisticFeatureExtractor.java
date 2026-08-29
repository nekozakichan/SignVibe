package com.ucucite.signvibe.ml;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;

import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Turns one camera frame into the SAME 86-dim body-anchored feature vector the
 * Python pipeline produced during training (extract_landmarks.py).
 *
 * Parity contract with the trainer (do not drift from this):
 *   - Anchor  : shoulder midpoint  (pose 11 = LEFT_SHOULDER, 12 = RIGHT_SHOULDER)
 *   - Scale   : shoulder width     (distance between 11 and 12)
 *   - Per hand: 21 landmarks x (x,y) = 42, body-relative, 0 if hand absent
 *   - Layout  : [ left_hand 42 ][ right_hand 42 ][ left_present 1 ][ right_present 1 ] = 86
 *   - Slotting: hand nearest pose LEFT wrist (15) -> left block; other -> right block
 *               (reproduces how legacy mp.solutions.holistic assigned hands, and
 *                deliberately avoids MediaPipe handedness labels, which assume a
 *                mirrored image and would invert on our raw frames)
 *   - Frames  : returns null when no pose is found, exactly like the extractor
 *               skipped pose-less frames; the caller collects the non-null ones.
 */
public class HolisticFeatureExtractor {

    public static final int FEAT_DIM = 86;

    /**
     * Training clips were RAW / unmirrored. Keep this false to match.
     * If on-device predictions come out scrambled, this flag is the first knob
     * to flip (then rebuild) -- it horizontally mirrors the frame before detection.
     */
    private static final boolean MIRROR = false;

    // Asset filenames in app/src/main/assets/. HAND_MODEL must match the file your
    // existing HandLandmarkerHelper already loads -- rename here if yours differs.
    private static final String POSE_MODEL = "pose_landmarker_lite.task";
    private static final String HAND_MODEL = "hand_landmarker.task";

    // Pose landmark indices (BlazePose 33-point topology).
    private static final int L_SHOULDER = 11, R_SHOULDER = 12, L_WRIST = 15, R_WRIST = 16;

    private final PoseLandmarker poseLandmarker;
    private final HandLandmarker handLandmarker;
    private long frameTs = 0L; // monotonically increasing timestamp for VIDEO mode

    public HolisticFeatureExtractor(Context context) {
        BaseOptions poseBase = BaseOptions.builder().setModelAssetPath(POSE_MODEL).build();
        poseLandmarker = PoseLandmarker.createFromOptions(context,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(poseBase)
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.5f)
                        .setMinPosePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .build());

        BaseOptions handBase = BaseOptions.builder().setModelAssetPath(HAND_MODEL).build();
        handLandmarker = HandLandmarker.createFromOptions(context,
                HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(handBase)
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(2)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .build());
    }

    /**
     * Detect on one frame and build the 86-dim feature vector.
     * Does NOT close the ImageProxy -- the caller owns its lifecycle.
     * Returns null if no usable pose (frame should be dropped by the caller).
     */
    @Nullable
    public float[] process(ImageProxy proxy) {
        Bitmap upright = toUprightBitmap(proxy);
        if (upright == null) return null;

        MPImage image = new BitmapImageBuilder(upright).build();
        long ts = frameTs++;
        PoseLandmarkerResult pose = poseLandmarker.detectForVideo(image, ts);
        HandLandmarkerResult hands = handLandmarker.detectForVideo(image, ts);
        return buildFeatures(pose, hands);
    }

    // ── frame -> upright, (un)mirrored ARGB bitmap ───────────────────────────
    @Nullable
    private Bitmap toUprightBitmap(ImageProxy proxy) {
        // Analyzer is configured OUTPUT_IMAGE_FORMAT_RGBA_8888, so plane 0 is RGBA.
        // (Assumes rowStride == width*4, which holds for the 4:3 sizes CameraX picks
        //  here -- same assumption as Google's MediaPipe Android sample.)
        ByteBuffer buffer = proxy.getPlanes()[0].getBuffer();
        buffer.rewind();
        int w = proxy.getWidth(), h = proxy.getHeight();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);

        Matrix m = new Matrix();
        m.postRotate(proxy.getImageInfo().getRotationDegrees());
        if (MIRROR) m.postScale(-1f, 1f);
        return Bitmap.createBitmap(bmp, 0, 0, w, h, m, true);
    }

    // ── landmarks -> 86-dim vector (mirrors frame_features() in Python) ──────
    @Nullable
    private float[] buildFeatures(PoseLandmarkerResult pose, HandLandmarkerResult hands) {
        if (pose.landmarks().isEmpty()) return null;              // no pose -> drop frame
        List<NormalizedLandmark> p = pose.landmarks().get(0);
        if (p.size() <= R_WRIST) return null;

        NormalizedLandmark lsh = p.get(L_SHOULDER), rsh = p.get(R_SHOULDER);
        float ox = (lsh.x() + rsh.x()) / 2f;
        float oy = (lsh.y() + rsh.y()) / 2f;
        float dx = lsh.x() - rsh.x(), dy = lsh.y() - rsh.y();
        float scale = (float) Math.sqrt(dx * dx + dy * dy);       // shoulder width
        if (scale < 1e-6f) return null;

        float[] leftBlock  = new float[42];   // zero-filled if hand absent
        float[] rightBlock = new float[42];
        float leftPresent = 0f, rightPresent = 0f;

        List<List<NormalizedLandmark>> handList = hands.landmarks();
        if (handList.size() == 1) {
            // Single hand: assign to the slot whose pose wrist it's nearer.
            List<NormalizedLandmark> hand = handList.get(0);
            NormalizedLandmark wrist = hand.get(0);
            if (distToPoseWrist(wrist, p, L_WRIST) <= distToPoseWrist(wrist, p, R_WRIST)) {
                fillBlock(leftBlock, hand, ox, oy, scale);  leftPresent = 1f;
            } else {
                fillBlock(rightBlock, hand, ox, oy, scale); rightPresent = 1f;
            }
        } else if (handList.size() >= 2) {
            // Two hands: the one nearer the LEFT pose wrist takes the left slot.
            List<NormalizedLandmark> a = handList.get(0);
            List<NormalizedLandmark> b = handList.get(1);
            float aToLeft = distToPoseWrist(a.get(0), p, L_WRIST);
            float bToLeft = distToPoseWrist(b.get(0), p, L_WRIST);
            List<NormalizedLandmark> leftHand  = (aToLeft <= bToLeft) ? a : b;
            List<NormalizedLandmark> rightHand = (aToLeft <= bToLeft) ? b : a;
            fillBlock(leftBlock,  leftHand,  ox, oy, scale); leftPresent  = 1f;
            fillBlock(rightBlock, rightHand, ox, oy, scale); rightPresent = 1f;
        }

        float[] feat = new float[FEAT_DIM];
        System.arraycopy(leftBlock,  0, feat, 0,  42);
        System.arraycopy(rightBlock, 0, feat, 42, 42);
        feat[84] = leftPresent;
        feat[85] = rightPresent;
        return feat;
    }

    private static void fillBlock(float[] block, List<NormalizedLandmark> hand,
                                  float ox, float oy, float scale) {
        for (int i = 0; i < 21; i++) {
            NormalizedLandmark lm = hand.get(i);
            block[2 * i]     = (lm.x() - ox) / scale;
            block[2 * i + 1] = (lm.y() - oy) / scale;
        }
    }

    private static float distToPoseWrist(NormalizedLandmark wrist,
                                         List<NormalizedLandmark> pose, int poseWristIdx) {
        NormalizedLandmark pw = pose.get(poseWristIdx);
        float dx = wrist.x() - pw.x(), dy = wrist.y() - pw.y();
        return dx * dx + dy * dy; // squared distance is enough for comparison
    }

    public void close() {
        if (poseLandmarker != null) poseLandmarker.close();
        if (handLandmarker != null) handLandmarker.close();
    }
}