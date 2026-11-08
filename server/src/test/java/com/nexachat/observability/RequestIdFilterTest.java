package com.nexachat.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void acceptsSafeInboundIdEchoesItAndPutsItInMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sync");
        request.addHeader(RequestIdFilter.HEADER, "client-req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, recordingChain(mdcDuringRequest));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-req-123");
        assertThat(mdcDuringRequest).hasValue("client-req-123");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_CORRELATION_KEY)).isNull();
    }

    @Test
    void generatesAnIdWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, recordingChain(mdcDuringRequest));

        String echoed = response.getHeader(RequestIdFilter.HEADER);
        assertThat(echoed).isNotBlank();
        assertThat(UUID.matcher(echoed).matches()).isTrue();
        assertThat(mdcDuringRequest).hasValue(echoed);
    }

    @Test
    void rejectsUnsafeInboundIdAndGeneratesOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestIdFilter.HEADER, "bad\nid-with-spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotEqualTo("bad\nid-with-spaces");
        assertThat(UUID.matcher(response.getHeader(RequestIdFilter.HEADER)).matches()).isTrue();
    }

    @Test
    void truncatesNothingBecauseSafeIdsAreAlreadyBounded() {
        assertThat(filter.resolveRequestId("a".repeat(64))).hasSize(64);
        assertThat(filter.resolveRequestId("a".repeat(65))).isNotEqualTo("a".repeat(65));
    }

    private static FilterChain recordingChain(AtomicReference<String> target) {
        return (request, response) -> target.set(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
