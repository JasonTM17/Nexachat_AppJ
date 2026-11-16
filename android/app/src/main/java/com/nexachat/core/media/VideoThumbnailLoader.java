package com.nexachat.core.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.AppConfig;
import dagger.hilt.android.EntryPointAccessors;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Shows a first-frame preview for a video message.
 *
 * <p>Remote videos are read through the authenticated client with HTTP range
 * requests, so only the container header and one key frame are downloaded
 * instead of the whole file. Frames are kept in a small in-memory cache keyed
 * by URL. A failure leaves the caller's placeholder in place.
 */
public final class VideoThumbnailLoader {

    private static final int MAX_CACHED_FRAMES = 24;
    private static final int MAX_FRAME_EDGE_PX = 640;
    private static final long FRAME_TIME_US = 500_000L;

    private static final BoundedLruCache<String, Bitmap> CACHE =
            new BoundedLruCache<>(MAX_CACHED_FRAMES);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "video-thumbnail");
        thread.setDaemon(true);
        return thread;
    });

    private VideoThumbnailLoader() {}

    /**
     * Loads the preview for {@code url} into {@code target}. The caller must have
     * tagged {@code target} with the same {@code url}; a recycled row whose tag
     * changed meanwhile is left untouched.
     */
    public static void load(@NonNull ImageView target, @NonNull String url) {
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        Context context = target.getContext().getApplicationContext();
        EXECUTOR.execute(() -> {
            Bitmap frame = extract(context, url);
            if (frame == null) {
                return;
            }
            CACHE.put(url, frame);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (url.equals(target.getTag())) {
                    target.setImageBitmap(frame);
                }
            });
        });
    }

    @Nullable
    private static Bitmap extract(Context context, String url) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (url.startsWith("content:") || url.startsWith("file:")) {
                retriever.setDataSource(context, Uri.parse(url));
            } else {
                if (!isApiOrigin(url)) {
                    // The authenticated client attaches the bearer token to every
                    // request, so it must never be pointed at another host.
                    return null;
                }
                OkHttpClient client = EntryPointAccessors
                        .fromApplication(context, MediaOkHttpEntryPoint.class)
                        .okHttpClient();
                retriever.setDataSource(new RangeDataSource(client, url));
            }
            Bitmap frame = retriever.getFrameAtTime(
                    FRAME_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return frame == null ? null : downscale(frame);
        } catch (RuntimeException ex) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException ignored) {
                // Nothing left to release.
            }
        }
    }

    /** True only for URLs on the API's scheme, host and port. */
    static boolean isApiOrigin(String url) {
        HttpUrl target = HttpUrl.parse(url);
        HttpUrl api = HttpUrl.parse(AppConfig.API_BASE_URL);
        return isSameOrigin(target, api);
    }

    static boolean isSameOrigin(@Nullable HttpUrl target, @Nullable HttpUrl api) {
        return target != null && api != null
                && target.scheme().equals(api.scheme())
                && target.host().equals(api.host())
                && target.port() == api.port();
    }

    private static Bitmap downscale(Bitmap source) {
        int longest = Math.max(source.getWidth(), source.getHeight());
        if (longest <= MAX_FRAME_EDGE_PX) {
            return source;
        }
        float scale = (float) MAX_FRAME_EDGE_PX / longest;
        Bitmap scaled = Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * scale)),
                Math.max(1, Math.round(source.getHeight() * scale)), true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }

    /** Random-access reader over an authenticated URL using {@code Range} requests. */
    private static final class RangeDataSource extends MediaDataSource {
        private final OkHttpClient client;
        private final String url;
        private long size = -2L;

        RangeDataSource(OkHttpClient client, String url) {
            this.client = client;
            this.url = url;
        }

        @Override
        public synchronized long getSize() throws IOException {
            if (size != -2L) {
                return size;
            }
            Request request = new Request.Builder().url(url).head().build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }
                String length = response.header("Content-Length");
                size = length == null ? -1L : Long.parseLong(length);
            } catch (NumberFormatException ex) {
                size = -1L;
            }
            return size;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long total = getSize();
            if (total >= 0 && position >= total) {
                return -1;
            }
            long last = position + length - 1;
            if (total >= 0) {
                last = Math.min(last, total - 1);
            }
            Request request = new Request.Builder()
                    .url(url)
                    .header("Range", "bytes=" + position + "-" + last)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (response.code() != 206 || body == null) {
                    // A server that ignores Range would stream the whole file from byte 0.
                    throw new IOException("Range not honoured: HTTP " + response.code());
                }
                java.io.InputStream stream = body.byteStream();
                int read = 0;
                while (read < length) {
                    int count = stream.read(buffer, offset + read, length - read);
                    if (count < 0) {
                        break;
                    }
                    read += count;
                }
                return read == 0 ? -1 : read;
            }
        }

        @Override
        public void close() {
            // Requests are closed per read.
        }
    }
}
