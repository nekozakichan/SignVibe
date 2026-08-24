package com.ucucite.signvibe.ui.profile;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.ucucite.signvibe.R;

public class HowToUseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_how_to_use);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }
}