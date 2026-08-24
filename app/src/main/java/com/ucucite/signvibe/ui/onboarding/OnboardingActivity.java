package com.ucucite.signvibe.ui.onboarding;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.ucucite.signvibe.R;
import com.ucucite.signvibe.ui.auth.LoginActivity;

import java.util.ArrayList;
import java.util.List;

public class OnboardingActivity extends AppCompatActivity
        implements OnboardingAdapter.OnboardingActionListener {

    private static final String PREFS_NAME = "signvibe_prefs";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";

    private ViewPager2 viewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        viewPager = findViewById(R.id.viewPagerOnboarding);

        List<OnboardingSlide> slides = buildSlides();
        OnboardingAdapter adapter = new OnboardingAdapter(slides, this);
        viewPager.setAdapter(adapter);
    }

    private List<OnboardingSlide> buildSlides() {
        List<OnboardingSlide> list = new ArrayList<>();
        list.add(new OnboardingSlide(
                getString(R.string.onboarding_title_1),
                getString(R.string.onboarding_desc_1),
                0 // no illustration for now
        ));
        list.add(new OnboardingSlide(
                getString(R.string.onboarding_title_2),
                getString(R.string.onboarding_desc_2),
                0
        ));
        list.add(new OnboardingSlide(
                getString(R.string.onboarding_title_3),
                getString(R.string.onboarding_desc_3),
                0
        ));
        return list;
    }

    @Override
    public void onNext(int currentPosition) {
        viewPager.setCurrentItem(currentPosition + 1);
    }

    @Override
    public void onGetStarted() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply();

        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }
}