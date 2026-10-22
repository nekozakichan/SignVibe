package com.ucucite.signvibe.ui.tour;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.ucucite.signvibe.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The home-screen tour: its steps, and whether this student has already seen it.
 *
 * "Seen" is remembered per student (Firebase uid), not per phone, because SPED
 * classes often share one tablet — each child gets the tour on their first login.
 */
public final class AppTour {

    private static final String PREFS = "signvibe_tour";

    private AppTour() { /* no instances */ }

    private static String seenKey() {
        String uid = FirebaseAuth.getInstance().getUid();
        return "home_tour_seen_" + (uid != null ? uid : "guest");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True the first time this student reaches the home screen. */
    public static boolean shouldAutoShow(@NonNull Context context) {
        return !prefs(context).getBoolean(seenKey(), false);
    }

    /** Called when the tour ends, whether finished or skipped. */
    public static void markSeen(@NonNull Context context) {
        prefs(context).edit().putBoolean(seenKey(), true).apply();
    }

    /** Welcome, each bottom tab left to right, then a closing card. */
    @NonNull
    public static List<TourStep> homeSteps(@NonNull Context c) {
        List<TourStep> steps = new ArrayList<>();
        steps.add(new TourStep(0,
                c.getString(R.string.tour_welcome_title), c.getString(R.string.tour_welcome_body)));
        steps.add(new TourStep(R.id.nav_learn,
                c.getString(R.string.tour_learn_title), c.getString(R.string.tour_learn_body)));
        steps.add(new TourStep(R.id.nav_translate,
                c.getString(R.string.tour_translate_title), c.getString(R.string.tour_translate_body)));
        steps.add(new TourStep(R.id.nav_game,
                c.getString(R.string.tour_game_title), c.getString(R.string.tour_game_body)));
        steps.add(new TourStep(R.id.nav_ai,
                c.getString(R.string.tour_ai_title), c.getString(R.string.tour_ai_body)));
        steps.add(new TourStep(R.id.nav_profile,
                c.getString(R.string.tour_profile_title), c.getString(R.string.tour_profile_body)));
        steps.add(new TourStep(0,
                c.getString(R.string.tour_end_title), c.getString(R.string.tour_end_body)));
        return steps;
    }
}
