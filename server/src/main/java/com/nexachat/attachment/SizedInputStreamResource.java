package com.nexachat.attachment;

import org.springframework.core.io.InputStreamResource;

import java.io.InputStream;

/**
 * A stream-backed resource with a known length, so Spring's
 * {@code ResourceHttpMessageConverter} can set {@code Content-Length}
 * without buffering the file.
 */
public final class SizedInputStreamResource extends InputStreamResource {

    private final long length;

    public SizedInputStreamResource(InputStream inputStream, long length) {
        super(inputStream);
        this.length = length;
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public String getFilename() {
        return null;
    }
}
