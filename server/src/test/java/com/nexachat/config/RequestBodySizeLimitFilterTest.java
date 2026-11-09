package com.nexachat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.common.error.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodySizeLimitFilterTest {

    private static final int LIMIT = 1024;

    private final RequestBodySizeLimitFilter filter =
            new RequestBodySizeLimitFilter(new ObjectMapper(), LIMIT);

    @Test
    void knownLengthUnderTheLimitReachesTheChain() throws Exception {
        MockHttpServletRequest request = jsonRequest(bytes(100));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void knownLengthOverTheLimitIsRejectedWith413() throws Exception {
        MockHttpServletRequest request = jsonRequest(bytes(LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertPayloadTooLarge(response);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void chunkedBodyUnderTheLimitReachesTheChainWithItsBodyIntact() throws Exception {
        byte[] body = bytes(100);
        MockHttpServletRequest request = chunkedRequest("application/json", body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(chain.getRequest().getContentLengthLong()).isEqualTo(body.length);
    }

    @Test
    void chunkedBodyExactlyAtTheLimitReachesTheChain() throws Exception {
        byte[] body = bytes(LIMIT);
        MockHttpServletRequest request = chunkedRequest("application/json", body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void chunkedBodyOverTheLimitIsRejectedWith413() throws Exception {
        MockHttpServletRequest request = chunkedRequest("application/json", bytes(LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertPayloadTooLarge(response);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void chunkedBodyOverTheLimitWithoutContentTypeIsRejectedWith413() throws Exception {
        MockHttpServletRequest request = chunkedRequest(null, bytes(LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertPayloadTooLarge(response);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void multipartKeepsItsOwnCapAndBypassesTheJsonLimit() throws Exception {
        byte[] body = bytes(LIMIT * 2);
        MockHttpServletRequest request = chunkedRequest("multipart/form-data; boundary=abc", body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    private void assertPayloadTooLarge(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains(ApiErrorCode.PAYLOAD_TOO_LARGE.getCode())
                .contains("Request body exceeds the maximum allowed size");
    }

    private static MockHttpServletRequest jsonRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    private static MockHttpServletRequest chunkedRequest(String contentType, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        if (contentType != null) {
            request.setContentType(contentType);
        }
        request.setContent(body);
        return request;
    }

    private static byte[] bytes(int size) {
        byte[] body = new byte[size];
        for (int index = 0; index < size; index++) {
            body[index] = (byte) ('a' + index % 26);
        }
        return body;
    }
}
