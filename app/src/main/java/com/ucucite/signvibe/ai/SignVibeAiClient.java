package com.ucucite.signvibe.ai;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import com.ucucite.signvibe.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class SignVibeAiClient {
    private static final String TAG = "SignVibeAiClient";
    private final Executor executor = Executors.newSingleThreadExecutor();

    public void sendText(String userMessage, Callback callback) {
        send(userMessage, null, callback);
    }

    public void sendTextWithImage(String userMessage, Bitmap image, Callback callback) {
        send(userMessage, image, callback);
    }

    private void send(String userMessage, Bitmap image, Callback callback) {
        executor.execute(() -> {
            try {
                String text = request(userMessage, image);
                if (text == null || text.trim().isEmpty()) {
                    callback.onError(new IllegalStateException("Empty response"));
                    return;
                }
                callback.onSuccess(text.trim());
            } catch (IOException | JSONException | IllegalStateException error) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "AI request failed: " + scrub(error.getClass().getSimpleName()));
                }
                callback.onError(error);
            }
        });
    }

    private String request(String userMessage, Bitmap image) throws IOException, JSONException {
        String endpoint = BuildConfig.SUPABASE_AI_FUNCTION_URL;
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new IllegalStateException("AI function URL is not configured");
        }

        JSONObject body = new JSONObject();
        body.put("message", userMessage);
        if (image != null) {
            body.put("imageBase64", encodeJpeg(image));
            body.put("imageMimeType", "image/jpeg");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        String anonKey = BuildConfig.SUPABASE_ANON_KEY;
        if (anonKey != null && !anonKey.trim().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + anonKey);
            connection.setRequestProperty("apikey", anonKey);
        }

        byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream stream = connection.getOutputStream()) {
            stream.write(requestBytes);
        }

        int status = connection.getResponseCode();
        String response = readResponse(connection, status);
        connection.disconnect();

        if (status < 200 || status >= 300) {
            throw new IOException("AI function returned " + status);
        }

        JSONObject json = new JSONObject(response);
        return json.optString("answer", "");
    }

    private String encodeJpeg(Bitmap image) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.JPEG, 85, stream);
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
    }

    private String readResponse(HttpURLConnection connection, int status) throws IOException {
        java.io.InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (java.util.Scanner scanner = new java.util.Scanner(stream, StandardCharsets.UTF_8.name())) {
            return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
        }
    }

    private String scrub(String value) {
        return value == null
                ? ""
                : value.replaceAll("(?i)gemini|google|supabase|model|api|quota|key", "[redacted]");
    }

    public interface Callback {
        void onSuccess(String response);

        void onError(Throwable error);
    }
}
