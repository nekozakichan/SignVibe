package com.ucucite.signvibe.ui.translate;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.ucucite.signvibe.R;
import com.ucucite.signvibe.SignVibeToast;

public class DetectionInstructionsActivity extends AppCompatActivity {

    public static final String EXTRA_TYPE = "extra_type"; // "alphabet", "number", "word"

    private String type;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection_instructions);

        type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) type = "number";

        bindContent();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnStartDetection).setOnClickListener(v -> onStartDetection());
    }

    private void bindContent() {
        TextView txtTitle = findViewById(R.id.txtTitle);
        TextView txtBullet3 = findViewById(R.id.txtBullet3);

        switch (type) {
            case "alphabet":
                txtTitle.setText(R.string.instructions_title_alphabet);
                txtBullet3.setText(R.string.instructions_bullet3_alphabet);
                break;
            case "word":
                txtTitle.setText(R.string.instructions_title_word);
                txtBullet3.setText(R.string.instructions_bullet3_word);
                break;
            case "number":
            default:
                txtTitle.setText(R.string.instructions_title_number);
                txtBullet3.setText(R.string.instructions_bullet3_number);
                break;
        }
    }

    private void onStartDetection() {
        if (type.equals("number") || type.equals("alphabet")) {
            Intent intent = new Intent(this, DetectionCameraActivity.class);
            intent.putExtra(DetectionCameraActivity.EXTRA_TYPE, type);
            startActivity(intent);
        } else if (type.equals("word")) {
            // Dynamic words use the deliberate-capture flow (Pose + Hand + sequence model).
            startActivity(new Intent(this, WordDetectionActivity.class));
        } else {
            SignVibeToast.show(this, getString(R.string.coming_soon_category), Toast.LENGTH_SHORT);
        }
    }
}