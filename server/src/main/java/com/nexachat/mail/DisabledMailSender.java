package com.nexachat.mail;

public final class DisabledMailSender implements MailSender {

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public void sendOtp(OtpEmail email) {
        throw new IllegalStateException("Mail delivery is disabled");
    }
}
