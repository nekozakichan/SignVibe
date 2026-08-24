package com.ucucite.signvibe.ui.learn;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ucucite.signvibe.R;

import java.util.List;

public class LessonAdapter extends RecyclerView.Adapter<LessonAdapter.LessonViewHolder> {

    public interface OnLessonClickListener {
        void onLessonClick(Lesson lesson);
    }

    private final List<Lesson> lessons;
    private final OnLessonClickListener listener;

    public LessonAdapter(List<Lesson> lessons, OnLessonClickListener listener) {
        this.lessons = lessons;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LessonViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_lesson, parent, false);
        return new LessonViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LessonViewHolder holder, int position) {
        Lesson lesson = lessons.get(position);

        holder.txtLabel.setText(lesson.getShortLabel());

        if (lesson.isLocked()) {
            // Locked: dim the card, hide the letter, show a big centered lock, no tap.
            holder.itemView.setAlpha(0.55f);
            holder.card.setBackgroundResource(R.drawable.bg_lesson_card);
            holder.txtLabel.setVisibility(View.INVISIBLE);
            holder.imgLock.setVisibility(View.VISIBLE);
            holder.imgCheck.setVisibility(View.GONE);
            holder.itemView.setOnClickListener(null);
            holder.itemView.setClickable(false);
        } else if (lesson.isCompleted()) {
            // Completed: teal card, white letter, check badge.
            holder.itemView.setAlpha(1f);
            holder.card.setBackgroundResource(R.drawable.bg_lesson_card_completed);
            holder.txtLabel.setVisibility(View.VISIBLE);
            holder.txtLabel.setTextColor(
                    holder.itemView.getContext().getResources().getColor(R.color.white, null));
            holder.imgLock.setVisibility(View.GONE);
            holder.imgCheck.setVisibility(View.VISIBLE);
            holder.imgCheck.setImageResource(R.drawable.ic_check_circle);
            holder.itemView.setClickable(true);
            holder.itemView.setOnClickListener(v -> listener.onLessonClick(lesson));
        } else {
            // Unlocked, not yet done.
            holder.itemView.setAlpha(1f);
            holder.card.setBackgroundResource(R.drawable.bg_lesson_card);
            holder.txtLabel.setVisibility(View.VISIBLE);
            holder.txtLabel.setTextColor(
                    holder.itemView.getContext().getResources().getColor(R.color.teal_dark, null));
            holder.imgLock.setVisibility(View.GONE);
            holder.imgCheck.setVisibility(View.GONE);
            holder.itemView.setClickable(true);
            holder.itemView.setOnClickListener(v -> listener.onLessonClick(lesson));
        }
    }

    @Override
    public int getItemCount() {
        return lessons.size();
    }

    static class LessonViewHolder extends RecyclerView.ViewHolder {
        View card;
        TextView txtLabel;
        ImageView imgCheck;
        ImageView imgLock;

        LessonViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.cardLesson);
            txtLabel = itemView.findViewById(R.id.txtLessonLabel);
            imgCheck = itemView.findViewById(R.id.imgCheck);
            imgLock = itemView.findViewById(R.id.imgLock);
        }
    }
}