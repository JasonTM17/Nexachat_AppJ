package com.nexachat.auth;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.user.UserAuthValidity;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthFilterCacheRaceTest {

    private static final JwtProperties JWT_PROPERTIES =
            new JwtProperties("test-secret-at-least-32-bytes-long", 900, 14);

    @Test
    void staleCacheMissFinishingAfterRevocationCannotKeepOldCutoff() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minusSeconds(120);
        JwtService jwtService = mock(JwtService.class);
        UserRepository userRepository = mock(UserRepository.class);
        AccessTokenValidityCache cache = new AccessTokenValidityCache(JWT_PROPERTIES, 60, 10);
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, userRepository, cache);
        when(jwtService.parseAccessTokenClaims("old-token"))
                .thenReturn(new JwtService.AccessTokenClaims(userId, issuedAt));

        CountDownLatch oldReadStarted = new CountDownLatch(1);
        CountDownLatch finishOldRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(userRepository.findAuthValidityById(userId)).thenAnswer(invocation -> {
            if (reads.getAndIncrement() == 0) {
                oldReadStarted.countDown();
                assertThat(finishOldRead.await(5, TimeUnit.SECONDS)).isTrue();
                return Optional.of(new UserAuthValidity(userId, null));
            }
            return Optional.of(new UserAuthValidity(userId, issuedAt.plusSeconds(1)));
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Authentication> firstAuthentication = new AtomicReference<>();
            Future<?> firstRequest = executor.submit(() -> filterRequest(filter, firstAuthentication));
            assertThat(oldReadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Models the after-commit eviction racing the in-flight old database read.
            cache.evict(userId);
            finishOldRead.countDown();
            firstRequest.get(5, TimeUnit.SECONDS);
            assertThat(firstAuthentication.get()).isNotNull();

            AtomicReference<Authentication> secondAuthentication = new AtomicReference<>();
            MockHttpServletRequest secondRequest = filterRequest(filter, secondAuthentication);

            assertThat(secondAuthentication.get()).isNull();
            assertThat(secondRequest.getAttribute(JwtAuthFilter.AUTH_ERROR_ATTRIBUTE))
                    .isEqualTo(ApiErrorCode.TOKEN_INVALID);
            assertThat(reads).hasValue(2);
        } finally {
            finishOldRead.countDown();
            executor.shutdownNow();
            SecurityContextHolder.clearContext();
        }
    }

    private static MockHttpServletRequest filterRequest(
            JwtAuthFilter filter, AtomicReference<Authentication> authentication) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer old-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            filter.doFilterInternal(request, response, (ignoredRequest, ignoredResponse) ->
                    authentication.set(SecurityContextHolder.getContext().getAuthentication()));
        } catch (Exception e) {
            throw new AssertionError("Filter request failed", e);
        } finally {
            SecurityContextHolder.clearContext();
        }
        return request;
    }
}
