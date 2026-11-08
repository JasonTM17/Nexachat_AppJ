package com.nexachat.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measures delta-sync request duration and response size. Only sizes and the
 * outcome are recorded; request and response bodies never leave the method.
 */
public class DeltaSyncMetricsFilter extends OncePerRequestFilter {

    private final ObservabilityMetrics metrics;

    public DeltaSyncMetricsFilter(ObservabilityMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        CountingResponseWrapper wrapper = new CountingResponseWrapper(response);
        try {
            filterChain.doFilter(request, wrapper);
        } finally {
            long durationMillis = (System.nanoTime() - start) / 1_000_000L;
            int status = wrapper.getStatus();
            metrics.recordDeltaSync(durationMillis, wrapper.byteCount(),
                    status < 500 && status > 0);
        }
    }

    /** Counts bytes written without buffering the response. */
    private static final class CountingResponseWrapper extends HttpServletResponseWrapper {

        private final LongAdder count = new LongAdder();
        private CountingOutputStream outputStream;
        private PrintWriter writer;

        private CountingResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        long byteCount() {
            return count.sum();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (outputStream == null) {
                outputStream = new CountingOutputStream(super.getOutputStream(), count);
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null || encoding.isBlank()
                        ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset));
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (outputStream != null) {
                outputStream.flush();
            }
            super.flushBuffer();
        }
    }

    private static final class CountingOutputStream extends ServletOutputStream {

        private final OutputStream delegate;
        private final LongAdder count;

        private CountingOutputStream(OutputStream delegate, LongAdder count) {
            this.delegate = delegate;
            this.count = count;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            // Not used by the synchronous sync path.
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count.increment();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count.add(len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
