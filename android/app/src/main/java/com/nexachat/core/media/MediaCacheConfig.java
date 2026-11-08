package com.nexachat.core.media;

import androidx.annotation.NonNull;
import java.io.File;
import okhttp3.Cache;
import okhttp3.Request;

/** Internal media cache sizing and path policy. The two caches share one 2 GiB ceiling. */
public final class MediaCacheConfig {

    public static final long MAX_TOTAL_DISK_CACHE_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final long GLIDE_DISK_CACHE_BYTES = MAX_TOTAL_DISK_CACHE_BYTES / 2L;
    public static final long OKHTTP_DISK_CACHE_BYTES =
            MAX_TOTAL_DISK_CACHE_BYTES - GLIDE_DISK_CACHE_BYTES;
    public static final String GLIDE_DIRECTORY_NAME = "media-glide-cache";
    public static final String OKHTTP_DIRECTORY_NAME = "media-okhttp-cache";

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
    public static Cache createOkHttpCache(@NonNull File cacheDirectory) {
        return new Cache(okHttpDirectory(cacheDirectory), OKHTTP_DISK_CACHE_BYTES);
    }

    public static boolean isAttachmentContentGet(@NonNull Request request) {
        if (!"GET".equals(request.method())) {
            return false;
        }
        String[] segments = request.url().encodedPath().split("/");
        return segments.length == 6
                && "api".equals(segments[1])
                && "v1".equals(segments[2])
                && "attachments".equals(segments[3])
                && !segments[4].isEmpty()
                && "content".equals(segments[5]);
    }

    public static boolean isMediaContentType(String contentType) {
        String normalized = contentType == null
                ? ""
                : contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("image/")
                || normalized.startsWith("video/")
                || normalized.startsWith("audio/")
                || "application/pdf".equals(normalized)
                || "application/octet-stream".equals(normalized);
    }
}
