package com.nexachat.feature.feed;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import com.nexachat.core.common.AppConfig;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Authenticated, same-origin Media3 {@link DataSource} backed by the shared {@link OkHttpClient}.
 * Supports HTTP {@code Range} requests so {@code /api/v1/feed/videos/{id}} streams via 206.
 */
@OptIn(markerClass = UnstableApi.class)
public final class OkHttpMediaDataSource extends BaseDataSource {

    public static final class Factory implements DataSource.Factory {
        private final OkHttpClient client;
        private final String baseUrl;

        public Factory(@NonNull OkHttpClient client) {
            this(client, AppConfig.API_BASE_URL);
        }

        @VisibleForTesting
        Factory(@NonNull OkHttpClient client, @NonNull String baseUrl) {
            this.client = client;
            this.baseUrl = baseUrl;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new OkHttpMediaDataSource(client, baseUrl);
        }
    }

    private final OkHttpClient client;
    private final String baseUrl;
    @Nullable private DataSpec dataSpec;
    @Nullable private volatile Call activeCall;
    @Nullable private Response response;
    @Nullable private InputStream stream;
    private boolean opened;
    private long bytesRemaining;

    private OkHttpMediaDataSource(@NonNull OkHttpClient client, @NonNull String baseUrl) {
        super(true);
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        transferInitializing(dataSpec);

        String urlString = dataSpec.uri.toString();
        HttpUrl target = HttpUrl.parse(urlString);
        HttpUrl base = HttpUrl.parse(baseUrl);
        if (target == null || base == null || !AppConfig.isSameOrigin(target, base)) {
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED);
        }

        Request.Builder builder = new Request.Builder().url(target);
        if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET) {
            String range = dataSpec.length != C.LENGTH_UNSET
                    ? "bytes=" + dataSpec.position + "-" + (dataSpec.position + dataSpec.length - 1L)
                    : "bytes=" + dataSpec.position + "-";
            builder.header("Range", range);
        }

        Call call = client.newCall(builder.build());
        this.activeCall = call;
        Response executed;
        try {
            executed = call.execute();
        } finally {
            if (this.activeCall == call) {
                this.activeCall = null;
            }
        }
        this.response = executed;
        int code = executed.code();
        if (code == 416) {
            closeResources();
            throw new DataSourceException(
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
        }
        if (!executed.isSuccessful()) {
            closeResources();
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS);
        }

        ResponseBody body = executed.body();
        if (body == null) {
            closeResources();
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        this.stream = body.byteStream();
        long contentLength = body.contentLength();

        long bytesToSkip = (code == 200) ? dataSpec.position : 0L;
        if (bytesToSkip > 0L && contentLength >= 0L && contentLength < bytesToSkip) {
            closeResources();
            throw new DataSourceException(
                    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
        }
        skipBytesFully(this.stream, bytesToSkip);

        if (code == 200) {
            if (contentLength >= 0L) {
                long available = contentLength - bytesToSkip;
                this.bytesRemaining = dataSpec.length != C.LENGTH_UNSET
                        ? Math.min(dataSpec.length, available)
                        : available;
            } else {
                this.bytesRemaining = dataSpec.length != C.LENGTH_UNSET
                        ? dataSpec.length
                        : C.LENGTH_UNSET;
            }
        } else {
            if (contentLength >= 0L) {
                this.bytesRemaining = dataSpec.length != C.LENGTH_UNSET
                        ? Math.min(dataSpec.length, contentLength)
                        : contentLength;
            } else {
                this.bytesRemaining = dataSpec.length != C.LENGTH_UNSET
                        ? dataSpec.length
                        : C.LENGTH_UNSET;
            }
        }
        this.opened = true;
        transferStarted(dataSpec);
        return this.bytesRemaining;
    }

    private void skipBytesFully(@NonNull InputStream input, long bytesToSkip) throws IOException {
        if (bytesToSkip <= 0L) {
            return;
        }
        byte[] discardBuffer = new byte[4096];
        long remaining = bytesToSkip;
        while (remaining > 0L) {
            int toRead = (int) Math.min(discardBuffer.length, remaining);
            int read = input.read(discardBuffer, 0, toRead);
            if (read == -1) {
                closeResources();
                throw new DataSourceException(
                        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            }
            remaining -= read;
        }
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT;
        }
        InputStream current = stream;
        if (current == null) {
            throw new DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
        }
        int toRead = bytesRemaining == C.LENGTH_UNSET
                ? length
                : (int) Math.min(length, bytesRemaining);
        int read = current.read(buffer, offset, toRead);
        if (read == -1) {
            if (bytesRemaining != C.LENGTH_UNSET && bytesRemaining > 0L) {
                throw new DataSourceException(
                        new EOFException("Unexpected end of stream with "
                                + bytesRemaining + " bytes remaining"),
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
            }
            return C.RESULT_END_OF_INPUT;
        }
        if (bytesRemaining != C.LENGTH_UNSET) {
            bytesRemaining -= read;
        }
        bytesTransferred(read);
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return dataSpec == null ? null : dataSpec.uri;
    }

    @Override
    public void close() {
        dataSpec = null;
        closeResources();
        if (opened) {
            opened = false;
            transferEnded();
        }
    }

    private void closeResources() {
        Call call = activeCall;
        if (call != null) {
            call.cancel();
            activeCall = null;
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            stream = null;
        }
        if (response != null) {
            response.close();
            response = null;
        }
    }
}
