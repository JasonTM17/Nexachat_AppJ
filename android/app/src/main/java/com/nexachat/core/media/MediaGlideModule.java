package com.nexachat.core.media;

import android.content.Context;
import androidx.annotation.NonNull;
import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.module.AppGlideModule;
import dagger.hilt.android.EntryPointAccessors;
import java.io.InputStream;

/** Configures the bounded internal Glide cache and authenticated remote URL loader. */
@GlideModule
public final class MediaGlideModule extends AppGlideModule {

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setDiskCache(new InternalCacheDiskCacheFactory(
                context,
                MediaCacheConfig.GLIDE_DIRECTORY_NAME,
                MediaCacheConfig.GLIDE_DISK_CACHE_BYTES));
    }

    @Override
    public void registerComponents(
            @NonNull Context context,
            @NonNull Glide glide,
            @NonNull Registry registry) {
        MediaOkHttpEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                context.getApplicationContext(), MediaOkHttpEntryPoint.class);
        registry.prepend(
                String.class,
                InputStream.class,
                new AuthenticatedOkHttpModelLoader.Factory(entryPoint.okHttpClient()));
    }

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }
}
