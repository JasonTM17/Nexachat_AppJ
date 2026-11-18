package com.nexachat.core.media;

import androidx.annotation.NonNull;
import java.io.File;

/**
 * Internal media cache path and fetch policy.
 *
 * <p>The OkHttp disk cache is gone on purpose: it persisted decrypted attachment
 * bytes unencrypted (audit F-02), and OkHttp's cache format cannot be encrypted.
 * Every request is forced {@code no-store} by {@link MediaCacheRequestInterceptor},
 * so no HTTP response body is ever written to disk. {@link #OKHTTP_DIRECTORY_NAME}
 * is kept only so app start and account deletion still wipe the directory left
 * behind by pre-hardening installs.
 */
public final class MediaCacheConfig {

    public static final long MAX_TOTAL_DISK_CACHE_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final long GLIDE_DISK_CACHE_BYTES = MAX_TOTAL_DISK_CACHE_BYTES / 2L;
    /**
     * Ceiling for one inline media download (Glide image, video thumbnail range
     * fetch). Mirrors the server-side upload cap ({@code AttachmentRepository
     * .MAX_UPLOAD_BYTES} = 25 MiB): legitimate attachments never exceed it, so a
     * larger body — declared up front or observed mid-stream — is hostile.
     */
    public static final long MAX_MEDIA_FETCH_BYTES = 25L * 1024L * 1024L;
    public static final String GLIDE_DIRECTORY_NAME = "media-glide-cache";
    /** Legacy OkHttp media cache dir; wiped on app start and account deletion, never written. */
    public static final String OKHTTP_DIRECTORY_NAME = "media-okhttp-cache";
    /** Encrypted at-rest store for downloaded attachments (AES/GCM, keystore key). */
    public static final String ATTACHMENTS_DIR_NAME = "attachments";
    /** Transient decrypted working copies handed to viewers; wiped on app start. */
    public static final String ATTACHMENTS_TRANSIENT_DIR_NAME = "attachments-open";

    private MediaCacheConfig() { }

    @NonNull
    public static File glideDirectory(@NonNull File cacheDirectory) {
        return new File(cacheDirectory, GLIDE_DIRECTORY_NAME);
    }

    @NonNull
    public static File okHttpDirectory(@NonNull File cacheDirectory) {
        return new File(cacheDirectory, OKHTTP_DIRECTORY_NAME);
    }

    @NonNull
    public static File attachmentDirectory(@NonNull File cacheDirectory) {
        return new File(cacheDirectory, ATTACHMENTS_DIR_NAME);
    }

    @NonNull
    public static File transientAttachmentDirectory(@NonNull File cacheDirectory) {
        return new File(cacheDirectory, ATTACHMENTS_TRANSIENT_DIR_NAME);
    }
}
