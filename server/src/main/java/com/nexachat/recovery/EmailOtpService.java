package com.nexachat.recovery;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmailOtpService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration SEND_COOLDOWN = Duration.ofSeconds(60);
    private static final String VERIFIED_EMAIL_INDEX = "users_verified_email_active_key";

    private final UserRepository userRepository;
    private final EmailOtpRepository otpRepository;
    private final OtpCodeHasher codeHasher;
    private final OtpAttemptRecorder attemptRecorder;
    private final Clock clock;

    public EmailOtpService(UserRepository userRepository,
                           EmailOtpRepository otpRepository,
                           OtpCodeHasher codeHasher,
                           OtpAttemptRecorder attemptRecorder,
                           Clock clock) {
        this.userRepository = userRepository;
        this.otpRepository = otpRepository;
        this.codeHasher = codeHasher;
        this.attemptRecorder = attemptRecorder;
        this.clock = clock;
    }

    @Transactional
    public OtpMail beginEmailVerification(UUID userId, String rawEmail) {
        User user = lockActiveUser(userId);
        String email = normalizeEmail(rawEmail);
        if (userRepository.existsVerifiedEmailForOtherUser(email, userId)) {
            throw ApiException.of(ApiErrorCode.EMAIL_ALREADY_IN_USE, "Recovery email is already in use");
        }
        Instant now = clock.instant();
        requireCooldownElapsed(userId, OtpPurpose.VERIFY_EMAIL, now);
        user.changeEmail(email);
        userRepository.saveAndFlush(user);
        return issueLocked(user, OtpPurpose.VERIFY_EMAIL, email, now);
    }

    @Transactional
    public Optional<OtpMail> issueForPublicRequest(UUID userId, OtpPurpose purpose) {
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null || !user.hasVerifiedEmail()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Instant latest = otpRepository.latestCreatedAt(userId, purpose);
        if (latest != null && latest.plus(SEND_COOLDOWN).isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(issueLocked(user, purpose, user.getEmail(), now));
    }

    @Transactional
    public String verifyEmail(UUID userId, String code) {
        User user = lockActiveUser(userId);
        String email = user.getEmail();
        if (email != null) {
            otpRepository.lockVerifiedEmail(email);
        }
        if (email == null || user.getEmailVerifiedAt() != null
                || userRepository.existsVerifiedEmailForOtherUser(email, userId)
                || !consume(user, OtpPurpose.VERIFY_EMAIL, code)) {
            if (email != null && user.getEmailVerifiedAt() == null
                    && userRepository.existsVerifiedEmailForOtherUser(email, userId)) {
                throw ApiException.of(ApiErrorCode.EMAIL_ALREADY_IN_USE, "Recovery email is already in use");
            }
            throw invalidCode();
        }
        user.verifyEmail(clock.instant());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isVerifiedEmailConflict(exception)) {
                throw ApiException.of(ApiErrorCode.EMAIL_ALREADY_IN_USE, "Recovery email is already in use");
            }
            throw exception;
        }
        return email;
    }

    @Transactional
    public void removeEmail(UUID userId) {
        User user = lockActiveUser(userId);
        user.removeEmail();
        userRepository.save(user);
        otpRepository.invalidateActive(userId, OtpPurpose.VERIFY_EMAIL, clock.instant());
    }

    @Transactional
    public boolean consume(User user, OtpPurpose purpose, String code) {
        String email = switch (purpose) {
            case VERIFY_EMAIL -> user.getEmailVerifiedAt() == null ? user.getEmail() : null;
            case DELETE_ACCOUNT, RESET_PASSWORD -> user.hasVerifiedEmail() ? user.getEmail() : null;
        };
        if (email == null) {
            codeHasher.dummyCompare(purpose, code);
            return false;
        }
        EmailOtpRecord candidate = otpRepository.findActive(user.getId(), purpose, email).orElse(null);
        if (candidate == null) {
            codeHasher.dummyCompare(purpose, code);
            return false;
        }
        Instant now = clock.instant();
        if (!candidate.expiresAt().isAfter(now) || candidate.attempts() >= 5) {
            codeHasher.dummyCompare(purpose, code);
            return false;
        }
        if (!codeHasher.matches(candidate.codeHash(), user.getId(), purpose, code)) {
            attemptRecorder.recordWrongAttempt(candidate.id(), now);
            return false;
        }
        return otpRepository.consume(candidate.id(), now);
    }

    @Transactional
    public void cancel(UUID otpId) {
        otpRepository.cancel(otpId, clock.instant());
    }

    public void performDummyWork(OtpPurpose purpose) {
        String randomCode = codeHasher.newCode();
        codeHasher.hash(UUID.nameUUIDFromBytes("nexachat-otp-dummy-user".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                purpose, randomCode);
    }

    public void dummyCompare(OtpPurpose purpose, String submittedCode) {
        codeHasher.dummyCompare(purpose, submittedCode);
    }

    public static String normalizeEmail(String rawEmail) {
        if (rawEmail == null) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Email address is not valid");
        }
        String normalized = rawEmail.strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 254
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Email address is not valid");
        }
        try {
            jakarta.mail.internet.InternetAddress[] addresses =
                    jakarta.mail.internet.InternetAddress.parse(normalized, true);
            if (addresses.length != 1 || !normalized.equals(addresses[0].getAddress())) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Email address is not valid");
            }
            addresses[0].validate();
            return normalized;
        } catch (jakarta.mail.internet.AddressException exception) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Email address is not valid");
        }
    }

    private OtpMail issueLocked(User user, OtpPurpose purpose, String email, Instant now) {
        otpRepository.invalidateActive(user.getId(), purpose, now);
        String code = codeHasher.newCode();
        UUID id = UUID.randomUUID();
        otpRepository.insert(id, user.getId(), purpose, email,
                codeHasher.hash(user.getId(), purpose, code), now.plus(CODE_TTL), now);
        return new OtpMail(id, email, purpose, code);
    }

    private void requireCooldownElapsed(UUID userId, OtpPurpose purpose, Instant now) {
        Instant latest = otpRepository.latestCreatedAt(userId, purpose);
        if (latest != null && latest.plus(SEND_COOLDOWN).isAfter(now)) {
            long retryAfter = Math.max(1L, Duration.between(now, latest.plus(SEND_COOLDOWN)).toSeconds());
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Rate limit exceeded, try again later",
                    java.util.Map.of("retryAfterSeconds", retryAfter));
        }
    }

    private User lockActiveUser(UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        return user;
    }

    private static boolean isVerifiedEmailConflict(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation
                    && VERIFIED_EMAIL_INDEX.equals(violation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static ApiException invalidCode() {
        return ApiException.of(ApiErrorCode.INVALID_OR_EXPIRED_CODE, "Code is invalid or expired");
    }
}
