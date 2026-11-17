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
import com.nexachat.ws.StompRevocationFence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Service
public class AccountRecoveryService {

    private static final long REQUEST_PHONE_CAPACITY = 10;
    private static final long CONFIRM_PHONE_CAPACITY = 20;
    private static final long REQUEST_ACCOUNT_CAPACITY = 10;
    private static final long CONFIRM_ACCOUNT_CAPACITY = 10;
    private static final Duration PHONE_PERIOD = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final PhoneService phoneService;
    private final EmailOtpService otpService;
    private final OtpMailDispatcher mailDispatcher;
    private final MailSender mailSender;
    private final RateLimitService rateLimitService;
    private final AccountDeletionService accountDeletionService;
    private final AuthService authService;
    private final StompRevocationFence revocationFence;

    public AccountRecoveryService(UserRepository userRepository,
                                  PhoneService phoneService,
                                  EmailOtpService otpService,
                                  OtpMailDispatcher mailDispatcher,
                                  MailSender mailSender,
                                  RateLimitService rateLimitService,
                                  AccountDeletionService accountDeletionService,
                                  AuthService authService,
                                  StompRevocationFence revocationFence) {
        this.userRepository = userRepository;
        this.phoneService = phoneService;
        this.otpService = otpService;
        this.mailDispatcher = mailDispatcher;
        this.mailSender = mailSender;
        this.rateLimitService = rateLimitService;
        this.accountDeletionService = accountDeletionService;
        this.authService = authService;
        this.revocationFence = revocationFence;
    }

    public void requestAccountDeletion(String rawPhone) {
        requestCode(rawPhone, OtpPurpose.DELETE_ACCOUNT);
    }

    public void requestPasswordReset(String rawPhone) {
        requestPasswordReset(rawPhone, null);
    }

    public void requestPasswordReset(String rawPhone, String rawEmail) {
        requestCode(subject(rawPhone, rawEmail), OtpPurpose.RESET_PASSWORD);
    }

    @Transactional
    public void confirmAccountDeletion(String rawPhone, String code) {
        String phone = normalizeOrNull(rawPhone);
        if (phone == null) {
            otpService.dummyCompare(OtpPurpose.DELETE_ACCOUNT, code);
            throw invalidCode();
        }
        boolean phoneBudgetAvailable = consumePhoneLimit("confirm", OtpPurpose.DELETE_ACCOUNT, phone);
        boolean accountBudgetAvailable = consumeAccountPhoneLimit("confirm", phone,
                CONFIRM_ACCOUNT_CAPACITY);
        if (!phoneBudgetAvailable || !accountBudgetAvailable) {
            otpService.dummyCompare(OtpPurpose.DELETE_ACCOUNT, code);
            throw invalidCode();
        }
        User user = userRepository.findByPhoneAndDeletedAtIsNull(phone).orElse(null);
        if (user == null) {
            otpService.dummyCompare(OtpPurpose.DELETE_ACCOUNT, code);
            throw invalidCode();
        }
        revocationFence.holdWriteUntilTransactionCompletion(user.getId());
        User locked = userRepository.findByIdForUpdate(user.getId()).orElse(null);
        if (locked == null || locked.getDeletedAt() != null || !phone.equals(locked.getPhone())
                || !otpService.consume(locked, OtpPurpose.DELETE_ACCOUNT, code)) {
            throw invalidCode();
        }
        accountDeletionService.deleteAccountByVerifiedRecovery(locked.getId());
    }

    @Transactional
    public void confirmPasswordReset(String rawPhone, String code, String newPassword) {
        confirmPasswordReset(rawPhone, null, code, newPassword);
    }

    @Transactional
    public void confirmPasswordReset(String rawPhone, String rawEmail, String code, String newPassword) {
        PasswordPolicy.requireRegisterable(newPassword);
        Subject subject = subject(rawPhone, rawEmail);
        if (subject == null) {
            otpService.dummyCompare(OtpPurpose.RESET_PASSWORD, code);
            throw invalidCode();
        }
        boolean phoneBudgetAvailable = consumePhoneLimit("confirm", OtpPurpose.RESET_PASSWORD, subject.key());
        boolean accountBudgetAvailable = consumeAccountPhoneLimit("confirm", subject.key(),
                CONFIRM_ACCOUNT_CAPACITY);
        if (!phoneBudgetAvailable || !accountBudgetAvailable) {
            otpService.dummyCompare(OtpPurpose.RESET_PASSWORD, code);
            throw invalidCode();
        }
        User user = subject.find(userRepository).orElse(null);
        if (user == null) {
            otpService.dummyCompare(OtpPurpose.RESET_PASSWORD, code);
            throw invalidCode();
        }
        revocationFence.holdWriteUntilTransactionCompletion(user.getId());
        User locked = userRepository.findByIdForUpdate(user.getId()).orElse(null);
        if (locked == null || locked.getDeletedAt() != null || !subject.stillOwnedBy(locked)
                || !otpService.consume(locked, OtpPurpose.RESET_PASSWORD, code)) {
            throw invalidCode();
        }
        authService.resetPassword(locked.getId(), newPassword);
    }

    private void requestCode(String rawPhone, OtpPurpose purpose) {
        requestCode(subject(rawPhone, null), purpose);
    }

    private void requestCode(Subject subject, OtpPurpose purpose) {
        boolean budgetAvailable = true;
        if (subject != null) {
            boolean phoneBudgetAvailable = consumePhoneLimit("request", purpose, subject.key());
            boolean accountBudgetAvailable = consumeAccountPhoneLimit("request", subject.key(),
                    REQUEST_ACCOUNT_CAPACITY);
            budgetAvailable = phoneBudgetAvailable && accountBudgetAvailable;
        }
        boolean finalBudgetAvailable = budgetAvailable;
        mailDispatcher.dispatchPublicRequest(purpose,
                () -> preparePublicRequest(subject, purpose, finalBudgetAvailable),
                mail -> otpService.cancel(mail.otpId()));
    }

    private Optional<OtpMail> preparePublicRequest(Subject subject, OtpPurpose purpose, boolean budgetAvailable) {
        if (!budgetAvailable) {
            otpService.performDummyWork(purpose);
            return Optional.empty();
        }
        Optional<OtpMail> delivery = Optional.empty();
        if (subject != null && mailSender.isAvailable()) {
            User user = subject.find(userRepository).orElse(null);
            if (user != null) {
                delivery = otpService.issueForPublicRequest(user.getId(), purpose);
            }
        }
        if (delivery.isEmpty()) {
            otpService.performDummyWork(purpose);
        }
        return delivery;
    }

    /**
     * The account a public recovery request names: a verified email wins when sent,
     * otherwise the phone. Null when neither normalizes.
     */
    private Subject subject(String rawPhone, String rawEmail) {
        if (rawEmail != null && !rawEmail.isBlank()) {
            try {
                return new Subject(null, EmailOtpService.normalizeEmail(rawEmail));
            } catch (ApiException exception) {
                return null;
            }
        }
        String phone = normalizeOrNull(rawPhone);
        return phone == null ? null : new Subject(phone, null);
    }

    private record Subject(String phone, String email) {

        String key() {
            return phone != null ? phone : "email|" + email;
        }

        Optional<User> find(UserRepository users) {
            return phone != null
                    ? users.findByPhoneAndDeletedAtIsNull(phone)
                    : users.findActiveByVerifiedEmail(email);
        }

        boolean stillOwnedBy(User user) {
            return phone != null
                    ? phone.equals(user.getPhone())
                    : email.equals(user.getEmail()) && user.hasVerifiedEmail();
        }
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

    private boolean consumePhoneLimit(String action, OtpPurpose purpose, String phone) {
        long capacity = "request".equals(action) ? REQUEST_PHONE_CAPACITY : CONFIRM_PHONE_CAPACITY;
        String key = "public-otp|" + purpose.name() + "|" + action + "|phone|" + phone;
        return consumeLimit(key, capacity);
    }

    private boolean consumeAccountPhoneLimit(String action, String phone, long capacity) {
        return consumeLimit("public-otp|" + action + "|account|phone|" + phone, capacity);
    }

    private boolean consumeLimit(String key, long capacity) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(key, capacity, PHONE_PERIOD);
        return consumption.allowed();
    }

    private static ApiException invalidCode() {
        return ApiException.of(ApiErrorCode.INVALID_OR_EXPIRED_CODE, "Code is invalid or expired");
    }
}
