package com.ucucite.signvibe.ui.onboarding;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ucucite.signvibe.R;

import java.util.List;

public class OnboardingAdapter extends RecyclerView.Adapter<OnboardingAdapter.SlideViewHolder> {

    public interface OnboardingActionListener {
        void onNext(int currentPosition);
        void onGetStarted();
    }

    private final List<OnboardingSlide> slides;
    private final OnboardingActionListener listener;

    public OnboardingAdapter(List<OnboardingSlide> slides, OnboardingActionListener listener) {
        this.slides = slides;
        this.listener = listener;
    }

    @NonNull
    @Override
    public SlideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_onboarding_slide, parent, false);
        return new SlideViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SlideViewHolder holder, int position) {
        OnboardingSlide slide = slides.get(position);
        boolean isLast = position == slides.size() - 1;

        holder.txtTitle.setText(slide.getTitle());
        holder.txtDesc.setText(slide.getDescription());

        holder.btnAction.setText(isLast
                ? holder.itemView.getContext().getString(R.string.get_started)
                : holder.itemView.getContext().getString(R.string.next));

        holder.btnAction.setOnClickListener(v -> {
            if (isLast) {
                listener.onGetStarted();
            } else {
                listener.onNext(position);
            }
        });

        buildDots(holder, position);
    }

    private void buildDots(SlideViewHolder holder, int activePosition) {
        holder.layoutDots.removeAllViews();
        for (int i = 0; i < slides.size(); i++) {
            ImageView dot = new ImageView(holder.itemView.getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(5, 0, 5, 0);
            dot.setLayoutParams(params);
            dot.setImageResource(i == activePosition ? R.drawable.dot_active : R.drawable.dot_inactive);
            holder.layoutDots.addView(dot);
        }
    }

    @Override
    public int getItemCount() {
        return slides.size();
    }

    static class SlideViewHolder extends RecyclerView.ViewHolder {
        TextView txtTitle;
        TextView txtDesc;
        TextView btnAction;
        LinearLayout layoutDots;

        SlideViewHolder(@NonNull View itemView) {
            super(itemView);
            txtTitle = itemView.findViewById(R.id.txtSlideTitle);
            txtDesc = itemView.findViewById(R.id.txtSlideDesc);
            btnAction = itemView.findViewById(R.id.btnAction);
            layoutDots = itemView.findViewById(R.id.layoutDots);
        }
    }
}