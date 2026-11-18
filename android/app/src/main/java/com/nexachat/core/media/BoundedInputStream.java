package com.nexachat.core.media;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Stream that aborts with an {@link IOException} once more than {@code maxBytes}
 * bytes have been consumed, so a hostile or compromised server cannot push an
 * unbounded body through an inline media fetch. A body of exactly
 * {@code maxBytes} bytes reads through to EOF normally.
 */
final class BoundedInputStream extends FilterInputStream {

    private final long maxBytes;
    private long total;

    BoundedInputStream(InputStream in, long maxBytes) {
        super(in);
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0 && ++total > maxBytes) {
            throw oversize();
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count > 0) {
            total += count;
            if (total > maxBytes) {
                throw oversize();
            }
        }
        return count;
    }

    @Override
    public long skip(long count) throws IOException {
        long skipped = super.skip(count);
        total += skipped;
        if (total > maxBytes) {
            throw oversize();
        }
        return skipped;
    }

    private IOException oversize() {
        return new IOException("media fetch exceeded " + maxBytes + " bytes");
    }
}
