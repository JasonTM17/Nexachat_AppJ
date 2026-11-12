package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.mail.MailSender;
import com.nexachat.recovery.EmailOtpService;
import com.nexachat.recovery.OtpPurpose;
import com.nexachat.recovery.OtpMailDispatcher;
import com.nexachat.auth.RateLimitService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmailRecoveryService {

    private static final long ACCOUNT_REQUEST_CAPACITY = 10;
    private static final long EMAIL_REQUEST_CAPACITY = 5;
    private static final long EMAIL_CONFIRM_CAPACITY = 10;
    private static final long ACCOUNT_CONFIRM_CAPACITY = 10;
    private static final Duration RATE_LIMIT_PERIOD = Duration.ofMinutes(5);

    private final EmailOtpService otpService;
    private final MailSender mailSender;
    private final OtpMailDispatcher mailDispatcher;
    private final RateLimitService rateLimitService;

    public EmailRecoveryService(EmailOtpService otpService,
                                MailSender mailSender,
                                OtpMailDispatcher mailDispatcher,
                                RateLimitService rateLimitService) {
        this.otpService = otpService;
        this.mailSender = mailSender;
        this.mailDispatcher = mailDispatcher;
        this.rateLimitService = rateLimitService;
    }

    public void requestVerification(UUID userId, String email) {
        requireMailAvailable();
        String normalizedEmail = EmailOtpService.normalizeEmail(email);
        otpService.requireEmailVerificationCooldown(userId);
        enforceLimit("recovery-email-request|account|" + userId,
                ACCOUNT_REQUEST_CAPACITY);
        boolean addressAllowed = consumeLimit("recovery-email-request|address|" + sha256Hex(normalizedEmail),
                EMAIL_REQUEST_CAPACITY);
        mailDispatcher.dispatchEmailVerification(
                () -> addressAllowed
                        ? Optional.of(otpService.beginEmailVerification(userId, normalizedEmail))
                        : Optional.empty(),
                mail -> otpService.cancel(mail.otpId()));
    }

    @Transactional
    public String verify(UUID userId, String code) {
        enforceLimit("recovery-email-confirm|account|" + userId, ACCOUNT_CONFIRM_CAPACITY);
        boolean addressAllowed = otpService.activeVerificationEmail(userId)
                .map(email -> consumeLimit("recovery-email-confirm|address|" + sha256Hex(email),
                        EMAIL_CONFIRM_CAPACITY))
                .orElse(true);
        if (!addressAllowed) {
            otpService.dummyCompare(OtpPurpose.VERIFY_EMAIL, code);
            throw ApiException.of(ApiErrorCode.INVALID_OR_EXPIRED_CODE, "Code is invalid or expired");
        }
        return otpService.verifyEmail(userId, code);
    }

    public void remove(UUID userId) {
        otpService.removeEmail(userId);
    }

    /**
     * Sends a DELETE_ACCOUNT code to the caller's own verified email.
     * Password accounts are refused before any limit or mail work.
     */
    public void requestDeletionCode(UUID userId) {
        String email = otpService.passwordlessVerifiedEmail(userId);
        requireMailAvailable();
        otpService.requireSendCooldown(userId, OtpPurpose.DELETE_ACCOUNT);
        enforceLimit("deletion-code|account|" + userId, ACCOUNT_REQUEST_CAPACITY);
        enforceLimit("deletion-code|address|" + sha256Hex(email), EMAIL_REQUEST_CAPACITY);
        mailDispatcher.dispatchAuthenticatedDeletion(
                () -> Optional.of(otpService.issueAuthenticatedDeletionCode(userId)),
                mail -> otpService.cancel(mail.otpId()));
    }

    private void requireMailAvailable() {
        if (!mailSender.isAvailable()) {
            throw mailUnavailable();
        }
    }

    private static ApiException mailUnavailable() {
        return ApiException.of(ApiErrorCode.MAIL_UNAVAILABLE, "Email service is unavailable");
    }

    private void enforceLimit(String key, long capacity) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(key, capacity, RATE_LIMIT_PERIOD);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }

    private boolean consumeLimit(String key, long capacity) {
        return rateLimitService.tryConsume(key, capacity, RATE_LIMIT_PERIOD).allowed();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
