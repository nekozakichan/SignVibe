package com.ucucite.signvibe.data;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.ucucite.signvibe.ui.game.VideoCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Warms the shared video cache with EVERY published lesson AND quiz clip so they
 * load instantly and can be watched offline later.
 *
 * <p>Safe to call on every app launch: it only downloads bytes that aren't
 * already cached, and it silently does nothing when the device is offline (the
 * Firestore query simply fails and is ignored). Once the app has been online
 * once with enough time to finish, lessons and quizzes play without internet.</p>
 */
public final class LessonPrefetcher {

    private static final String TAG = "LessonPrefetcher";

    // Firestore collections whose documents each carry a "video_url" to cache.
    private static final String[] VIDEO_COLLECTIONS = {"lessons", "quiz_questions"};

    // Guards against launching overlapping sweeps within one app session.
    private static volatile boolean running = false;

    private LessonPrefetcher() {}

    /** Downloads all published lesson + quiz videos into the cache, in the background. */
    @OptIn(markerClass = UnstableApi.class)
    public static void prefetchAll(Context context) {
        if (running) return;
        running = true;
        final Context app = context.getApplicationContext();

        final ExecutorService pool = Executors.newFixedThreadPool(3);
        final CacheDataSource.Factory factory = new CacheDataSource.Factory()
                .setCache(VideoCache.get(app))
                .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

        // Track how many collection queries are still in flight, so the pool is
        // shut down (and `running` cleared) only after the last one returns.
        final int[] remaining = { VIDEO_COLLECTIONS.length };

        for (String collection : VIDEO_COLLECTIONS) {
            FirebaseFirestore.getInstance()
                    .collection(collection)
                    .whereEqualTo("status", "published")
                    .get()
                    .addOnSuccessListener(snap -> {
                        int queued = 0;
                        for (QueryDocumentSnapshot doc : snap) {
                            final String url = doc.getString("video_url");
                            if (TextUtils.isEmpty(url)) continue;
                            queued++;
                            pool.execute(() -> {
                                try {
                                    DataSpec spec = new DataSpec(Uri.parse(url));
                                    new CacheWriter(factory.createDataSource(), spec, null, null).cache();
                                } catch (Exception ignored) {
                                    // Offline / cancelled — the player fetches on demand later.
                                }
                            });
                        }
                        Log.d(TAG, "Prefetching " + queued + " videos from " + collection + ".");
                        finishOne(remaining, pool);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Prefetch skipped for " + collection
                                + " (offline or query failed).", e);
                        finishOne(remaining, pool);
                    });
        }
    }

    private static synchronized void finishOne(int[] remaining, ExecutorService pool) {
        remaining[0]--;
        if (remaining[0] <= 0) {
            pool.shutdown();   // let queued downloads finish, then stop accepting new ones
            running = false;
        }
    }
}
