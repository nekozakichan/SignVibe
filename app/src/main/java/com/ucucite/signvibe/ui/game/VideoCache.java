package com.ucucite.signvibe.ui.game;

import android.content.Context;

import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/** One shared on-disk cache for sign videos. Repeat flips load from disk, not network. */
public final class VideoCache {

    private static SimpleCache instance;
    private static final long MAX_BYTES = 80L * 1024 * 1024; // 80 MB is plenty for short clips

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