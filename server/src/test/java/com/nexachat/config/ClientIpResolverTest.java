package com.nexachat.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Enumeration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void ignoresSpoofedForwardedAddressWhenPeerIsNotTrusted() {
        ClientIpResolver resolver = resolverWith();
        MockHttpServletRequest inner = new MockHttpServletRequest();
        inner.setRemoteAddr("203.0.113.10");
        inner.addHeader("X-Forwarded-For", "198.51.100.99");
        HttpServletRequest wrapped = new jakarta.servlet.http.HttpServletRequestWrapper(inner) {
            @Override
            public String getRemoteAddr() {
                return "198.51.100.99";
            }
        };

        assertThat(resolver.rateLimitClientIp(wrapped)).isEqualTo("203.0.113.10");
    }

    @Test
    void trustedProxyWithoutForwardedHeaderUsesThePeer() {
        ClientIpResolver resolver = resolverWith("127.0.0.0/8");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void spoofedLeftMostForwardedForCannotChooseTheBucket() {
        ClientIpResolver resolver = resolverWith("10.0.0.0/8");
        MockHttpServletRequest request = peer("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.7");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void twoRealClientsBehindTrustedProxyGetDifferentBuckets() {
        ClientIpResolver resolver = resolverUsingHeader("CF-Connecting-IP", "10.0.0.0/8");

        assertThat(resolver.rateLimitClientIp(requestFrom("10.0.0.5", "CF-Connecting-IP", "203.0.113.7")))
                .isEqualTo("203.0.113.7");
        assertThat(resolver.rateLimitClientIp(requestFrom("10.0.0.5", "CF-Connecting-IP", "203.0.113.8")))
                .isEqualTo("203.0.113.8");
    }

    @Test
    void forwardedForOnlyClientsBehindTrustedProxyGetDifferentBuckets() {
        ClientIpResolver resolver = resolverWith("10.0.0.0/8");

        assertThat(resolver.rateLimitClientIp(requestFrom("10.0.0.5", "X-Forwarded-For", "203.0.113.7")))
                .isEqualTo("203.0.113.7");
        assertThat(resolver.rateLimitClientIp(requestFrom("10.0.0.5", "X-Forwarded-For", "203.0.113.8")))
                .isEqualTo("203.0.113.8");
    }

    @Test
    void untrustedDirectPeerIgnoresForwardedHeaders() {
        ClientIpResolver resolver = resolverWith("10.0.0.0/8");
        MockHttpServletRequest request = peer("198.51.100.5");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("CF-Connecting-IP", "203.0.113.8");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("198.51.100.5");
    }

    @Test
    void skipsTrustedProxyHopsWhenWalkingForwardedFor() {
        ClientIpResolver resolver = resolverWith("10.0.0.0/8", "192.0.2.0/24");
        MockHttpServletRequest request = peer("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 192.0.2.10");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void parsesIpv6ClientAddress() {
        ClientIpResolver resolver = resolverUsingHeader("CF-Connecting-IP", "10.0.0.0/8");

        assertThat(resolver.rateLimitClientIp(requestFrom("10.0.0.5", "CF-Connecting-IP", "2001:db8::1")))
                .isEqualTo("2001:db8:0:0:0:0:0:1");
        assertThat(resolver.rateLimitClientIp(requestFrom("10.0.0.5", "CF-Connecting-IP", "[2001:db8::2]")))
                .isEqualTo("2001:db8:0:0:0:0:0:2");
    }

    @Test
    void ipv6TrustedProxyPeerCanSupplyTheClientAddress() {
        ClientIpResolver resolver = resolverWith("::1/128");
        MockHttpServletRequest request = peer("0:0:0:0:0:0:0:1");
        request.addHeader("X-Forwarded-For", "2001:db8::7");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("2001:db8:0:0:0:0:0:7");
    }

    @Test
    void malformedForwardedForFallsBackToPeer() {
        ClientIpResolver resolver = resolverWith("10.0.0.0/8");
        MockHttpServletRequest request = peer("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.7, not-an-address");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void hostnameInForwardedForIsNotResolved() {
        ClientIpResolver resolver = resolverWith("10.0.0.0/8");
        MockHttpServletRequest request = peer("10.0.0.5");
        request.addHeader("X-Forwarded-For", "localhost");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void multiValueSingleAddressHeaderFallsBackToPeer() {
        ClientIpResolver resolver = resolverUsingHeader("CF-Connecting-IP", "10.0.0.0/8");
        MockHttpServletRequest request = peer("10.0.0.5");
        request.addHeader("CF-Connecting-IP", "203.0.113.7, 203.0.113.8");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("10.0.0.5");
    }

    @Test
    void readsForwardedHeaderBehindTheForwardedHeaderWrapper() {
        ClientIpResolver resolver = resolverWith("10.0.0.0/8");
        MockHttpServletRequest inner = new MockHttpServletRequest();
        inner.setRemoteAddr("10.0.0.5");
        inner.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.7");
        HttpServletRequest wrapped = new HiddenForwardedHeaders(inner, "198.51.100.99");
        // The capture filter stores the socket peer on the outermost request.
        wrapped.setAttribute(ClientIpResolver.SOCKET_REMOTE_ADDR_ATTRIBUTE, "10.0.0.5");

        assertThat(resolver.rateLimitClientIp(wrapped)).isEqualTo("203.0.113.7");
    }

    @Test
    void usesCapturedSocketPeerWhenPresent() {
        ClientIpResolver resolver = resolverWith();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ClientIpResolver.SOCKET_REMOTE_ADDR_ATTRIBUTE, "203.0.113.10");
        request.setRemoteAddr("198.51.100.99");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void fallsBackToRemoteAddrWhenRequestIsNotWrapped() {
        ClientIpResolver resolver = resolverWith();
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void blankConfiguredHeaderNameUsesForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver(
                new SecurityProperties(List.of(), List.of(), List.of("10.0.0.0/8"), " "));
        MockHttpServletRequest request = peer("10.0.0.5");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertThat(resolver.rateLimitClientIp(request)).isEqualTo("203.0.113.7");
    }

    private static MockHttpServletRequest peer(String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(address);
        return request;
    }

    private static MockHttpServletRequest requestFrom(String peer, String header, String value) {
        MockHttpServletRequest request = peer(peer);
        request.addHeader(header, value);
        return request;
    }

    private static ClientIpResolver resolverWith(String... cidrs) {
        return resolverUsingHeader(ClientIpResolver.DEFAULT_CLIENT_IP_HEADER, cidrs);
    }

    private static ClientIpResolver resolverUsingHeader(String header, String... cidrs) {
        return new ClientIpResolver(new SecurityProperties(List.of(), List.of(), List.of(cidrs), header));
    }

    /** Mirrors Spring's ForwardedHeaderFilter: it hides forwarded headers and rewrites the peer. */
    private static final class HiddenForwardedHeaders extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final String rewrittenPeer;

        private HiddenForwardedHeaders(HttpServletRequest request, String rewrittenPeer) {
            super(request);
            this.rewrittenPeer = rewrittenPeer;
        }

        @Override
        public String getHeader(String name) {
            return "X-Forwarded-For".equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return "X-Forwarded-For".equalsIgnoreCase(name)
                    ? java.util.Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }

        @Override
        public String getRemoteAddr() {
            return rewrittenPeer;
        }
    }
}
