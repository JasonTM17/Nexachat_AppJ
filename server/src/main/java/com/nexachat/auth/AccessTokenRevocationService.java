package com.nexachat.auth;

import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AccessTokenRevocationService {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final RefreshReplayConfiguration.BoundaryHook boundaryHook;

    public AccessTokenRevocationService(UserRepository userRepository,
                                        TokenService tokenService,
                                        ApplicationEventPublisher eventPublisher,
                                        Clock clock,
                                        RefreshReplayConfiguration.BoundaryHook boundaryHook) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.boundaryHook = boundaryHook;
    }

    @Transactional
    public void revoke(UUID userId) {
        User user = lockUser(userId, RefreshReplayConfiguration.Operation.ACCESS_REVOKE);
        Instant now = databaseTime();
        stampAccessTokenCutoff(user, now);
        eventPublisher.publishEvent(new AccessTokensRevokedEvent(userId));
    }

    /** Revokes a reused or expired refresh family and access tokens in one transaction. */
    @Transactional
    public void revokeRefreshFamilyAndAccessTokens(UUID userId) {
        revokeRefreshFamilyAndAccessTokens(userId, RefreshReplayConfiguration.Operation.REUSE_REVOKE);
    }

    @Transactional
    public void revokeRefreshFamilyAndAccessTokens(UUID userId,
                                                   RefreshReplayConfiguration.Operation operation) {
        User user = lockUser(userId, operation);
        Instant now = databaseTime();
        tokenService.revokeAllForUserAt(userId, now, operation);
        stampAccessTokenCutoff(user, now);
        eventPublisher.publishEvent(new AccessTokensRevokedEvent(userId));
    }

    private User lockUser(UUID userId, RefreshReplayConfiguration.Operation operation) {
        boundaryHook.reached(RefreshReplayConfiguration.Boundary.BEFORE_USER_LOCK, operation, userId, null);
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user != null) {
            boundaryHook.reached(RefreshReplayConfiguration.Boundary.AFTER_USER_LOCK, operation, userId, null);
        }
        return user;
    }

    private static void stampAccessTokenCutoff(User user, Instant now) {
        if (user != null) {
            // JWT iat has one-second resolution; access tokens at the cutoff second fail closed.
            user.invalidateAccessTokens(now.truncatedTo(ChronoUnit.SECONDS));
        }
    }

    private Instant databaseTime() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
