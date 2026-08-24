package com.ucucite.signvibe.ui.game;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.ucucite.signvibe.R;

import java.util.ArrayList;
import java.util.List;

public class FlappySignActivity extends AppCompatActivity implements FlappySignView.GameListener {

    /** Set on the result Intent when the child taps "View Leaderboard". */
    public static final String EXTRA_OPEN_PROFILE = "open_profile";

    private static final String[] FLYERS =
            {"\uD83E\uDD1F", "\u270B", "\uD83D\uDC4C", "\uD83E\uDD19",
                    "\u270C\uFE0F", "\uD83D\uDC4D", "\uD83D\uDD90\uFE0F", "\u270A"};

    private FlappySignView gameView;
    private android.view.View overlayStart, overlayOver;
    private TextView flyerPreview, txtScore, txtStars, txtNewBest;
    private String selectedFlyer = FLYERS[0];
    private android.view.View overlayPause;
    private TextView btnPause;
    private final List<TextView> tiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_flappy_sign);

        gameView = findViewById(R.id.gameView);
        overlayStart = findViewById(R.id.overlayStart);
        overlayOver = findViewById(R.id.overlayOver);
        flyerPreview = findViewById(R.id.flyerPreview);
        txtScore = findViewById(R.id.txtScore);
        txtStars = findViewById(R.id.txtStars);
        txtNewBest = findViewById(R.id.txtNewBest);

        // Find pause views
        overlayPause = findViewById(R.id.overlayPause);
        btnPause = findViewById(R.id.btnPause);

        gameView.setListener(this);
        gameView.setFlyer(selectedFlyer);

        buildFlyerGrid();

        Button btnPlay = findViewById(R.id.btnPlay);
        Button btnRetry = findViewById(R.id.btnRetry);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnViewLeaderboard = findViewById(R.id.btnViewLeaderboard);
        Button btnResume = findViewById(R.id.btnResume);
        Button btnQuit = findViewById(R.id.btnQuit);

        btnPlay.setBackground(pill(0xFFFF5A7A));
        btnRetry.setBackground(pill(0xFFFF5A7A));
        btnViewLeaderboard.setBackground(pill(0xFFFFA000));
        btnBack.setBackground(pill(0xFF2EC4B6));
        btnResume.setBackground(pill(0xFFFF5A7A));
        btnQuit.setBackground(pill(0xFF2EC4B6));

        btnPlay.setOnClickListener(v -> startGame());
        btnRetry.setOnClickListener(v -> startGame());
        btnBack.setOnClickListener(v -> finish());
        btnViewLeaderboard.setOnClickListener(v -> {
            // Signal Home to switch to the Profile tab, then close the game.
            Intent data = new Intent();
            data.putExtra(EXTRA_OPEN_PROFILE, true);
            setResult(RESULT_OK, data);
            finish();
        });

        // Pause button click listeners
        // Only allow pausing if game is playing and NOT counting down
        btnPause.setOnClickListener(v -> {
            if (gameView.isPlaying() && !gameView.isCountingDown()) {
                pauseGame();
            }
        });

        btnResume.setOnClickListener(v -> resumeGame());
        btnQuit.setOnClickListener(v -> finish());
    }

    private void buildFlyerGrid() {
        GridLayout grid = findViewById(R.id.flyerGrid);
        for (String e : FLYERS) {
            TextView t = new TextView(this);
            t.setText(e);
            t.setTextSize(24);
            t.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(52); lp.height = dp(52);
            lp.setMargins(dp(5), dp(5), dp(5), dp(5));
            t.setLayoutParams(lp);
            t.setBackground(tileBg(e.equals(selectedFlyer)));
            t.setOnClickListener(v -> selectFlyer(e, t));
            grid.addView(t);
            tiles.add(t);
        }
    }

    private void selectFlyer(String emoji, TextView tile) {
        selectedFlyer = emoji;
        flyerPreview.setText(emoji);
        for (TextView t : tiles) t.setBackground(tileBg(false));
        tile.setBackground(tileBg(true));
        gameView.setFlyer(emoji);
    }

    private void startGame() {
        overlayStart.setVisibility(android.view.View.GONE);
        overlayOver.setVisibility(android.view.View.GONE);
        overlayPause.setVisibility(android.view.View.GONE);
        btnPause.setVisibility(android.view.View.VISIBLE);
        gameView.startGame();
    }

    private void pauseGame() {
        gameView.pauseGame();
        overlayPause.setVisibility(android.view.View.VISIBLE);
        btnPause.setVisibility(android.view.View.GONE);
    }

    private void resumeGame() {
        overlayPause.setVisibility(android.view.View.GONE);
        btnPause.setVisibility(android.view.View.VISIBLE);
        gameView.resumeGame(); // starts the 3-2-1 countdown in the view
    }

    @Override
    public void onGameOver(int score, int stars) {
        runOnUiThread(() -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) sb.append(i < stars ? "\u2B50" : "\u2606");
            txtStars.setText(sb.toString());
            txtScore.setText("Score: " + score);
            txtNewBest.setVisibility(android.view.View.GONE);
            btnPause.setVisibility(android.view.View.GONE);
            overlayOver.setVisibility(android.view.View.VISIBLE);
        });

        // Keep-best write to the leaderboard; only reveal the badge on a real PB.
        LeaderboardRepository.submitScore(score, (success, newBest) -> {
            if (newBest) runOnUiThread(() -> txtNewBest.setVisibility(android.view.View.VISIBLE));
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView.isPlaying()) pauseGame();
    }

    private GradientDrawable tileBg(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(selected ? 0xFFFFF0F3 : 0xFFFFFFFF);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(3), selected ? 0xFFFF5A7A : 0x00000000);
        return g;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(999));
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}