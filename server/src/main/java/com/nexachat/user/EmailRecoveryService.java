package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.mail.MailSender;
import com.nexachat.recovery.EmailOtpService;
import com.nexachat.recovery.OtpMail;
import com.nexachat.recovery.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class EmailRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(EmailRecoveryService.class);
    private final EmailOtpService otpService;
    private final MailSender mailSender;

    public EmailRecoveryService(EmailOtpService otpService, MailSender mailSender) {
        this.otpService = otpService;
        this.mailSender = mailSender;
    }

    public void requestVerification(UUID userId, String email) {
        requireMailAvailable();
        OtpMail mail = otpService.beginEmailVerification(userId, email);
        try {
            mailSender.sendOtp(mail.recipient(), mail.purpose(), mail.code());
        } catch (RuntimeException exception) {
            otpService.cancel(mail.otpId());
            log.warn("OTP mail delivery failed for purpose {}", mail.purpose());
            throw mailUnavailable();
        }
    }

    public String verify(UUID userId, String code) {
        return otpService.verifyEmail(userId, code);
    }

    public void remove(UUID userId) {
        otpService.removeEmail(userId);
    }

    private void requireMailAvailable() {
        if (!mailSender.isAvailable()) {
            throw mailUnavailable();
        }
    }

    private static ApiException mailUnavailable() {
        return ApiException.of(ApiErrorCode.MAIL_UNAVAILABLE, "Email service is unavailable");
    }
}
