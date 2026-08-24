package com.ucucite.signvibe.ui.learn;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.ucucite.signvibe.R;

import java.util.List;

public class LearnModuleAdapter extends RecyclerView.Adapter<LearnModuleAdapter.ModuleViewHolder> {

    public interface OnModuleClickListener {
        void onModuleClick(LearnModule module);
    }

    private final List<LearnModule> modules;
    private final OnModuleClickListener listener;

    public LearnModuleAdapter(List<LearnModule> modules, OnModuleClickListener listener) {
        this.modules = modules;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ModuleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_module, parent, false);
        return new ModuleViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ModuleViewHolder holder, int position) {
        LearnModule module = modules.get(position);

        // Hybrid icon: emoji for the 5 known modules, uploaded image for teacher-added ones.
        if (module.hasImageIcon()) {
            holder.txtIcon.setVisibility(View.GONE);
            holder.imgIcon.setVisibility(View.VISIBLE);
            Glide.with(holder.imgIcon.getContext())
                    .load(module.getIconUrl())
                    .placeholder(android.R.drawable.ic_menu_gallery)  // shown while loading
                    .error(android.R.drawable.ic_menu_gallery)        // shown if load fails
                    .into(holder.imgIcon);
        } else {
            holder.imgIcon.setVisibility(View.GONE);
            holder.txtIcon.setVisibility(View.VISIBLE);
            holder.txtIcon.setText(module.getEmojiOrGlyph());
        }

        holder.txtTitle.setText(module.getTitle());
        holder.progressModule.setProgress(module.getProgressPercent());
        holder.txtLessonsCount.setText(
                holder.itemView.getContext().getString(
                        R.string.lessons_progress_fmt,
                        module.getCompletedLessons(),
                        module.getTotalLessons()
                )
        );

        holder.itemView.setOnClickListener(v -> listener.onModuleClick(module));
    }

    @Override
    public int getItemCount() {
        return modules.size();
    }

    static class ModuleViewHolder extends RecyclerView.ViewHolder {
        TextView txtIcon;
        ImageView imgIcon;
        TextView txtTitle;
        TextView txtLessonsCount;
        ProgressBar progressModule;

        ModuleViewHolder(@NonNull View itemView) {
            super(itemView);
            txtIcon = itemView.findViewById(R.id.txtIcon);
            imgIcon = itemView.findViewById(R.id.imgIcon);
            txtTitle = itemView.findViewById(R.id.txtTitle);
            txtLessonsCount = itemView.findViewById(R.id.txtLessonsCount);
            progressModule = itemView.findViewById(R.id.progressModule);
        }
    }
}