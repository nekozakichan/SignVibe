package com.ucucite.signvibe.ui.translate;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ucucite.signvibe.R;

import java.util.List;

public class TranslateCategoryAdapter extends RecyclerView.Adapter<TranslateCategoryAdapter.CategoryViewHolder> {

    public interface OnCategoryClickListener {
        void onCategoryClick(TranslateCategory category);
    }

    private final List<TranslateCategory> categories;
    private final OnCategoryClickListener listener;

    public TranslateCategoryAdapter(List<TranslateCategory> categories, OnCategoryClickListener listener) {
        this.categories = categories;
        this.listener = listener;
    }

    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_translate_category, parent, false);
        return new CategoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
        TranslateCategory category = categories.get(position);

        holder.txtTitle.setText(category.getTitle());
        holder.txtDesc.setText(category.getDescription());

        if (category.getType() == TranslateCategory.TYPE_WORD) {
            holder.txtEmoji.setVisibility(View.GONE);
            holder.imgWordIcon.setVisibility(View.VISIBLE);
        } else {
            holder.txtEmoji.setVisibility(View.VISIBLE);
            holder.imgWordIcon.setVisibility(View.GONE);
            holder.txtEmoji.setText(category.getEmoji());
        }

        holder.itemView.setOnClickListener(v -> listener.onCategoryClick(category));
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    static class CategoryViewHolder extends RecyclerView.ViewHolder {
        FrameLayout scanFrame;
        TextView txtEmoji;
        ImageView imgWordIcon;
        TextView txtTitle;
        TextView txtDesc;

        CategoryViewHolder(@NonNull View itemView) {
            super(itemView);
            scanFrame = itemView.findViewById(R.id.scanFrame);
            txtEmoji = itemView.findViewById(R.id.txtEmoji);
            imgWordIcon = itemView.findViewById(R.id.imgWordIcon);
            txtTitle = itemView.findViewById(R.id.txtTitle);
            txtDesc = itemView.findViewById(R.id.txtDesc);
        }
    }
}