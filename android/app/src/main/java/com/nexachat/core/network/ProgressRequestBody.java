package com.nexachat.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.IOException;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.Okio;
import okio.Sink;

/**
 * {@link RequestBody} decorator reporting sink progress while OkHttp writes the multipart body.
 *
 * <p>Progress arrives on the calling thread inside {@link #writeTo}; the listener must therefore
 * be non-suspending (e.g. trySend into a channel upstream). Fractions are strictly non-decreasing —
 * bytes only ever accumulate. {@link RequestBody#isOneShot()} is forwarded to the delegate.
 */
public final class ProgressRequestBody extends RequestBody {

    /** Non-blocking progress callback while bytes are written to the wire. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesWritten, long contentLength);
    }

    private final RequestBody delegate;
    private final ProgressListener onProgress;

    public ProgressRequestBody(@NonNull RequestBody delegate, @NonNull ProgressListener onProgress) {
        this.delegate = delegate;
        this.onProgress = onProgress;
    }

    @Override
    @Nullable
    public okhttp3.MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public long contentLength() throws IOException {
        return delegate.contentLength();
    }

    @Override
    public boolean isOneShot() {
        return delegate.isOneShot();
    }

    @Override
    public void writeTo(@NonNull BufferedSink sink) throws IOException {
        long total;
        try {
            total = contentLength();
        } catch (Exception ignored) {
            total = -1L;
        }
        final long totalLen = total;
        Sink countingSink =
                new ForwardingSink(sink) {
                    long bytesWritten = 0L;

                    @Override
                    public void write(@NonNull Buffer source, long byteCount) throws IOException {
                        super.write(source, byteCount);
                        bytesWritten += byteCount;
                        onProgress.onProgress(bytesWritten, totalLen);
                    }
                };
        BufferedSink buffered = Okio.buffer(countingSink);
        delegate.writeTo(buffered);
        buffered.flush();
    }
}
