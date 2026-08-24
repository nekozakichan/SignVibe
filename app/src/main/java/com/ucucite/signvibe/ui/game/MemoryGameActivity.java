package com.ucucite.signvibe.ui.game;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.firebase.firestore.FirebaseFirestore;
import com.ucucite.signvibe.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@UnstableApi
public class MemoryGameActivity extends AppCompatActivity {

    public static final String EXTRA_PAIRS = "extra_pairs";
    public static final String EXTRA_COLUMNS = "extra_columns";
    public static final String EXTRA_LABEL = "extra_label";

    private static final int TYPE_VIDEO = 0;
    private static final int TYPE_WORD = 1;

    // Timing
    private static final long MIN_VIEW_MS = 1500;       // shortest a pair stays up (very short clips)
    private static final long MAX_VIEW_MS = 3500;       // longest a pair stays up (long clips)
    private static final long FALLBACK_VIEW_MS = 2000;  // used if a clip's length is unknown
    private static final long MATCH_EXTRA_MS = 300;     // small celebratory beat after a match's clip plays
    private static final long MAX_WAIT_MS = 6000;       // safety: never wait forever on a stuck video

    private int requestedPairs;
    private int columns;
    private String difficultyLabel = "Easy";   // which button was pressed; stored on each session

    // ── round completion-time tracking (foreground time only) ──
    // We measure how long the child actively spends on a round. If the app is
    // backgrounded (phone set down, app switched — common with young learners),
    // that idle time is EXCLUDED so it can't inflate the trend. elapsedRealtime
    // is monotonic, so a clock/timezone change mid-round can't corrupt it.
    private long roundStartElapsed;   // when the current round's clock started
    private long pausedAccumMs;       // total ms this round spent backgrounded
    private long pauseMarkElapsed;    // when the current pause began (0 == not paused)

    private GridLayout gridBoard;
    private View loading, emptyState, winOverlay;
    private TextView txtMoves, txtWinMoves;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Card> cards = new ArrayList<>();
    private final List<Lesson> allLessons = new ArrayList<>();

    private Card first, second;
    private boolean busy;
    private int moves;
    private int matchedCount;
    private int activePairs;

    private ExecutorService prefetchPool;

    // ── lightweight holders ──
    private static class Lesson {
        final String title, videoUrl;
        Lesson(String title, String videoUrl) { this.title = title; this.videoUrl = videoUrl; }
    }

    private static class Card {
        int pairId, type;
        String content;         // word text, or video url
        View cardRoot, front;
        TextView backQ, txtWord;
        PlayerView playerView;
        ProgressBar spinner;
        ImageView imgCheck;
        ExoPlayer player;
        boolean faceUp, matched;
        boolean contentShown;           // video: first frame painted · word: visible instantly
        Runnable onShownCallback;       // fired once, when this card becomes visible
        long clipDurationMs;            // this card's video length, learned once it's ready
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memory_game);

        requestedPairs = getIntent().getIntExtra(EXTRA_PAIRS, 3);
        columns = getIntent().getIntExtra(EXTRA_COLUMNS, 2);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label != null && !label.trim().isEmpty()) difficultyLabel = label.trim();

        gridBoard = findViewById(R.id.gridBoard);
        loading = findViewById(R.id.loading);
        emptyState = findViewById(R.id.emptyState);
        winOverlay = findViewById(R.id.winOverlay);
        txtMoves = findViewById(R.id.txtMoves);
        txtWinMoves = findViewById(R.id.txtWinMoves);

        TextView title = findViewById(R.id.txtTitle);
        if (label != null) title.setText("Memory Match · " + label);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnBackToGames).setOnClickListener(v -> finish());
        findViewById(R.id.btnPlayAgain).setOnClickListener(v -> {
            winOverlay.setVisibility(View.GONE);
            buildBoard();
        });

        gridBoard.setColumnCount(columns);
        fetchLessons();
    }

    private void fetchLessons() {
        loading.setVisibility(View.VISIBLE);
        FirebaseFirestore.getInstance()
                .collection("lessons")
                .whereEqualTo("status", "published")
                .get()
                .addOnSuccessListener(snap -> {
                    loading.setVisibility(View.GONE);

                    // Dedupe by title; skip blanks and lessons with no video.
                    Map<String, String> byTitle = new LinkedHashMap<>();
                    snap.forEach(doc -> {
                        String t = doc.getString("title");
                        String url = doc.getString("video_url");
                        if (t != null && !t.trim().isEmpty()
                                && url != null && !url.trim().isEmpty()
                                && !byTitle.containsKey(t.trim())) {
                            byTitle.put(t.trim(), url);
                        }
                    });

                    allLessons.clear();
                    for (Map.Entry<String, String> e : byTitle.entrySet()) {
                        allLessons.add(new Lesson(e.getKey(), e.getValue()));
                    }

                    if (allLessons.size() < 3) {
                        emptyState.setVisibility(View.VISIBLE);
                        return;
                    }
                    buildBoard();
                })
                .addOnFailureListener(e -> {
                    loading.setVisibility(View.GONE);
                    emptyState.setVisibility(View.VISIBLE);
                });
    }

    private void buildBoard() {
        releaseAllPlayers();
        gridBoard.removeAllViews();
        cards.clear();
        first = second = null;
        busy = false;
        moves = 0;
        matchedCount = 0;
        updateMoves();

        // Start this round's completion clock fresh. The board is tappable
        // essentially as soon as the cards are added, so board-ready is the
        // honest "the child can begin" moment.
        roundStartElapsed = SystemClock.elapsedRealtime();
        pausedAccumMs = 0L;
        pauseMarkElapsed = 0L;

        // Clamp pairs to what the lesson pool can supply.
        activePairs = Math.min(requestedPairs, allLessons.size());

        List<Lesson> pool = new ArrayList<>(allLessons);
        Collections.shuffle(pool);

        List<Card> built = new ArrayList<>();
        for (int i = 0; i < activePairs; i++) {
            Lesson lesson = pool.get(i);

            Card videoCard = makeCard(i, TYPE_VIDEO, lesson.videoUrl);
            Card wordCard = makeCard(i, TYPE_WORD, lesson.title);
            built.add(videoCard);
            built.add(wordCard);
        }
        Collections.shuffle(built);

        int heightPx = cardHeightPx();
        int margin = dp(5);

        for (Card c : built) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = heightPx;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(margin, margin, margin, margin);
            c.cardRoot.setLayoutParams(lp);
            gridBoard.addView(c.cardRoot);
            cards.add(c);
        }

        prefetchVideos();
    }

    /** Warm the cache for every video card in this round, so first flips play instantly. */
    private void prefetchVideos() {
        if (prefetchPool != null) prefetchPool.shutdownNow();
        prefetchPool = Executors.newFixedThreadPool(2);

        CacheDataSource.Factory factory = new CacheDataSource.Factory()
                .setCache(VideoCache.get(this))
                .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        for (Card c : cards) {
            if (c.type != TYPE_VIDEO || c.content == null) continue;
            final String url = c.content;
            prefetchPool.execute(() -> {
                try {
                    DataSpec spec = new DataSpec(Uri.parse(url));
                    CacheWriter writer = new CacheWriter(
                            factory.createDataSource(), spec, null, null);
                    writer.cache();   // downloads to cache; skips bytes already cached
                } catch (Exception ignored) {
                    // Offline or cancelled — the live player still fetches on demand.
                }
            });
        }
    }

    private Card makeCard(int pairId, int type, String content) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_memory_card, gridBoard, false);

        Card c = new Card();
        c.pairId = pairId;
        c.type = type;
        c.content = content;
        c.cardRoot = v;
        c.front = v.findViewById(R.id.front);
        c.backQ = v.findViewById(R.id.backQ);
        c.txtWord = v.findViewById(R.id.txtWord);
        c.playerView = v.findViewById(R.id.playerView);
        c.spinner = v.findViewById(R.id.cardLoading);
        c.imgCheck = v.findViewById(R.id.imgCheck);

        c.playerView.setUseController(false);
        c.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

        // Show only the relevant front child for this card type.
        if (type == TYPE_VIDEO) {
            c.txtWord.setVisibility(View.GONE);
        } else {
            c.playerView.setVisibility(View.GONE);
            c.txtWord.setText(content);
        }

        v.setOnClickListener(view -> onCardTapped(c));
        return c;
    }

    private void onCardTapped(Card c) {
        if (busy || c.matched || c.faceUp) return;

        flipToFront(c);

        if (first == null) {
            first = c;
            return;
        }

        second = c;
        busy = true;
        moves++;
        updateMoves();

        boolean isMatch = first.pairId == second.pairId;
        final Card a = first, b = second;
        first = second = null;

        if (isMatch) {
            // Play the sign through (clip length), then confirm — same feel no matter the flip order.
            runWhenBothShown(a, b, () -> handler.postDelayed(() -> {
                markMatched(a);
                markMatched(b);
                matchedCount++;
                busy = false;
                if (matchedCount == activePairs) showWin();
            }, viewDurationFor(a, b) + MATCH_EXTRA_MS));
        } else {
            // Play the sign through so it can be studied, then flip back.
            runWhenBothShown(a, b, () -> handler.postDelayed(() -> {
                flipToBack(a);
                flipToBack(b);
                busy = false;
            }, viewDurationFor(a, b)));
        }
    }

    /** Runs {@code action} once, as soon as both cards have their content visible (with a safety cap). */
    private void runWhenBothShown(Card a, Card b, Runnable action) {
        final boolean[] done = {false};
        Runnable proceed = () -> {
            if (done[0]) return;
            done[0] = true;
            action.run();
        };
        Runnable gate = () -> {
            if (a.contentShown && b.contentShown) proceed.run();
        };
        awaitShown(a, gate);
        awaitShown(b, gate);
        // Safety net: if a video never renders, don't hang the game.
        handler.postDelayed(proceed, MAX_WAIT_MS);
    }

    /** How long a revealed pair stays up: the video's length, clamped to a comfy range. */
    private long viewDurationFor(Card a, Card b) {
        // The pair is always one video + one word; take whichever clip length we know.
        long clip = Math.max(a.clipDurationMs, b.clipDurationMs);
        if (clip <= 0) return FALLBACK_VIEW_MS;               // length unknown → safe default
        return Math.max(MIN_VIEW_MS, Math.min(clip, MAX_VIEW_MS));
    }

    private void awaitShown(Card c, Runnable gate) {
        if (c.contentShown) gate.run();
        else c.onShownCallback = gate;
    }

    /** Marks a card visible and fires anything waiting on it. */
    private void markShown(Card c) {
        if (c.contentShown) return;
        c.contentShown = true;
        if (c.spinner != null) c.spinner.setVisibility(View.GONE);
        if (c.onShownCallback != null) {
            Runnable r = c.onShownCallback;
            c.onShownCallback = null;
            r.run();
        }
    }

    // ── flip animation (horizontal squash reads as a card flip) ──
    private void flipToFront(Card c) {
        c.faceUp = true;
        ObjectAnimator out = ObjectAnimator.ofFloat(c.cardRoot, "scaleX", 1f, 0f);
        out.setDuration(120);
        out.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                c.cardRoot.setBackgroundResource(R.drawable.bg_memory_card);
                c.backQ.setVisibility(View.GONE);
                c.front.setVisibility(View.VISIBLE);
                if (c.type == TYPE_VIDEO) {
                    startVideo(c);      // spinner shows; markShown() fires on first frame
                } else {
                    markShown(c);       // a word is readable immediately
                }
                ObjectAnimator in = ObjectAnimator.ofFloat(c.cardRoot, "scaleX", 0f, 1f);
                in.setDuration(120);
                in.start();
            }
        });
        out.start();
    }

    private void flipToBack(Card c) {
        c.faceUp = false;
        c.contentShown = false;     // next reveal must re-gate on the video showing again
        c.onShownCallback = null;
        ObjectAnimator out = ObjectAnimator.ofFloat(c.cardRoot, "scaleX", 1f, 0f);
        out.setDuration(120);
        out.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (c.type == TYPE_VIDEO) releaseVideo(c);
                c.front.setVisibility(View.GONE);
                c.backQ.setVisibility(View.VISIBLE);
                c.cardRoot.setBackgroundResource(R.drawable.bg_memory_card_back);
                ObjectAnimator in = ObjectAnimator.ofFloat(c.cardRoot, "scaleX", 0f, 1f);
                in.setDuration(120);
                in.start();
            }
        });
        out.start();
    }

    private void markMatched(Card c) {
        c.matched = true;
        if (c.type == TYPE_VIDEO) releaseVideo(c);
        c.cardRoot.setBackgroundResource(R.drawable.bg_memory_card_matched);
        c.imgCheck.setVisibility(View.VISIBLE);
    }

    private void startVideo(Card c) {
        if (c.spinner != null) c.spinner.setVisibility(View.VISIBLE);

        // Cache-backed source: first play downloads, repeat plays come from disk.
        CacheDataSource.Factory cacheFactory = new CacheDataSource.Factory()
                .setCache(VideoCache.get(this))
                .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        ExoPlayer p = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(cacheFactory))
                .setLoadControl(fastStartLoadControl())
                .build();

        c.player = p;
        c.playerView.setPlayer(p);

        p.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && c.player != null) {
                    long d = c.player.getDuration();
                    if (d > 0) c.clipDurationMs = d;   // now we know how long this sign runs
                }
            }
            @Override public void onRenderedFirstFrame() {
                markShown(c);   // the sign is now actually on screen
            }
            @Override public void onPlayerError(PlaybackException error) {
                markShown(c);   // a broken clip shouldn't stall the game
            }
        });

        p.setMediaItem(MediaItem.fromUri(c.content));
        p.setRepeatMode(Player.REPEAT_MODE_ALL);
        p.setVolume(0f);
        p.prepare();
        p.setPlayWhenReady(true);
    }

    /** Start playback as soon as a little is buffered, instead of waiting for a big cushion. */
    private LoadControl fastStartLoadControl() {
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        15000,  // min buffer
                        30000,  // max buffer
                        250,    // buffer needed to START (low = fast start)
                        500)    // buffer needed to resume after a stall
                .build();
    }

    private void releaseVideo(Card c) {
        if (c.player != null) {
            c.player.release();
            c.player = null;
        }
        c.playerView.setPlayer(null);
        if (c.spinner != null) c.spinner.setVisibility(View.GONE);
    }

    private void releaseAllPlayers() {
        for (Card c : cards) releaseVideo(c);
    }

    private void updateMoves() {
        txtMoves.setText("Tries: " + moves);
    }

    /**
     * Foreground time elapsed in the current round, in ms. Subtracts any time
     * the activity spent backgrounded — including an in-progress pause, defensively.
     */
    private long currentRoundDurationMs() {
        long now = SystemClock.elapsedRealtime();
        long liveExtraPause = (pauseMarkElapsed != 0L) ? (now - pauseMarkElapsed) : 0L;
        return Math.max(0L, (now - roundStartElapsed) - pausedAccumMs - liveExtraPause);
    }

    private void showWin() {
        // Capture completion time the instant the round is won — before the
        // overlay can sit on screen and before Play Again resets the clock.
        long durationMs = currentRoundDurationMs();

        int stars = starsFor(moves, activePairs);
        setStar(R.id.star1, stars >= 1);
        setStar(R.id.star2, stars >= 2);
        setStar(R.id.star3, stars >= 3);
        txtWinMoves.setText("Finished in " + moves + " tries");
        winOverlay.setVisibility(View.VISIBLE);

        // Record this round for the teacher dashboard. The kid's game is
        // unaffected by whether the write lands — it's fire-and-forget.
        recordSession(stars, durationMs);
    }

    /** Persist one finished round to Firestore. Stars stored == stars shown on the overlay. */
    private void recordSession(int stars, long durationMs) {
        int mistakes = Math.max(0, moves - activePairs);
        int score = scoreFor(moves, activePairs);
        GameSession session = new GameSession(
                difficultyLabel.toLowerCase(),
                activePairs,
                moves,
                mistakes,
                score,
                stars,
                durationMs
        );
        GameSessionRepository.record(session, success -> {
            if (!success) Log.w("MemoryGame", "Game session not saved (see logcat above).");
        });
    }

    private int starsFor(int moves, int pairs) {
        if (moves <= Math.ceil(pairs * 1.6)) return 3;
        if (moves <= pairs * 2.5) return 2;
        return 1;
    }

    /**
     * Points for a finished round. Tuned for SPED learners: finishing ALWAYS
     * earns a positive score, and a harder board is worth more. These constants
     * are yours to tune — the shape is what matters, not the exact numbers.
     *   perfect easy (3 pairs)  → 300
     *   perfect medium (6)      → 650
     *   perfect hard (8)        → 900
     */
    private int scoreFor(int moves, int pairs) {
        int extra = Math.max(0, moves - pairs);              // tries beyond the minimum
        int base = Math.max(pairs * 100 - extra * 10, pairs * 20);   // floor: never punitive
        return base + difficultyBonus();
    }

    private int difficultyBonus() {
        switch (difficultyLabel.toLowerCase()) {
            case "medium": return 50;
            case "hard":   return 100;
            default:       return 0;   // easy
        }
    }

    private void setStar(int id, boolean earned) {
        ImageView star = findViewById(id);
        star.setColorFilter(earned ? 0xFFFFC107 : 0xFFD5DDDF);
    }

    private int cardHeightPx() {
        int dp;
        if (columns <= 2) dp = 150;
        else if (columns == 3) dp = 118;
        else dp = 96;
        return dp(dp);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Close out a pause window: fold the backgrounded span into the total
        // so it's excluded from completion time.
        if (pauseMarkElapsed != 0L) {
            pausedAccumMs += SystemClock.elapsedRealtime() - pauseMarkElapsed;
            pauseMarkElapsed = 0L;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Mark the start of a background span (guard against double-mark).
        if (pauseMarkElapsed == 0L) pauseMarkElapsed = SystemClock.elapsedRealtime();
        for (Card c : cards) {
            if (c.player != null) c.player.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (prefetchPool != null) prefetchPool.shutdownNow();
        handler.removeCallbacksAndMessages(null);
        releaseAllPlayers();
    }
}