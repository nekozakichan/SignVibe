package com.ucucite.signvibe.ui.ai;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.SignVibeToast;
import com.ucucite.signvibe.ai.SignVibeAiClient;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class AiChatActivity extends AppCompatActivity {
    private static final int MAX_IMAGE_DIMENSION = 1024;

    // Camera photos go to getCacheDir()/camera — must match <cache-path> in res/xml/file_paths.xml.
    private static final String CAMERA_DIR = "camera";
    private static final String STATE_PENDING_PHOTO = "pending_photo_uri";

    private AiChatAdapter adapter;
    private SignVibeAiClient aiClient;
    private EditText inputMessage;
    private View previewFrame;
    private ImageView imagePreview;
    private TextView removeImage;
    private Uri selectedImageUri;
    private Bitmap selectedBitmap;

    /**
     * Where the camera app is saving the photo it's taking. Kept across a
     * recreate: low-memory phones often kill the chat while the camera is open,
     * and the result comes back to a fresh instance.
     */
    @Nullable private Uri pendingPhotoUri;

    private final ActivityResultLauncher<PickVisualMediaRequest> imagePicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) {
                    showSelectedImage(uri);
                }
            });

    private final ActivityResultLauncher<Uri> takePicture =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
                Uri uri = pendingPhotoUri;
                pendingPhotoUri = null;
                if (uri == null) return;
                if (Boolean.TRUE.equals(saved)) {
                    showSelectedImage(uri);
                } else {
                    deleteCameraPhoto(uri);   // student backed out of the camera
                }
            });

    // The app declares CAMERA (for sign detection), so Android requires the
    // permission to be granted before another app's camera can be launched.
    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchCamera();
                } else {
                    SignVibeToast.show(this, getString(R.string.ai_camera_permission),
                            Toast.LENGTH_LONG);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_chat);

        if (savedInstanceState != null) {
            pendingPhotoUri = savedInstanceState.getParcelable(STATE_PENDING_PHOTO);
        } else {
            deleteOldCameraPhotos();
        }

        aiClient = new SignVibeAiClient();
        adapter = new AiChatAdapter();

        RecyclerView chatList = findViewById(R.id.chatList);
        inputMessage = findViewById(R.id.inputMessage);
        previewFrame = findViewById(R.id.previewFrame);
        imagePreview = findViewById(R.id.imagePreview);
        removeImage = findViewById(R.id.removeImage);
        ImageButton attachButton = findViewById(R.id.btnAttach);
        ImageButton sendButton = findViewById(R.id.btnSend);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        chatList.setLayoutManager(layoutManager);
        chatList.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        attachButton.setOnClickListener(v -> showImageSourcePicker());
        removeImage.setOnClickListener(v -> clearSelectedImage());
        sendButton.setOnClickListener(v -> sendMessage(chatList));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(STATE_PENDING_PHOTO, pendingPhotoUri);
    }

    // ===== Choosing a photo =====

    /** Bottom sheet: take a new photo with the camera, or pick one from the album. */
    private void showImageSourcePicker() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.bottom_sheet_image_source, null);
        content.findViewById(R.id.optionCamera).setOnClickListener(v -> {
            sheet.dismiss();
            openCamera();
        });
        content.findViewById(R.id.optionAlbum).setOnClickListener(v -> {
            sheet.dismiss();
            openAlbum();
        });
        sheet.setContentView(content);
        sheet.show();
    }

    private void openAlbum() {
        imagePicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void openCamera() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            SignVibeToast.show(this, getString(R.string.ai_no_camera), Toast.LENGTH_SHORT);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    /** Opens the phone's camera app, which saves the photo into a file we own. */
    private void launchCamera() {
        try {
            File dir = cameraDir();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Couldn't create " + dir);
            }
            File photo = new File(dir, "hand_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", photo);
            takePicture.launch(pendingPhotoUri);
        } catch (IOException | IllegalArgumentException | ActivityNotFoundException e) {
            pendingPhotoUri = null;
            SignVibeToast.show(this, getString(R.string.ai_no_camera), Toast.LENGTH_SHORT);
        }
    }

    private File cameraDir() {
        return new File(getCacheDir(), CAMERA_DIR);
    }

    private void deleteCameraPhoto(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(cameraDir(), name).delete();
        }
    }

    /**
     * Photos from earlier visits are no longer shown anywhere, so clear them when
     * the chat opens fresh. (Not on every photo — this visit's chat bubbles still
     * point at their files.)
     */
    private void deleteOldCameraPhotos() {
        File[] old = cameraDir().listFiles();
        if (old == null) return;
        for (File f : old) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void showSelectedImage(Uri uri) {
        try {
            selectedBitmap = loadResizedBitmap(uri);
            selectedImageUri = uri;
            // Show the decoded bitmap (already upright and small), not the raw file.
            imagePreview.setImageBitmap(selectedBitmap);
            previewFrame.setVisibility(View.VISIBLE);
        } catch (IOException | OutOfMemoryError e) {
            clearSelectedImage();
            adapter.add(AiChatMessage.error(getString(R.string.ai_image_error)));
        }
    }

    // ===== Sending =====

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

    // ===== Image decoding =====

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

        // Phone cameras usually save sideways and record the real orientation in
        // EXIF. Turn the picture upright so the AI (and the preview) see the hand
        // the right way up.
        int rotation = readExifRotation(uri);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
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

    private int readExifRotation(Uri uri) {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) return 0;
            int orientation = new ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default:                                   return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;   // no/odd EXIF: keep the picture as it is
        }
    }

    private void clearSelectedImage() {
        selectedImageUri = null;
        selectedBitmap = null;
        imagePreview.setImageDrawable(null);
        previewFrame.setVisibility(View.GONE);
    }
}
