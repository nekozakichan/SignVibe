package com.ucucite.signvibe.ml;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies a captured word sign from a buffer of per-frame feature vectors.
 *
 * Pipeline (mirrors train_words.py / extract_landmarks.py):
 *   frames (variable count, each 86-dim)
 *     -> resample along time to exactly SEQ_LEN=30   (linear interp, like np.interp)
 *     -> TFLite word_model.tflite  input (1,30,86) -> output (1,NUM_CLASSES) softmax
 *     -> argmax -> label + display text (from labels.json) + confidence
 *
 * No scaler file: normalization is a BatchNorm layer inside the model.
 */
public class WordSequenceClassifier {

    public static final int SEQ_LEN  = 30;
    public static final int FEAT_DIM = HolisticFeatureExtractor.FEAT_DIM; // 86

    private static final String MODEL_FILE  = "word_model.tflite";
    private static final String LABELS_FILE = "labels.json";

    public static class Result {
        public final String label;      // e.g. "goodmorning"
        public final String display;    // e.g. "Good morning"  (spoken text)
        public final float confidence;  // 0..1
        public Result(String label, String display, float confidence) {
            this.label = label; this.display = display; this.confidence = confidence;
        }
    }

    private Interpreter interpreter;
    private String[] labels;     // index -> raw label
    private String[] displays;   // index -> display text
    private int numClasses;
    private boolean ready = false;

    public WordSequenceClassifier(Context context) {
        try {
            interpreter = new Interpreter(loadModel(context, MODEL_FILE));
            loadLabels(context, LABELS_FILE);
            ready = true;
        } catch (Exception e) {
            ready = false;
        }
    }

    public boolean isReady() { return ready; }

    /**
     * Classify a captured sequence. Returns null if not enough usable frames.
     * @param frames list of 86-dim vectors (only the pose-valid frames the
     *               extractor returned; order = capture order).
     */
    @Nullable
    public Result classify(List<float[]> frames) {
        if (!ready || frames == null || frames.size() < 2) return null;

        float[][] seq = resample(frames, SEQ_LEN);   // (30, 86)

        // TFLite input buffer (1, 30, 86) float32.
        ByteBuffer input = ByteBuffer
                .allocateDirect(4 * SEQ_LEN * FEAT_DIM)
                .order(ByteOrder.nativeOrder());
        for (int t = 0; t < SEQ_LEN; t++)
            for (int f = 0; f < FEAT_DIM; f++)
                input.putFloat(seq[t][f]);
        input.rewind();

        float[][] output = new float[1][numClasses];
        interpreter.run(input, output);

        int best = 0;
        for (int i = 1; i < numClasses; i++)
            if (output[0][i] > output[0][best]) best = i;

        return new Result(labels[best], displays[best], output[0][best]);
    }

    // ── Resample a variable-length sequence to exactly n frames ──────────────
    // Linear interpolation along the time axis, matching np.interp in training:
    //   src positions = linspace(0, F-1, F) ; dst positions = linspace(0, F-1, n)
    private static float[][] resample(List<float[]> frames, int n) {
        int F = frames.size();
        float[][] out = new float[n][FEAT_DIM];
        if (F == n) {
            for (int i = 0; i < n; i++) out[i] = frames.get(i).clone();
            return out;
        }
        for (int i = 0; i < n; i++) {
            double pos = (n == 1) ? 0.0 : (double) i * (F - 1) / (n - 1); // dst position
            int lo = (int) Math.floor(pos);
            int hi = Math.min(lo + 1, F - 1);
            float w = (float) (pos - lo);                                 // interp weight
            float[] a = frames.get(lo), b = frames.get(hi);
            for (int f = 0; f < FEAT_DIM; f++)
                out[i][f] = a[f] * (1f - w) + b[f] * w;
        }
        return out;
    }

    // ── Asset loading ────────────────────────────────────────────────────────
    private static MappedByteBuffer loadModel(Context ctx, String name) throws Exception {
        AssetFileDescriptor fd = ctx.getAssets().openFd(name);
        try (FileInputStream is = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel channel = is.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private void loadLabels(Context ctx, String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = ctx.getAssets().open(name)) {
            byte[] buf = new byte[4096]; int r;
            while ((r = is.read(buf)) != -1) sb.append(new String(buf, 0, r, "UTF-8"));
        }
        // labels.json: { "0": {"label": "...", "display": "..."}, "1": {...}, ... }
        JSONObject obj = new JSONObject(sb.toString());
        numClasses = obj.length();
        labels   = new String[numClasses];
        displays = new String[numClasses];
        for (int i = 0; i < numClasses; i++) {
            JSONObject e = obj.getJSONObject(String.valueOf(i));
            labels[i]   = e.getString("label");
            displays[i] = e.optString("display", e.getString("label"));
        }
    }

    public void close() {
        if (interpreter != null) { interpreter.close(); interpreter = null; }
        ready = false;
    }
}