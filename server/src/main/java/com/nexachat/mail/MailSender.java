package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;

public interface MailSender {

    boolean isAvailable();

    void sendOtp(String recipient, OtpPurpose purpose, String code);
}
