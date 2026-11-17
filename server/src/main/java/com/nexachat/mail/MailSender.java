package com.nexachat.mail;

public interface MailSender {

    boolean isAvailable();

    /** Delivers the message or throws; a thrown error makes the caller withdraw the code. */
    void sendOtp(OtpEmail email);
}
