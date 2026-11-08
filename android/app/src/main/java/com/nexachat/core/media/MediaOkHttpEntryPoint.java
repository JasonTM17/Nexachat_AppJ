package com.nexachat.core.media;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;

/** Exposes the authenticated singleton client to Glide's generated application module. */
@EntryPoint
@InstallIn(SingletonComponent.class)
public interface MediaOkHttpEntryPoint {
    OkHttpClient okHttpClient();
}
