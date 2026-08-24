package com.ucucite.signvibe.ui.quiz;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.ucucite.signvibe.R;

public class QuizResultActivity extends AppCompatActivity {

    public static final String EXTRA_CORRECT = "extra_correct";
    public static final String EXTRA_TOTAL = "extra_total";
    public static final String EXTRA_PERCENTAGE = "extra_percentage";
    public static final String EXTRA_PASSED = "extra_passed";
    public static final String EXTRA_STARS = "extra_stars";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz_result);

        int correct = getIntent().getIntExtra(EXTRA_CORRECT, 0);
        int total = getIntent().getIntExtra(EXTRA_TOTAL, 0);
        int percentage = getIntent().getIntExtra(EXTRA_PERCENTAGE, 0);
        boolean passed = getIntent().getBooleanExtra(EXTRA_PASSED, false);
        int stars = getIntent().getIntExtra(EXTRA_STARS, 0);

        ImageView star1 = findViewById(R.id.star1);
        ImageView star2 = findViewById(R.id.star2);
        ImageView star3 = findViewById(R.id.star3);
        ImageView[] starViews = { star1, star2, star3 };

        // Fill earned stars gold, leave the rest as faint empty stars.
        for (int i = 0; i < starViews.length; i++) {
            starViews[i].setImageResource(
                    i < stars ? R.drawable.ic_star_filled : R.drawable.ic_star_empty);
        }

        TextView txtHeadline = findViewById(R.id.txtHeadline);
        TextView txtScore = findViewById(R.id.txtScore);

        if (passed) {
            txtHeadline.setText(starsHeadline(stars));
        } else {
            txtHeadline.setText(getString(R.string.quiz_result_try_again));
        }
        txtScore.setText(getString(R.string.quiz_result_score_fmt, correct, total, percentage));

        findViewById(R.id.btnDone).setOnClickListener(v -> finish());
    }

    private String starsHeadline(int stars) {
        switch (stars) {
            case 3: return getString(R.string.quiz_result_perfect);
            case 2: return getString(R.string.quiz_result_great);
            default: return getString(R.string.quiz_result_good);
        }
    }
}