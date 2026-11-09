package com.nexachat.auth;

import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AccessTokenRevocationService {

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final ApplicationEventPublisher eventPublisher;

    public AccessTokenRevocationService(UserRepository userRepository,
                                        TokenService tokenService,
                                        ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void revoke(UUID userId) {
        stampAccessTokenCutoff(userId);
        eventPublisher.publishEvent(new AccessTokensRevokedEvent(userId));
    }

    /** Revokes a reused refresh family and its access tokens in one transaction. */
    @Transactional
    public void revokeRefreshFamilyAndAccessTokens(UUID userId) {
        tokenService.revokeAllForUser(userId);
        stampAccessTokenCutoff(userId);
        eventPublisher.publishEvent(new AccessTokensRevokedEvent(userId));
    }

    private void stampAccessTokenCutoff(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            // JWT iat has one-second resolution, so preserve the existing one-second slack.
            user.invalidateAccessTokens(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        }
    }
}
