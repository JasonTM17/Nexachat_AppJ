package com.nexachat.auth;

import com.nexachat.auth.google.GoogleSignInProperties;
import com.nexachat.common.error.ApiException;
import com.nexachat.config.ClientIpResolver;
import com.nexachat.testutil.MutableTimeMeter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecoveryEmailRateLimitInterceptorTest {

    @Test
    @SuppressWarnings("unchecked")
    void authenticatedEmailOtpRoutesHaveIndependentPerIpBuckets() {
        ClientIpResolver ipResolver = mock(ClientIpResolver.class);
        when(ipResolver.rateLimitClientIp(any())).thenReturn("198.51.100.31");
        ObjectProvider<GoogleSignInProperties> googleProperties = mock(ObjectProvider.class);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                new RateLimitService(100, Duration.ofMinutes(10), new MutableTimeMeter()),
                ipResolver,
                googleProperties);

        assertLimited(interceptor, "PUT", "/api/v1/users/me/email");
        assertLimited(interceptor, "POST", "/api/v1/users/me/email/verify");
        assertLimited(interceptor, "POST", "/api/v1/users/me/deletion-code");
    }

    private static void assertLimited(RateLimitInterceptor interceptor, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        for (int attempt = 0; attempt < 30; attempt++) {
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ApiException.class);
    }
}
