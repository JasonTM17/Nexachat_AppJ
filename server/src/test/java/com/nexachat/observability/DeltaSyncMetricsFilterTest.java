package com.nexachat.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaSyncMetricsFilterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObservabilityMetrics metrics = new ObservabilityMetrics(registry);
    private final DeltaSyncMetricsFilter filter = new DeltaSyncMetricsFilter(metrics);

    @Test
    void recordsDurationAndResponseSizeForASuccessfulSync() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(200);
            httpResponse.getWriter().write("hello world");
        });

        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_DURATION)
                .tag("outcome", "SUCCESS").timer()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_BYTES)
                .tag("size_bucket", "<1KB").summary()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_BYTES)
                .tag("size_bucket", "<1KB").summary().count()).isEqualTo(1);
    }

    @Test
    void recordsErrorOutcomeForAServerError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(500);
            httpResponse.getWriter().write("boom");
        });

        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_DURATION)
                .tag("outcome", "ERROR").timer()).isNotNull();
        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_DURATION)
                .tag("outcome", "ERROR").timer().count()).isEqualTo(1);
    }

    @Test
    void countsLargeResponsesIntoBoundedBuckets() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) res;
            httpResponse.setStatus(200);
            char[] chunk = new char[2 * 1024 * 1024];
            java.util.Arrays.fill(chunk, 'x');
            httpResponse.getWriter().write(new String(chunk));
        });

        assertThat(registry.find(ObservabilityMetrics.DELTA_SYNC_BYTES)
                .tag("size_bucket", ">1MB").summary()).isNotNull();
    }

    @Test
    void sizeBucketBoundariesAreStable() {
        assertThat(ObservabilityMetrics.sizeBucket(0)).isEqualTo("<1KB");
        assertThat(ObservabilityMetrics.sizeBucket(1023)).isEqualTo("<1KB");
        assertThat(ObservabilityMetrics.sizeBucket(1024)).isEqualTo("1KB-10KB");
        assertThat(ObservabilityMetrics.sizeBucket(10 * 1024)).isEqualTo("10KB-100KB");
        assertThat(ObservabilityMetrics.sizeBucket(100 * 1024)).isEqualTo("100KB-1MB");
        assertThat(ObservabilityMetrics.sizeBucket(1024 * 1024)).isEqualTo(">1MB");
    }
}
