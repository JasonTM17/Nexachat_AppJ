package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;

public final class DisabledMailSender implements MailSender {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void sendOtp(String recipient, OtpPurpose purpose, String code) {
        throw new IllegalStateException("Mail delivery is disabled");
    }
}
