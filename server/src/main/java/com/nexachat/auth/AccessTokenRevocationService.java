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
    private final ApplicationEventPublisher eventPublisher;

    public AccessTokenRevocationService(UserRepository userRepository,
                                        ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void revoke(UUID userId) {
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
