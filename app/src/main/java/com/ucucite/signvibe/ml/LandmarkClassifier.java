package com.ucucite.signvibe.ml;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Runs a trained MLP classifier on 21 hand landmarks (63 floats: x,y,z per
 * point). Generic across detection types — Number (1-10), Alphabet (A-Z),
 * and future categories all use this same class, just pointed at different
 * model/scaler asset files and producing different label sets.
 */
public class LandmarkClassifier {

    private static final String TAG = "LandmarkClassifier";
    private static final int NUM_LANDMARKS = 63; // 21 points * (x, y, z)

    public static class Result {
        public final String label;
        public final float confidence;

        Result(String label, float confidence) {
            this.label = label;
            this.confidence = confidence;
        }
    }

    private final String modelFile;
    private final String scalerFile;

    private Interpreter interpreter;
    private float[] mean;
    private float[] scale;
    private String[] labels;
    private boolean isReady = false;

    public LandmarkClassifier(@NonNull Context context, @NonNull String modelFile, @NonNull String scalerFile) {
        this.modelFile = modelFile;
        this.scalerFile = scalerFile;
        try {
            interpreter = new Interpreter(loadModelFile(context));
            loadScalerParams(context);
            isReady = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize LandmarkClassifier (" + modelFile + ")", e);
        }
    }

    private MappedByteBuffer loadModelFile(@NonNull Context context) throws IOException {
        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd(modelFile);
             FileInputStream inputStream = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fd.getStartOffset();
            long declaredLength = fd.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        }
    }

    private void loadScalerParams(@NonNull Context context) throws IOException, JSONException {
        StringBuilder json = new StringBuilder();
        try (InputStream is = context.getAssets().open(scalerFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
        }

        JSONObject obj = new JSONObject(json.toString());

        JSONArray meanArr = obj.getJSONArray("mean");
        mean = new float[meanArr.length()];
        for (int i = 0; i < meanArr.length(); i++) mean[i] = (float) meanArr.getDouble(i);

        JSONArray scaleArr = obj.getJSONArray("scale");
        scale = new float[scaleArr.length()];
        for (int i = 0; i < scaleArr.length(); i++) scale[i] = (float) scaleArr.getDouble(i);

        JSONArray labelsArr = obj.getJSONArray("labels");
        labels = new String[labelsArr.length()];
        for (int i = 0; i < labelsArr.length(); i++) labels[i] = labelsArr.getString(i);

        Log.d(TAG, "Loaded scaler params, labels: " + java.util.Arrays.toString(labels));
    }

    public boolean isReady() {
        return isReady;
    }

    /**
     * @param landmarks flat array of 63 floats (21 landmarks x,y,z, normalized 0-1 by MediaPipe)
     * @return predicted label + confidence, or null if not ready or input malformed
     */
    @Nullable
    public Result classify(@NonNull float[] landmarks) {
        if (!isReady || landmarks.length != NUM_LANDMARKS) return null;

        float[] scaled = new float[NUM_LANDMARKS];
        for (int i = 0; i < NUM_LANDMARKS; i++) {
            scaled[i] = (landmarks[i] - mean[i]) / scale[i];
        }

        float[][] input = new float[1][NUM_LANDMARKS];
        input[0] = scaled;

        float[][] output = new float[1][labels.length];
        interpreter.run(input, output);

        int maxIndex = 0;
        float maxScore = output[0][0];
        for (int i = 1; i < output[0].length; i++) {
            if (output[0][i] > maxScore) {
                maxScore = output[0][i];
                maxIndex = i;
            }
        }

        return new Result(labels[maxIndex], maxScore);
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}