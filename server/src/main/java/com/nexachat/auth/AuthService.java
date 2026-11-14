package com.nexachat.auth;

import com.nexachat.auth.dto.AuthResponse;
import com.nexachat.auth.dto.ChangePasswordRequest;
import com.nexachat.auth.dto.LoginRequest;
import com.nexachat.auth.dto.RefreshRequest;
import com.nexachat.auth.dto.RegisterRequest;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.recovery.EmailOtpService;
import com.nexachat.user.PhoneService;
import com.nexachat.user.User;
import com.nexachat.user.UserAccountCreatedEvent;
import com.nexachat.user.UserRepository;
import com.nexachat.ws.StompRevocationFence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the auth use cases (register/login/refresh/logout/password).
 *
 * <p>Anti-enumeration guarantees (§13): login returns the SAME
 * {@code INVALID_CREDENTIALS} whether the phone is unknown or the password is
 * wrong — an unparseable phone at login also maps to {@code INVALID_CREDENTIALS}
 * (unlike register, where bad input honestly returns {@code VALIDATION_FAILED}).
 * Unknown accounts and unparseable phones both pay one bcrypt compare against a
 * dummy hash to reduce timing-based account-enumeration signals.
 *
 * <p>Transaction note: {@link #refresh} is deliberately NOT {@code @Transactional}
 * at this level — when {@link TokenService#rotate} reports reuse/expiry, the
 * family and access-token revocation must commit in its own transaction before the 401 is thrown;
 * rolling it back together with the rotation would silently defeat reuse
 * detection.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final PhoneService phoneService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AccessTokenRevocationService accessTokenRevocationService;
    private final RateLimitService rateLimitService;
    private final AuthResponseFactory responseFactory;
    private final RefreshReplayConfiguration.BoundaryHook boundaryHook;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;
    private final StompRevocationFence revocationFence;

    /** Well-formed bcrypt used to equalize login timing for unknown accounts and malformed phones. */
    private final String dummyHash;

    /** Per-phone credential budget, on top of the interceptor's per-IP limit. */
    private static final long PHONE_RATE_CAPACITY = 20;
    private static final Duration PHONE_RATE_PERIOD = Duration.ofMinutes(5);

    public AuthService(UserRepository userRepository,
                       EntityManager entityManager,
                       PhoneService phoneService,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       AccessTokenRevocationService accessTokenRevocationService,
                       RateLimitService rateLimitService,
                       AuthResponseFactory responseFactory,
                       RefreshReplayConfiguration.BoundaryHook boundaryHook,
                       ApplicationEventPublisher eventPublisher,
                       ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
                       StompRevocationFence revocationFence) {
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.phoneService = phoneService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.accessTokenRevocationService = accessTokenRevocationService;
        this.rateLimitService = rateLimitService;
        this.responseFactory = responseFactory;
        this.boundaryHook = boundaryHook;
        this.eventPublisher = eventPublisher;
        this.transactionManagerProvider = transactionManagerProvider;
        this.revocationFence = revocationFence;
        // Computed once at startup (~one bcrypt round), guaranteed to be a valid hash.
        this.dummyHash = passwordEncoder.encode("nexachat-timing-equalization");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, String deviceLabel) {
        final String phone = normalizeForRegister(request.phone());
        PasswordPolicy.requireRegisterable(request.password());
        enforcePhoneRateLimit("register", phone);

        if (userRepository.existsByPhoneAndDeletedAtIsNull(phone)) {
            throw ApiException.of(ApiErrorCode.PHONE_EXISTS, "Phone number is already registered");
        }
        User user = User.create(phone, passwordEncoder.encode(request.password()), request.displayName().trim());
        try {
            // saveAndFlush, not save: the INSERT must hit the UNIQUE constraint
            // NOW — save() alone defers the flush to commit-time, after this
            // method returns, and the violation would escape the catch.
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registers can both pass the existence check — the
            // UNIQUE constraint is the final arbiter, mapped back to the same contract.
            throw ApiException.of(ApiErrorCode.PHONE_EXISTS, "Phone number is already registered");
        }
        eventPublisher.publishEvent(new UserAccountCreatedEvent(user.getId()));
        log.info("Registered new account");
        return issueSession(user, deviceLabel);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String deviceLabel) {
        if (!PasswordPolicy.fitsBcrypt(request.password())) {
            passwordEncoder.matches("nexachat-timing-equalization", dummyHash);
            throw invalidCredentials();
        }
        final String identifier = request.loginIdentifier();
        User foundUser;
        String claimedEmail = null;
        String claimedPhone = null;
        if (identifier.indexOf('@') >= 0) {
            String email;
            try {
                email = EmailOtpService.normalizeEmail(identifier);
            } catch (ApiException e) {
                passwordEncoder.matches(request.password(), dummyHash);
                throw invalidCredentials();
            }
            enforceCredentialRateLimit("login", "email|" + email);
            claimedEmail = email;
            // Only a verified email identifies an account, so an unverified claim cannot squat a login.
            foundUser = userRepository.findActiveByVerifiedEmail(email).orElse(null);
        } else {
            String phone;
            try {
                phone = phoneService.toE164(identifier);
            } catch (IllegalArgumentException e) {
                passwordEncoder.matches(request.password(), dummyHash);
                throw invalidCredentials();
            }
            enforcePhoneRateLimit("login", phone);
            claimedPhone = phone;
            foundUser = userRepository.findByPhoneAndDeletedAtIsNull(phone).orElse(null);
        }
        if (foundUser == null) {
            // Pay one bcrypt compare anyway so unknown vs known users take the same time.
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        User user = userRepository.findByIdForUpdate(foundUser.getId()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        // The unlocked read above may already have placed a pre-lock snapshot in this persistence context.
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        // The identifier that located this account must still belong to it once the row is locked: an email
        // removed or replaced (or an account deleted) between the lookup and the lock must not authorize a login.
        boolean stillOwned = user.getDeletedAt() == null
                && (claimedEmail == null
                        || (claimedEmail.equals(user.getEmail()) && user.getEmailVerifiedAt() != null))
                && (claimedPhone == null || claimedPhone.equals(user.getPhone()));
        String hash = user.getPasswordHash();
        boolean hasPassword = hash != null && !hash.isBlank();
        // Always pay exactly one bcrypt compare, so a passwordless account is not faster than a wrong password.
        boolean passwordMatches = passwordEncoder.matches(request.password(), hasPassword ? hash : dummyHash);
        if (!stillOwned || !hasPassword || !passwordMatches) {
            throw invalidCredentials();
        }
        return issueSession(user, deviceLabel);
    }

    /**
     * Refresh rotation entry point. Not transactional here on purpose — see
     * class javadoc: the family revocation for a reused/expired token must
     * commit BEFORE the 401 is raised.
     */
    public AuthResponse refresh(RefreshRequest request, String deviceLabel) {
        return refresh(request, deviceLabel, null);
    }

    public AuthResponse refresh(RefreshRequest request, String deviceLabel, UUID requestKey) {
        TokenService.RotationResult rotation;
        try {
            rotation = tokenService.rotate(request.refreshToken(), requestKey, deviceLabel);
        } catch (RefreshTokenException e) {
            if (e.userId() != null) {
                // Separate transaction → refresh-family and access-token
                // revocation commit although this method then throws the 401.
                RefreshReplayConfiguration.Operation reason = e.errorCode() == ApiErrorCode.TOKEN_REUSED
                        ? RefreshReplayConfiguration.Operation.REUSE_REVOKE
                        : RefreshReplayConfiguration.Operation.EXPIRY_REVOKE;
                accessTokenRevocationService.revokeRefreshFamilyAndAccessTokens(e.userId(), reason);
                log.warn("Refresh-token {} detected; revoked all sessions",
                        e.errorCode() == ApiErrorCode.TOKEN_REUSED ? "reuse" : "expiry");
            }
            throw ApiException.of(e.errorCode(), e.getMessage());
        }
        // This seam is deliberately after TokenService's proxy has committed.
        boundaryHook.reached(RefreshReplayConfiguration.Boundary.AFTER_REFRESH_TRANSACTION_COMMIT,
                RefreshReplayConfiguration.Operation.ROTATE, rotation.userId(), rotation.predecessorId());

        boolean ownerUnavailable = Boolean.TRUE.equals(new TransactionTemplate(
                transactionManagerProvider.getObject()).execute(status ->
                userRepository.findByIdForUpdate(rotation.userId())
                        .map(user -> user.getDeletedAt() != null)
                        .orElse(true)));
        if (ownerUnavailable) {
            tokenService.revokeIfOwned(rotation.response().refreshToken(), rotation.userId());
            throw ApiException.of(ApiErrorCode.TOKEN_INVALID, "Refresh token is not valid");
        }
        return rotation.response();
    }

    /**
     * Logout revokes the presented refresh token and stamps the access-token
     * cutoff, so the access token this session was using stops working now
     * instead of at its natural expiry.
     */
    @Transactional
    public void logout(UUID userId, String rawRefreshToken) {
        tokenService.revokeIfOwned(rawRefreshToken, userId);
        accessTokenRevocationService.revoke(userId);
    }

    @Transactional
    public void logoutAll(UUID userId) {
        tokenService.revokeAllForUser(userId);
        accessTokenRevocationService.revoke(userId);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        revocationFence.holdWriteUntilTransactionCompletion(userId);
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }

        String existingHash = user.getPasswordHash();
        if (existingHash == null || existingHash.isBlank()) {
            throw ApiException.of(ApiErrorCode.NO_PASSWORD_SET, "This account does not have a password");
        }
        if (!passwordEncoder.matches(request.oldPassword(), existingHash)) {
            throw ApiException.of(ApiErrorCode.WRONG_PASSWORD, "Current password is incorrect");
        }
        PasswordPolicy.requireRegisterable(request.newPassword());
        user.changePasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // A password change must kill every access token issued so far; the
        // client of the current session rotates with its kept refresh token.
        accessTokenRevocationService.revoke(userId);

        // "Revoke all other refresh tokens": keep the supplied session token alive
        // if the client passed one, otherwise revoke every session (FR-A6).
        if (request.refreshToken() != null && !request.refreshToken().isBlank()) {
            tokenService.revokeAllForUserExcept(userId, request.refreshToken());
        } else {
            tokenService.revokeAllForUser(userId);
        }
        log.info("Password changed");
    }

    @Transactional
    public void resetPassword(UUID userId, String newPassword) {
        PasswordPolicy.requireRegisterable(newPassword);
        revocationFence.holdWriteUntilTransactionCompletion(userId);
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        tokenService.revokeAllForUser(userId);
        accessTokenRevocationService.revoke(userId);
        log.info("Password reset");
    }

    private AuthResponse issueSession(User user, String deviceLabel) {
        String refreshToken = tokenService.issue(user, deviceLabel);
        return responseFactory.create(user, refreshToken);
    }

    private String normalizeForRegister(String rawPhone) {
        try {
            return phoneService.toE164(rawPhone);
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Phone number is not valid");
        }
    }

    private static ApiException invalidCredentials() {
        return ApiException.of(ApiErrorCode.INVALID_CREDENTIALS, "Invalid email, phone number or password");
    }

    /**
     * Bounds credential guessing per account, not only per source address: an
     * attacker rotating IPs still exhausts one phone's budget. Runs after the
     * phone is normalized so the key is canonical E.164.
     */
    private void enforcePhoneRateLimit(String action, String phone) {
        enforceCredentialRateLimit(action, "phone|" + phone);
    }

    private void enforceCredentialRateLimit(String action, String subject) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                action + "|" + subject, PHONE_RATE_CAPACITY, PHONE_RATE_PERIOD);
        if (!consumption.allowed()) {
            throw ApiException.of(
                    ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }
}
