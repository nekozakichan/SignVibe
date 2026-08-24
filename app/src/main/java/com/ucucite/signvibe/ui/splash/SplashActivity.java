package com.ucucite.signvibe.ui.splash;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.ucucite.signvibe.R;
import com.ucucite.signvibe.ui.auth.LoginActivity;
import com.ucucite.signvibe.ui.home.HomeActivity;
import com.ucucite.signvibe.ui.onboarding.OnboardingActivity;

public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY_MS = 1500;
    private static final String PREFS_NAME = "signvibe_prefs";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        new Handler(Looper.getMainLooper()).postDelayed(this::routeNext, SPLASH_DELAY_MS);
    }

    private void routeNext() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, false);

        Intent intent;

        if (!onboardingDone) {
            // First time opening the app — show onboarding
            intent = new Intent(this, OnboardingActivity.class);
        } else {
            // Onboarding already seen — check if user is logged in
            FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
            if (currentUser != null) {
                intent = new Intent(this, HomeActivity.class);
            } else {
                intent = new Intent(this, LoginActivity.class);
            }
        }

        startActivity(intent);
        finish();
    }
}