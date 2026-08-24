package com.ucucite.signvibe.ui.translate;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.CompositePageTransformer;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.ucucite.signvibe.R;

import java.util.ArrayList;
import java.util.List;

public class TranslateFragment extends Fragment {

    private ViewPager2 viewPager;
    private LinearLayout layoutDots;
    private final List<ImageView> dots = new ArrayList<>();
    private List<TranslateCategory> categories;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_translate, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        viewPager = view.findViewById(R.id.viewPagerCategories);
        layoutDots = view.findViewById(R.id.layoutDots);

        categories = buildCategories();
        TranslateCategoryAdapter adapter = new TranslateCategoryAdapter(categories, this::onCategoryClick);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(3);

        CompositePageTransformer transformer = new CompositePageTransformer();
        transformer.addTransformer(new MarginPageTransformer(24));
        transformer.addTransformer((page, position) -> {
            float scale = 1f - (0.12f * Math.abs(position));
            page.setScaleY(Math.max(0.88f, scale));
        });
        viewPager.setPageTransformer(transformer);

        setupDots();
        setCurrentDot(0);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                setCurrentDot(position);
            }
        });
    }

    private List<TranslateCategory> buildCategories() {
        List<TranslateCategory> list = new ArrayList<>();
        list.add(new TranslateCategory(
                TranslateCategory.TYPE_ALPHABET,
                getString(R.string.translate_alphabet_title),
                getString(R.string.translate_alphabet_desc),
                "\u270A" // raised fist emoji
        ));
        list.add(new TranslateCategory(
                TranslateCategory.TYPE_NUMBER,
                getString(R.string.translate_number_title),
                getString(R.string.translate_number_desc),
                "\u270C\uFE0F" // victory hand emoji
        ));
        list.add(new TranslateCategory(
                TranslateCategory.TYPE_WORD,
                getString(R.string.translate_word_title),
                getString(R.string.translate_word_desc),
                null
        ));
        return list;
    }

    private void setupDots() {
        dots.clear();
        layoutDots.removeAllViews();

        for (int i = 0; i < categories.size(); i++) {
            ImageView dot = new ImageView(requireContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(4, 0, 4, 0);
            dot.setLayoutParams(params);
            dot.setImageResource(R.drawable.dot_inactive_translate);
            dots.add(dot);
            layoutDots.addView(dot);
        }
    }

    private void setCurrentDot(int position) {
        for (int i = 0; i < dots.size(); i++) {
            dots.get(i).setImageResource(
                    i == position ? R.drawable.dot_active_pill : R.drawable.dot_inactive_translate
            );
        }
    }

    private void onCategoryClick(TranslateCategory category) {
        String type;
        switch (category.getType()) {
            case TranslateCategory.TYPE_ALPHABET:
                type = "alphabet";
                break;
            case TranslateCategory.TYPE_WORD:
                type = "word";
                break;
            case TranslateCategory.TYPE_NUMBER:
            default:
                type = "number";
                break;
        }

        Intent intent = new Intent(requireContext(), DetectionInstructionsActivity.class);
        intent.putExtra(DetectionInstructionsActivity.EXTRA_TYPE, type);
        startActivity(intent);
    }
}