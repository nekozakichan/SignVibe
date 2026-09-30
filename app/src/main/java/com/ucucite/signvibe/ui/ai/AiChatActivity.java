package com.ucucite.signvibe.ui.ai;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ucucite.signvibe.R;
import com.ucucite.signvibe.ai.SignVibeAiClient;

import java.io.IOException;
import java.io.InputStream;

public class AiChatActivity extends AppCompatActivity {
    private static final int MAX_IMAGE_DIMENSION = 1024;

    private AiChatAdapter adapter;
    private SignVibeAiClient aiClient;
    private EditText inputMessage;
    private ImageView imagePreview;
    private TextView removeImage;
    private Uri selectedImageUri;
    private Bitmap selectedBitmap;

    private final ActivityResultLauncher<PickVisualMediaRequest> imagePicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    showSelectedImage(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);

        aiClient = new SignVibeAiClient();
        adapter = new AiChatAdapter();

        RecyclerView chatList = findViewById(R.id.chatList);
        inputMessage = findViewById(R.id.inputMessage);
        imagePreview = findViewById(R.id.imagePreview);
        removeImage = findViewById(R.id.removeImage);
        ImageButton attachButton = findViewById(R.id.btnAttach);
        ImageButton sendButton = findViewById(R.id.btnSend);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        chatList.setLayoutManager(layoutManager);
        chatList.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        attachButton.setOnClickListener(v -> imagePicker.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()
        ));
        removeImage.setOnClickListener(v -> clearSelectedImage());
        sendButton.setOnClickListener(v -> sendMessage(chatList));
    }

    private void showSelectedImage(Uri uri) {
        try {
            selectedBitmap = loadResizedBitmap(uri);
            selectedImageUri = uri;
            imagePreview.setImageURI(uri);
            imagePreview.setVisibility(View.VISIBLE);
            removeImage.setVisibility(View.VISIBLE);
        } catch (IOException e) {
            clearSelectedImage();
            adapter.add(AiChatMessage.error(getString(R.string.ai_image_error)));
        }
    }

    private void sendMessage(RecyclerView chatList) {
        String message = inputMessage.getText().toString().trim();
        if (message.isEmpty() && selectedBitmap == null) {
            return;
        }
        if (message.isEmpty()) {
            message = "What sign is this?";
        }

        Bitmap bitmapForRequest = selectedBitmap;
        adapter.add(AiChatMessage.user(message, selectedImageUri));
        adapter.add(AiChatMessage.loading());
        chatList.scrollToPosition(adapter.getItemCount() - 1);

        inputMessage.setText("");
        clearSelectedImage();

        SignVibeAiClient.Callback callback = new SignVibeAiClient.Callback() {
            @Override
            public void onSuccess(String response) {
                runOnUiThread(() -> {
                    adapter.replaceLast(AiChatMessage.ai(response));
                    chatList.scrollToPosition(adapter.getItemCount() - 1);
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    adapter.replaceLast(AiChatMessage.error(getString(R.string.ai_unavailable)));
                    chatList.scrollToPosition(adapter.getItemCount() - 1);
                });
            }
        };

        if (bitmapForRequest != null) {
            aiClient.sendTextWithImage(message, bitmapForRequest, callback);
        } else {
            aiClient.sendText(message, callback);
        }
    }

    private Bitmap loadResizedBitmap(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }

        int sampleSize = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / sampleSize > MAX_IMAGE_DIMENSION) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        Bitmap bitmap;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) {
            throw new IOException("Image decode failed");
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int max = Math.max(width, height);
        if (max <= MAX_IMAGE_DIMENSION) {
            return bitmap;
        }

        float scale = MAX_IMAGE_DIMENSION / (float) max;
        return Bitmap.createScaledBitmap(bitmap, Math.round(width * scale), Math.round(height * scale), true);
    }

    private void clearSelectedImage() {
        selectedImageUri = null;
        selectedBitmap = null;
        imagePreview.setImageDrawable(null);
        imagePreview.setVisibility(View.GONE);
        removeImage.setVisibility(View.GONE);
    }
}
