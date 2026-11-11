package com.nexachat.core.media;

import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.File;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.Cache;
import okhttp3.OkHttpClient;

/** Wipes downloaded attachment/media disk state on permanent account removal. */
@Singleton
public final class MediaCacheCleaner {

    private static final String MEDIA_UPLOADS_DIR = "media-uploads";

    private final Context context;
    private final OkHttpClient okHttpClient;

    @Inject
    public MediaCacheCleaner(
            @ApplicationContext @NonNull Context context,
            @NonNull OkHttpClient okHttpClient) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.okHttpClient = Objects.requireNonNull(okHttpClient, "okHttpClient");
    }

    @NonNull
    public Completable clearForAccountDeletion() {
        return Completable.fromAction(this::clearNow).subscribeOn(Schedulers.io());
    }

    private void clearNow() {
        Cache cache = okHttpClient.cache();
        if (cache != null) {
            try {
                cache.evictAll();
            } catch (Exception ignored) {
                // Best-effort after the server already committed deletion.
            }
        }
        File cacheDir = context.getCacheDir();
        deleteTree(MediaCacheConfig.glideDirectory(cacheDir));
        deleteTree(MediaCacheConfig.okHttpDirectory(cacheDir));
        deleteTree(new File(cacheDir, MEDIA_UPLOADS_DIR));
        try {
            Glide.get(context).clearDiskCache();
        } catch (Exception ignored) {
            // Glide may be uninitialized in tests.
        }
    }

    private static void deleteTree(@NonNull File root) {
        if (!root.exists()) {
            return;
        }
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteTree(child);
                } else if (!child.delete()) {
                    child.deleteOnExit();
                }
            }
        }
        if (!root.delete()) {
            root.deleteOnExit();
        }
    }
}
