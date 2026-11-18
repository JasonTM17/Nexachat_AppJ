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
        File attachmentsDir = MediaCacheConfig.attachmentDirectory(cacheDir);
        File transientDir = MediaCacheConfig.transientAttachmentDirectory(cacheDir);
        File uploadsDir = new File(cacheDir, "media-uploads");
        assertTrue(glideDir.mkdirs());
        assertTrue(okhttpDir.mkdirs());
        assertTrue(attachmentsDir.mkdirs());
        assertTrue(transientDir.mkdirs());
        assertTrue(uploadsDir.mkdirs());
        File marker = new File(uploadsDir, "blob.bin");
        assertTrue(marker.createNewFile());
        File encrypted = new File(attachmentsDir, "att-1-pic.png");
        assertTrue(encrypted.createNewFile());
        File working = new File(transientDir, "att-1-pic.png");
        assertTrue(working.createNewFile());

        Context context = Mockito.mock(Context.class);
        Mockito.when(context.getCacheDir()).thenReturn(cacheDir);
        Mockito.when(context.getApplicationContext()).thenReturn(context);

        Cache cache = new Cache(okhttpDir, 1024);
        OkHttpClient client = new OkHttpClient.Builder().cache(cache).build();
        new MediaCacheCleaner(context, client).clearForAccountDeletion().blockingAwait();

        assertFalse(marker.exists());
        assertFalse(encrypted.exists());
        assertFalse(working.exists());
        assertFalse(glideDir.exists());
        assertFalse(uploadsDir.exists());
        assertFalse(attachmentsDir.exists());
        assertFalse(transientDir.exists());
    }

    @Test
    public void clearTransientAttachmentCopiesWipesWorkingCopiesAndLegacyOkHttpCache() throws Exception {
        File cacheDir = temp.newFolder("cache2");
        File attachmentsDir = MediaCacheConfig.attachmentDirectory(cacheDir);
        File transientDir = MediaCacheConfig.transientAttachmentDirectory(cacheDir);
        File legacyOkhttpDir = MediaCacheConfig.okHttpDirectory(cacheDir);
        assertTrue(attachmentsDir.mkdirs());
        assertTrue(transientDir.mkdirs());
        assertTrue(legacyOkhttpDir.mkdirs());
        File encrypted = new File(attachmentsDir, "att-1-pic.png");
        assertTrue(encrypted.createNewFile());
        File working = new File(transientDir, "att-1-pic.png");
        assertTrue(working.createNewFile());
        File legacy = new File(legacyOkhttpDir, "legacy-body.bin");
        assertTrue(legacy.createNewFile());

        Context context = Mockito.mock(Context.class);
        Mockito.when(context.getCacheDir()).thenReturn(cacheDir);
        Mockito.when(context.getApplicationContext()).thenReturn(context);

        OkHttpClient client = new OkHttpClient.Builder().build();
        new MediaCacheCleaner(context, client).clearTransientAttachmentCopies().blockingAwait();

        assertFalse(working.exists());
        assertFalse(transientDir.exists());
        assertFalse("legacy plaintext OkHttp cache must be wiped on app start", legacyOkhttpDir.exists());
        assertTrue("encrypted store must survive the transient wipe", encrypted.exists());
    }
}
