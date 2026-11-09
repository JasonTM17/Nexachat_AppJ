package com.nexachat.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
        HttpServletRequest wrapped = new jakarta.servlet.http.HttpServletRequestWrapper(inner) {
            @Override
            public String getRemoteAddr() {
                return "198.51.100.99";
            }
        };

        assertThat(resolver.rateLimitClientIp(wrapped)).isEqualTo("203.0.113.10");
    }

    @Test
    void honorsRewrittenAddressWhenPeerIsATrustedProxy() {
        ClientIpResolver resolver = resolverWith("127.0.0.0/8");
        MockHttpServletRequest inner = new MockHttpServletRequest();
        inner.setRemoteAddr("127.0.0.1");
        HttpServletRequest wrapped = new jakarta.servlet.http.HttpServletRequestWrapper(inner) {
            @Override
            public String getRemoteAddr() {
                return "198.51.100.99";
            }
        };

        assertThat(resolver.rateLimitClientIp(wrapped)).isEqualTo("198.51.100.99");
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

    private static ClientIpResolver resolverWith(String... cidrs) {
        return new ClientIpResolver(new SecurityProperties(List.of(), List.of(), List.of(cidrs)));
    }
}
