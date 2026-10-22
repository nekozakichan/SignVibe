package com.ucucite.signvibe.ui.tour;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;

/** One stop on the app tour: the view to light up (or none) and what to say about it. */
public final class TourStep {

    /** View to spotlight, or 0 for a centred card with nothing highlighted. */
    @IdRes public final int targetId;
    @NonNull public final String title;
    @NonNull public final String body;

    public TourStep(@IdRes int targetId, @NonNull String title, @NonNull String body) {
        this.targetId = targetId;
        this.title = title;
        this.body = body;
    }
}
