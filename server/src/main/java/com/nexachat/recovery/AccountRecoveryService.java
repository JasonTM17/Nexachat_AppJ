package com.nexachat.recovery;

import com.nexachat.auth.AuthService;
import com.nexachat.auth.PasswordPolicy;
import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.mail.MailSender;
import com.nexachat.user.AccountDeletionService;
import com.nexachat.user.PhoneService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Service
public class AccountRecoveryService {

    private static final long REQUEST_PHONE_CAPACITY = 10;
    private static final long CONFIRM_PHONE_CAPACITY = 20;
    private static final Duration PHONE_PERIOD = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final PhoneService phoneService;
    private final EmailOtpService otpService;
    private final OtpMailDispatcher mailDispatcher;
    private final MailSender mailSender;
    private final RateLimitService rateLimitService;
    private final AccountDeletionService accountDeletionService;
    private final AuthService authService;

    public AccountRecoveryService(UserRepository userRepository,
                                  PhoneService phoneService,
                                  EmailOtpService otpService,
                                  OtpMailDispatcher mailDispatcher,
                                  MailSender mailSender,
                                  RateLimitService rateLimitService,
                                  AccountDeletionService accountDeletionService,
                                  AuthService authService) {
        this.userRepository = userRepository;
        this.phoneService = phoneService;
        this.otpService = otpService;
        this.mailDispatcher = mailDispatcher;
        this.mailSender = mailSender;
        this.rateLimitService = rateLimitService;
        this.accountDeletionService = accountDeletionService;
        this.authService = authService;
    }

    public void requestAccountDeletion(String rawPhone) {
        requestCode(rawPhone, OtpPurpose.DELETE_ACCOUNT);
    }

    public void requestPasswordReset(String rawPhone) {
        requestCode(rawPhone, OtpPurpose.RESET_PASSWORD);
    }

    @Transactional
    public void confirmAccountDeletion(String rawPhone, String code) {
        String phone = normalizeOrNull(rawPhone);
        if (phone == null) {
            otpService.dummyCompare(OtpPurpose.DELETE_ACCOUNT, code);
            throw invalidCode();
        }
        enforcePhoneLimit("confirm", OtpPurpose.DELETE_ACCOUNT, phone);
        User user = userRepository.findByPhoneAndDeletedAtIsNull(phone).orElse(null);
        if (user == null) {
            otpService.dummyCompare(OtpPurpose.DELETE_ACCOUNT, code);
            throw invalidCode();
        }
        User locked = userRepository.findByIdForUpdate(user.getId()).orElse(null);
        if (locked == null || locked.getDeletedAt() != null || !phone.equals(locked.getPhone())
                || !otpService.consume(locked, OtpPurpose.DELETE_ACCOUNT, code)) {
            throw invalidCode();
        }
        accountDeletionService.deleteAccountByVerifiedRecovery(locked.getId());
    }

    @Transactional
    public void confirmPasswordReset(String rawPhone, String code, String newPassword) {
        PasswordPolicy.requireRegisterable(newPassword);
        String phone = normalizeOrNull(rawPhone);
        if (phone == null) {
            otpService.dummyCompare(OtpPurpose.RESET_PASSWORD, code);
            throw invalidCode();
        }
        enforcePhoneLimit("confirm", OtpPurpose.RESET_PASSWORD, phone);
        User user = userRepository.findByPhoneAndDeletedAtIsNull(phone).orElse(null);
        if (user == null) {
            otpService.dummyCompare(OtpPurpose.RESET_PASSWORD, code);
            throw invalidCode();
        }
        User locked = userRepository.findByIdForUpdate(user.getId()).orElse(null);
        if (locked == null || locked.getDeletedAt() != null || !phone.equals(locked.getPhone())
                || !otpService.consume(locked, OtpPurpose.RESET_PASSWORD, code)) {
            throw invalidCode();
        }
        authService.resetPassword(locked.getId(), newPassword);
    }

    private void requestCode(String rawPhone, OtpPurpose purpose) {
        String phone = normalizeOrNull(rawPhone);
        if (phone != null) {
            enforcePhoneLimit("request", purpose, phone);
        }
        mailDispatcher.dispatchPublicRequest(purpose, () -> preparePublicRequest(phone, purpose));
    }

    private Optional<OtpMail> preparePublicRequest(String phone, OtpPurpose purpose) {
        Optional<OtpMail> delivery = Optional.empty();
        if (phone != null && mailSender.isAvailable()) {
            User user = userRepository.findByPhoneAndDeletedAtIsNull(phone).orElse(null);
            if (user != null) {
                delivery = otpService.issueForPublicRequest(user.getId(), purpose);
            }
        }
        if (delivery.isEmpty()) {
            otpService.performDummyWork(purpose);
        }
        return delivery;
    }

    private String normalizeOrNull(String rawPhone) {
        if (rawPhone == null || rawPhone.length() > 64) {
            return null;
        }
        try {
            return phoneService.toE164(rawPhone);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void enforcePhoneLimit(String action, OtpPurpose purpose, String phone) {
        long capacity = "request".equals(action) ? REQUEST_PHONE_CAPACITY : CONFIRM_PHONE_CAPACITY;
        String key = "public-otp|" + purpose.name() + "|" + action + "|phone|" + phone;
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(key, capacity, PHONE_PERIOD);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }

    private static ApiException invalidCode() {
        return ApiException.of(ApiErrorCode.INVALID_OR_EXPIRED_CODE, "Code is invalid or expired");
    }
}
