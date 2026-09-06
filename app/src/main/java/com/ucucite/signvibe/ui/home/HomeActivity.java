package com.ucucite.signvibe.ui.home;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.ucucite.signvibe.R;
import com.ucucite.signvibe.update.UpdateChecker;
import com.ucucite.signvibe.ui.game.GameFragment;
import com.ucucite.signvibe.ui.learn.LearnFragment;
import com.ucucite.signvibe.ui.profile.ProfileFragment;
import com.ucucite.signvibe.ui.translate.TranslateFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class HomeActivity extends AppCompatActivity {

    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        bottomNav = findViewById(R.id.bottomNav);

        // Default tab
        if (savedInstanceState == null) {
            showFragment(new LearnFragment());
            // Quietly check GitHub Releases for a newer APK once per launch.
            UpdateChecker.checkForUpdate(this);
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

    private void showFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }
}