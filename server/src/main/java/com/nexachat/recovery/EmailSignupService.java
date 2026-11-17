package com.nexachat.recovery;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.mail.MailSender;
import com.nexachat.user.UserRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/** Sends the code that lets a person register with an email address. */
@Service
public class EmailSignupService {

    private static final long ADDRESS_REQUEST_CAPACITY = 5;
    private static final Duration PERIOD = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final SignupEmailOtpService otpService;
    private final OtpMailDispatcher mailDispatcher;
    private final MailSender mailSender;
    private final RateLimitService rateLimitService;

    public EmailSignupService(UserRepository userRepository,
                              SignupEmailOtpService otpService,
                              OtpMailDispatcher mailDispatcher,
                              MailSender mailSender,
                              RateLimitService rateLimitService) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.mailDispatcher = mailDispatcher;
        this.mailSender = mailSender;
        this.rateLimitService = rateLimitService;
    }

    public void requestCode(String rawEmail) {
        if (!mailSender.isAvailable()) {
            throw ApiException.of(ApiErrorCode.MAIL_UNAVAILABLE, "Email service is unavailable");
        }
        String email = EmailOtpService.normalizeEmail(rawEmail);
        if (userRepository.existsByEmailAndEmailVerifiedAtIsNotNullAndDeletedAtIsNull(email)) {
            throw ApiException.of(ApiErrorCode.EMAIL_ALREADY_IN_USE, "Email is already registered");
        }
        otpService.requireCooldownElapsed(email);
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                "signup-email-request|address|" + sha256Hex(email), ADDRESS_REQUEST_CAPACITY, PERIOD);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
        mailDispatcher.dispatchSignupVerification(
                () -> Optional.of(otpService.issue(email)),
                mail -> otpService.cancel(mail.otpId()));
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
