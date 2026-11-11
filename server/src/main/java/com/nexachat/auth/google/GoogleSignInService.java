package com.nexachat.auth.google;

import com.nexachat.auth.AuthResponseFactory;
import com.nexachat.auth.RateLimitService;
import com.nexachat.auth.TokenService;
import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.auth.google.dto.GoogleLinkRequest;
import com.nexachat.auth.google.dto.GoogleSignInRequest;
import com.nexachat.auth.google.dto.GoogleUnlinkRequest;
import com.nexachat.auth.google.dto.LinkedIdentitiesResponse;
import com.nexachat.auth.google.dto.LinkedIdentityResponse;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Link, unlink and sign-in flows for Google identities.
 *
 * <p>Sign-in only succeeds for a Google {@code sub} that is already linked to a
 * NexaChat account; automatic account creation and email-based linking are
 * deliberately out of scope (account-takeover risk). Errors are generic so a
 * caller cannot probe which Google accounts exist.
 */
@Service
public class GoogleSignInService {

    /** Password re-authentication on link/unlink is bounded per account. */
    private static final long LINK_RATE_CAPACITY = 10;
    private static final Duration LINK_RATE_PERIOD = Duration.ofMinutes(5);

    private final GoogleSignInProperties properties;
    private final GoogleIdTokenVerifier verifier;
    private final GoogleNonceService nonceService;
    private final UserIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuthResponseFactory responseFactory;
    private final RateLimitService rateLimitService;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

    public GoogleSignInService(GoogleSignInProperties properties,
                               GoogleIdTokenVerifier verifier,
                               GoogleNonceService nonceService,
                               UserIdentityRepository identityRepository,
                               UserRepository userRepository,
                               PasswordEncoder passwordEncoder,
                               TokenService tokenService,
                               AuthResponseFactory responseFactory,
                               RateLimitService rateLimitService,
                               ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.properties = properties;
        this.verifier = verifier;
        this.nonceService = nonceService;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.responseFactory = responseFactory;
        this.rateLimitService = rateLimitService;
        this.transactionManagerProvider = transactionManagerProvider;
    }

    /** Every endpoint of a disabled feature answers 404 — existence is not disclosed. */
    public void requireEnabled() {
        if (!properties.enabled()) {
            throw ApiException.of(ApiErrorCode.NOT_FOUND, "Not found");
        }
    }

    public AuthResponse signIn(GoogleSignInRequest request, String deviceLabel) {
        requireEnabled();
        GoogleIdTokenClaims claims = verifier.verify(request.idToken(), request.nonce());
        consumeNonce(request.nonce(), GoogleNonceService.PURPOSE_SIGNIN, null, null);

        // Keep remote JWKS access and the independently committed nonce update
        // outside this transaction. Otherwise concurrent requests can each hold
        // one pool connection while waiting for a REQUIRES_NEW connection.
        return transactionTemplate().execute(status -> {
            UserIdentity identity = identityRepository
                    .findByProviderAndSubject(UserIdentity.PROVIDER_GOOGLE, claims.subject())
                    .orElseThrow(GoogleSignInService::notLinked);
            User user = userRepository.findByIdForUpdate(identity.getUserId())
                    .orElseThrow(GoogleSignInService::notLinked);
            if (user.getDeletedAt() != null) {
                throw notLinked();
            }

            String refreshToken = tokenService.issue(user, deviceLabel);
            return responseFactory.create(user, refreshToken);
        });
    }

    public void link(UUID userId, String accessTokenId, GoogleLinkRequest request) {
        requireEnabled();
        enforceAccountRateLimit(userId, "google-link");
        User initialUser = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (!passwordEncoder.matches(request.password(), initialUser.getPasswordHash())) {
            throw ApiException.of(ApiErrorCode.WRONG_PASSWORD, "Current password is incorrect");
        }

        GoogleIdTokenClaims claims = verifier.verify(request.idToken(), request.nonce());
        consumeNonce(request.nonce(), GoogleNonceService.PURPOSE_LINK, userId, accessTokenId);

        // The password check above preserves the request contract: a wrong
        // password does not burn a valid nonce. Recheck after locking because
        // the password may change while the token is verified.
        transactionTemplate().executeWithoutResult(status -> linkInTransaction(userId, request, claims));
    }

    private void linkInTransaction(UUID userId, GoogleLinkRequest request, GoogleIdTokenClaims claims) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.of(ApiErrorCode.WRONG_PASSWORD, "Current password is incorrect");
        }

        UserIdentity existing = identityRepository
                .findByProviderAndSubject(UserIdentity.PROVIDER_GOOGLE, claims.subject())
                .orElse(null);
        if (existing != null) {
            if (existing.getUserId().equals(userId)) {
                return; // idempotent: the same account is already linked
            }
            throw alreadyLinked();
        }
        if (identityRepository.findByUser_IdAndProvider(userId, UserIdentity.PROVIDER_GOOGLE).isPresent()) {
            throw alreadyLinked();
        }
        try {
            identityRepository.saveAndFlush(
                    UserIdentity.link(user, UserIdentity.PROVIDER_GOOGLE, claims.subject(),
                            claims.email()));
        } catch (DataIntegrityViolationException exception) {
            // A concurrent link won the unique constraint; keep the generic answer.
            throw alreadyLinked();
        }
    }

    @Transactional
    public void unlink(UUID userId, GoogleUnlinkRequest request) {
        requireEnabled();
        enforceAccountRateLimit(userId, "google-unlink");
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw ApiException.of(ApiErrorCode.LAST_SIGN_IN_METHOD,
                    "The last sign-in method cannot be removed");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.of(ApiErrorCode.WRONG_PASSWORD, "Current password is incorrect");
        }
        UserIdentity identity = identityRepository
                .findByUser_IdAndProvider(userId, UserIdentity.PROVIDER_GOOGLE)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.NOT_FOUND, "No linked Google account"));
        identityRepository.delete(identity);
        identityRepository.flush();
    }

    @Transactional(readOnly = true)
    public LinkedIdentitiesResponse list(UUID userId) {
        requireEnabled();
        List<LinkedIdentityResponse> identities = identityRepository
                .findAllByUser_IdOrderByLinkedAtAsc(userId)
                .stream()
                .map(identity -> new LinkedIdentityResponse(
                        identity.getProvider(), identity.getEmailAtLink(), identity.getLinkedAt()))
                .toList();
        return new LinkedIdentitiesResponse(identities);
    }

    /** Issues a nonce for the authenticated link flow, bounded per account. */
    public GoogleNonceService.IssuedNonce issueLinkNonce(UUID userId, String accessTokenId) {
        requireEnabled();
        enforceAccountRateLimit(userId, "google-link-nonce");
        return nonceService.issue(GoogleNonceService.PURPOSE_LINK, userId, accessTokenId);
    }

    private void consumeNonce(String nonce, String purpose, UUID userId, String accessTokenId) {
        if (!nonceService.consume(nonce, purpose, userId, accessTokenId)) {
            throw ApiException.of(ApiErrorCode.GOOGLE_SIGN_IN_FAILED,
                    "Google sign-in could not be completed");
        }
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManagerProvider.getObject());
    }

    private void enforceAccountRateLimit(UUID userId, String action) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                action + "|user|" + userId, LINK_RATE_CAPACITY, LINK_RATE_PERIOD);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }

    private static ApiException notLinked() {
        return ApiException.of(ApiErrorCode.GOOGLE_ACCOUNT_NOT_LINKED,
                "This Google account is not linked to a NexaChat account");
    }

    private static ApiException alreadyLinked() {
        return ApiException.of(ApiErrorCode.GOOGLE_ACCOUNT_ALREADY_LINKED,
                "This Google account cannot be linked");
    }
}
