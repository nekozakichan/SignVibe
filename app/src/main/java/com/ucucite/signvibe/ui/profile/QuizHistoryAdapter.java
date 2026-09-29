package com.ucucite.signvibe.ui.profile;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.ucucite.signvibe.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Rows for the Profile tab's "My Quizzes" card. */
public class QuizHistoryAdapter extends RecyclerView.Adapter<QuizHistoryAdapter.VH> {

    private final List<QuizHistoryItem> items = new ArrayList<>();
    private final Map<String, String> moduleNames = new HashMap<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("MMM d, yyyy", Locale.US);

    /**
     * @param newItems    the student's quiz results, already sorted for display
     * @param names       moduleId -> module display name (from the modules collection);
     *                    ids not in the map fall back to a prettified module_id
     */
    public void submit(List<QuizHistoryItem> newItems, Map<String, String> names) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        moduleNames.clear();
        if (names != null) moduleNames.putAll(names);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_quiz_history, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        QuizHistoryItem item = items.get(position);
        Context ctx = h.itemView.getContext();

        h.module.setText(displayName(item.getModuleId()));

        String when = item.getCompletedAt() != null
                ? dateFormat.format(item.getCompletedAt())
                : ctx.getString(R.string.quiz_history_just_now);
        h.date.setText(ctx.getString(R.string.quiz_history_best_fmt, when));

        h.score.setText(ctx.getString(R.string.quiz_history_score_fmt,
                item.getScore(), item.getTotal(), item.getPercentage()));
        // Teal when passed, red when below the pass mark — same colours as the quiz feedback.
        h.score.setTextColor(ContextCompat.getColor(ctx,
                item.isPassed() ? R.color.teal_dark : R.color.error_red));

        for (int i = 0; i < h.stars.length; i++) {
            h.stars[i].setImageResource(i < item.getStars()
                    ? R.drawable.ic_star_filled
                    : R.drawable.ic_star_empty);
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    private String displayName(String moduleId) {
        String name = moduleNames.get(moduleId);
        if (name != null && !name.isEmpty()) return name;
        return prettify(moduleId);
    }

    /** "common_words" -> "Common Words" — fallback when the module doc isn't found. */
    private static String prettify(String moduleId) {
        if (moduleId == null || moduleId.isEmpty()) return "Quiz";
        StringBuilder out = new StringBuilder();
        for (String word : moduleId.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView module, date, score;
        final ImageView[] stars = new ImageView[3];

        VH(@NonNull View v) {
            super(v);
            module = v.findViewById(R.id.txtQuizModule);
            date = v.findViewById(R.id.txtQuizDate);
            score = v.findViewById(R.id.txtQuizScore);
            stars[0] = v.findViewById(R.id.imgQuizStar1);
            stars[1] = v.findViewById(R.id.imgQuizStar2);
            stars[2] = v.findViewById(R.id.imgQuizStar3);
        }
    }
}
