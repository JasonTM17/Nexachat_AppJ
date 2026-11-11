package com.nexachat.core.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

public final class MediaCacheCleanerTest {

    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void clearForAccountDeletionRemovesMediaDirectories() throws Exception {
        File cacheDir = temp.newFolder("cache");
        File glideDir = MediaCacheConfig.glideDirectory(cacheDir);
        File okhttpDir = MediaCacheConfig.okHttpDirectory(cacheDir);
        File uploadsDir = new File(cacheDir, "media-uploads");
        assertTrue(glideDir.mkdirs());
        assertTrue(okhttpDir.mkdirs());
        assertTrue(uploadsDir.mkdirs());
        File marker = new File(uploadsDir, "blob.bin");
        assertTrue(marker.createNewFile());

        Context context = Mockito.mock(Context.class);
        Mockito.when(context.getCacheDir()).thenReturn(cacheDir);
        Mockito.when(context.getApplicationContext()).thenReturn(context);

        Cache cache = new Cache(okhttpDir, 1024);
        OkHttpClient client = new OkHttpClient.Builder().cache(cache).build();
        new MediaCacheCleaner(context, client).clearForAccountDeletion().blockingAwait();

        assertFalse(marker.exists());
        assertFalse(glideDir.exists());
        assertFalse(uploadsDir.exists());
    }
}
