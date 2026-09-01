package com.ucucite.signvibe.ui.learn;

import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.ucucite.signvibe.R;
import com.ucucite.signvibe.SignVibeToast;
import com.ucucite.signvibe.data.ProgressRepository;

import java.util.Locale;

public class LessonDetailActivity extends AppCompatActivity {

    public static final String EXTRA_MODULE_ID = "extra_module_id";
    public static final String EXTRA_LESSON_ID = "extra_lesson_id";
    public static final String EXTRA_LESSON_TITLE = "extra_lesson_title";
    public static final String EXTRA_LESSON_DESCRIPTION = "extra_lesson_description";
    public static final String EXTRA_LESSON_VIDEO_URL = "extra_lesson_video_url";
    public static final String EXTRA_LESSON_SHORT_LABEL = "extra_lesson_short_label";

    private ExoPlayer player;
    private PlayerView playerView;
    private ProgressBar videoLoading;
    private LinearLayout videoEmptyState;
    private ImageView imgVideoStateIcon;
    private TextView txtVideoStateMessage;

    private TextToSpeech tts;
    private boolean isTtsReady = false;

    private String moduleId;
    private String lessonId;
    private String lessonTitle;
    private String lessonDescription;
    private String videoUrl;
    private String shortLabel;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lesson_detail);

        moduleId = getIntent().getStringExtra(EXTRA_MODULE_ID);
        lessonId = getIntent().getStringExtra(EXTRA_LESSON_ID);
        lessonTitle = getIntent().getStringExtra(EXTRA_LESSON_TITLE);
        lessonDescription = getIntent().getStringExtra(EXTRA_LESSON_DESCRIPTION);
        videoUrl = getIntent().getStringExtra(EXTRA_LESSON_VIDEO_URL);
        shortLabel = getIntent().getStringExtra(EXTRA_LESSON_SHORT_LABEL);

        bindViews();
        setupTts();
        setupVideo();
        setupClickListeners();
    }

    private void bindViews() {
        ((TextView) findViewById(R.id.txtLessonTitle)).setText(lessonTitle);
        ((TextView) findViewById(R.id.txtLessonDescription)).setText(lessonDescription);
        ((TextView) findViewById(R.id.txtShortLabel)).setText(shortLabel);

        playerView = findViewById(R.id.playerView);
        videoLoading = findViewById(R.id.videoLoading);
        videoEmptyState = findViewById(R.id.videoEmptyState);
        imgVideoStateIcon = findViewById(R.id.imgVideoStateIcon);
        txtVideoStateMessage = findViewById(R.id.txtVideoStateMessage);
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.85f);
                isTtsReady = true;
            }
        });
    }

    @OptIn(markerClass = UnstableApi.class)
    private void setupVideo() {
        if (TextUtils.isEmpty(videoUrl)) {
            showVideoState(R.drawable.ic_videocam_off, getString(R.string.video_not_uploaded));
            return;
        }

        videoLoading.setVisibility(View.VISIBLE);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setPlayWhenReady(true); //auto-starts since there's no play button now

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    videoLoading.setVisibility(View.GONE);
                    playerView.setVisibility(View.VISIBLE);
                    videoEmptyState.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                showVideoState(R.drawable.ic_error_outline, getString(R.string.could_not_load_video));
            }
        });

        player.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)));
        player.prepare();
    }

    private void showVideoState(int iconRes, String message) {
        videoLoading.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        videoEmptyState.setVisibility(View.VISIBLE);
        imgVideoStateIcon.setImageResource(iconRes);
        txtVideoStateMessage.setText(message);
    }

    private void setupClickListeners() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnSpeak).setOnClickListener(v -> speakShortLabel());

        findViewById(R.id.btnRepeat).setOnClickListener(v -> {
            if (player != null) {
                player.seekTo(0);
                player.play();
            }
        });

        findViewById(R.id.btnMarkDone).setOnClickListener(v -> {
            if (moduleId != null && lessonId != null) {
                ProgressRepository.markLessonComplete(moduleId, lessonId);
            }
            SignVibeToast.show(this, "Lesson marked as completed!", Toast.LENGTH_SHORT);
            finish();
        });
    }

    private void speakShortLabel() {
        if (isTtsReady && !TextUtils.isEmpty(shortLabel)) {
            tts.speak(shortLabel, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}