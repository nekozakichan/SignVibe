package com.ucucite.signvibe.ui.game;

import android.content.Context;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/** One shared on-disk cache for sign videos. Repeat flips load from disk, not network. */
public final class VideoCache {

    private static SimpleCache instance;
    // Large enough to hold every lesson clip so nothing gets evicted — this is
    // what lets all lessons play offline once they've been prefetched online.
    private static final long MAX_BYTES = 1024L * 1024 * 1024; // 1 GB

    private VideoCache() {}

    public static synchronized SimpleCache get(Context context) {
        if (instance == null) {
            File dir = new File(context.getCacheDir(), "sign_video_cache");
            instance = new SimpleCache(
                    dir,
                    new LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                    new StandaloneDatabaseProvider(context.getApplicationContext())
            );
        }
        return instance;
    }
}