package com.ucucite.signvibe.ui.game;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ucucite.signvibe.R;

import java.util.ArrayList;
import java.util.List;

public class LeaderboardAdapter extends RecyclerView.Adapter<LeaderboardAdapter.VH> {

    private final List<LeaderboardEntry> items = new ArrayList<>();

    public void submit(List<LeaderboardEntry> entries) {
        items.clear();
        if (entries != null) items.addAll(entries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_leaderboard, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LeaderboardEntry e = items.get(position);

        // Medals for the podium, plain numbers after.
        switch (e.getRank()) {
            case 1: h.rank.setText("\uD83E\uDD47"); break; // 🥇
            case 2: h.rank.setText("\uD83E\uDD48"); break; // 🥈
            case 3: h.rank.setText("\uD83E\uDD49"); break; // 🥉
            default: h.rank.setText(String.valueOf(e.getRank()));
        }

        String name = e.getDisplayName() != null ? e.getDisplayName() : "Player";
        h.name.setText(e.isCurrentUser() ? name + " (You)" : name);
        h.score.setText(String.valueOf(e.getBestScore()));

        // Gently highlight the child's own row so they can always spot themselves.
        if (e.isCurrentUser()) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFFFF0F3);
            bg.setCornerRadius(dp(h.root, 12));
            h.root.setBackground(bg);
        } else {
            h.root.setBackground(null);
        }
    }

    @Override
    public int getItemCount() { return items.size(); }

    private static float dp(View v, float value) {
        return value * v.getResources().getDisplayMetrics().density;
    }

    static class VH extends RecyclerView.ViewHolder {
        final View root;
        final TextView rank, name, score;
        VH(@NonNull View v) {
            super(v);
            root = v.findViewById(R.id.rowRoot);
            rank = v.findViewById(R.id.txtRank);
            name = v.findViewById(R.id.txtName);
            score = v.findViewById(R.id.txtRowScore);
        }
    }
}