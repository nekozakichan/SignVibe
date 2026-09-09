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
 * Warms the shared video cache with EVERY published lesson clip so lessons can
 * be watched offline later.
 *
 * <p>Safe to call on every app launch: it only downloads bytes that aren't
 * already cached, and it silently does nothing when the device is offline (the
 * Firestore query simply fails and is ignored). Once the app has been online
 * once with enough time to finish, all lessons play without internet.</p>
 */
public final class LessonPrefetcher {

    private static final String TAG = "LessonPrefetcher";

    // Guards against launching overlapping sweeps within one app session.
    private static volatile boolean running = false;

    private LessonPrefetcher() {}

    /** Downloads all published lesson videos into the cache, in the background. */
    @OptIn(markerClass = UnstableApi.class)
    public static void prefetchAll(Context context) {
        if (running) return;
        running = true;
        final Context app = context.getApplicationContext();

        FirebaseFirestore.getInstance()
                .collection("lessons")
                .whereEqualTo("status", "published")
                .get()
                .addOnSuccessListener(snap -> {
                    final ExecutorService pool = Executors.newFixedThreadPool(3);
                    final CacheDataSource.Factory factory = new CacheDataSource.Factory()
                            .setCache(VideoCache.get(app))
                            .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory())
                            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);

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
                                // Offline / cancelled — the player still fetches on demand later.
                            }
                        });
                    }
                    Log.d(TAG, "Prefetching " + queued + " lesson videos for offline use.");
                    pool.shutdown();     // let queued tasks finish, then stop accepting new ones
                    running = false;
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Lesson prefetch skipped (offline or query failed).", e);
                    running = false;
                });
    }
}
