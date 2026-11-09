package com.nexachat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.dto.ErrorResponse;
import com.nexachat.common.error.ApiErrorCode;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Rejects request bodies larger than
 * {@code nexachat.security.max-json-body-bytes} (default 1 MiB) before the
 * dispatcher parses them.
 *
 * <p>Multipart uploads are excluded: they carry their own 25 MB ceiling in
 * {@code spring.servlet.multipart} and are validated again in the storage
 * layer. Bodies with a known {@code Content-Length} are rejected from the
 * header alone. Bodies of unknown length (chunked transfer encoding) are read
 * on the request input stream and counted against the same limit, so a caller
 * cannot bypass the bound by omitting the header; an oversized chunked body is
 * answered with the same {@code 413} error envelope.
 *
 * <p>Runs ahead of the security chain so an oversized payload never reaches
 * authentication, authorization or a message converter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    static final long DEFAULT_MAX_BODY_BYTES = 1024L * 1024L;
    private static final String MULTIPART_PREFIX = "multipart/";
    private static final int READ_BUFFER_BYTES = 8192;

    private final ObjectMapper objectMapper;
    private final long maxBodyBytes;

    public RequestBodySizeLimitFilter(
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${nexachat.security.max-json-body-bytes:1048576}") long maxBodyBytes) {
        this.objectMapper = objectMapper;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    jakarta.servlet.FilterChain filterChain)
            throws jakarta.servlet.ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith(MULTIPART_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBodyBytes) {
            writePayloadTooLarge(response);
            return;
        }
        if (contentLength >= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = readBounded(request, maxBodyBytes);
        if (body == null) {
            writePayloadTooLarge(response);
            return;
        }
        filterChain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void writePayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(
                ApiErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds the maximum allowed size"));
    }

    /**
     * Reads at most {@code limit + 1} bytes from the request stream. Returns
     * {@code null} once the limit is exceeded, so the caller can answer
     * {@code 413} without buffering an unbounded body.
     */
    private static byte[] readBounded(HttpServletRequest request, long limit) throws IOException {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long total = 0;
        try (InputStream input = request.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    return null;
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    /**
     * Re-serves an already counted chunked body so the dispatcher, message
     * converters and any downstream filter see a normal readable request.
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;
        private final ServletInputStream inputStream;
        private BufferedReader reader;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
            this.inputStream = new ServletInputStream() {
                private final ByteArrayInputStream delegate = new ByteArrayInputStream(body);
                private boolean finished;

                @Override
                public boolean isFinished() {
                    return finished;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("async reads are not supported");
                }

                @Override
                public int read() {
                    int value = delegate.read();
                    finished = value == -1;
                    return value;
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    int count = delegate.read(target, offset, length);
                    finished = count == -1;
                    return count;
                }

                @Override
                public void close() throws IOException {
                    delegate.close();
                }
            };
        }

        @Override
        public ServletInputStream getInputStream() {
            return inputStream;
        }

        @Override
        public BufferedReader getReader() {
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(body), charset()));
            }
            return reader;
        }

        @Override
        public int getContentLength() {
            return body.length > Integer.MAX_VALUE ? -1 : body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        private Charset charset() {
            String encoding = getCharacterEncoding();
            if (encoding == null || encoding.isBlank()) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(encoding);
            } catch (RuntimeException exception) {
                return StandardCharsets.UTF_8;
            }
        }
    }
}
