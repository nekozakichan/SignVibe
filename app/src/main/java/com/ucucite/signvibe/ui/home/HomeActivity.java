package com.ucucite.signvibe.ui.home;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.ucucite.signvibe.R;
import com.ucucite.signvibe.data.LessonPrefetcher;
import com.ucucite.signvibe.update.UpdateChecker;
import com.ucucite.signvibe.ui.ai.AiChatActivity;
import com.ucucite.signvibe.ui.game.GameFragment;
import com.ucucite.signvibe.ui.learn.LearnFragment;
import com.ucucite.signvibe.ui.profile.ProfileFragment;
import com.ucucite.signvibe.ui.translate.TranslateFragment;
import com.ucucite.signvibe.ui.tour.AppTour;
import com.ucucite.signvibe.ui.tour.SpotlightTourView;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class HomeActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    // App tour (spotlight walkthrough of the bottom tabs).
    private SpotlightTourView activeTour;
    private final OnBackPressedCallback closeTourOnBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (activeTour != null) activeTour.dismiss();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        bottomNav = findViewById(R.id.bottomNav);
        getOnBackPressedDispatcher().addCallback(this, closeTourOnBack);

        // Default tab
        if (savedInstanceState == null) {
            showFragment(new LearnFragment());
            // Quietly check GitHub Releases for a newer APK once per launch.
            UpdateChecker.checkForUpdate(this);
            // Warm the cache with all lesson videos so they can play offline later.
            LessonPrefetcher.prefetchAll(this);
            // First visit for this student: walk them through the tabs.
            if (AppTour.shouldAutoShow(this)) {
                bottomNav.post(this::startAppTour);
            }
        }

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_learn) {
                showFragment(new LearnFragment());
                return true;
            } else if (id == R.id.nav_translate) {
                showFragment(new TranslateFragment());
                return true;
            } else if (id == R.id.nav_game) {
                showFragment(new GameFragment());
                return true;
            } else if (id == R.id.nav_ai) {
                startActivity(new Intent(this, AiChatActivity.class));
                return false;
            } else if (id == R.id.nav_profile) {
                showFragment(new ProfileFragment());
                return true;
            }
            return false;
        });
    }

    /**
     * Programmatically switch to the Profile tab. Setting the selected item id
     * fires the same OnItemSelectedListener above, so ProfileFragment is shown
     * through the single navigation path (no separate transaction to keep in sync).
     * Used by the Flappy Sign "View Leaderboard" button via GameFragment.
     */
    public void goToProfile() {
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_profile);
        }
    }

    /** Shows the spotlight tour of the bottom tabs. Also used by Profile's "Take the App Tour". */
    public void startAppTour() {
        if (activeTour != null || isFinishing()) return;
        activeTour = SpotlightTourView.show(this, AppTour.homeSteps(this), completed -> {
            AppTour.markSeen(this);
            activeTour = null;
            closeTourOnBack.setEnabled(false);
        });
        closeTourOnBack.setEnabled(true);
    }

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }
}
