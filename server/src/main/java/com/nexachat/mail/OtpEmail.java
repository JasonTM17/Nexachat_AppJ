package com.nexachat.mail;

import com.nexachat.recovery.OtpPurpose;

import java.util.Locale;

/**
 * One security-code message ready for a {@link MailSender}.
 *
 * @param locale         language of the request that asked for the code; Vietnamese or English is used
 * @param idempotencyKey stable per code so a retried send can never reach the inbox twice
 */
public record OtpEmail(String recipient, OtpPurpose purpose, String code, Locale locale, String idempotencyKey) {

    public OtpEmail {
        locale = locale == null ? Locale.ENGLISH : locale;
    }

    public OtpEmail(String recipient, OtpPurpose purpose, String code) {
        this(recipient, purpose, code, Locale.ENGLISH, null);
    }

    /** The code is a credential; keep it out of logs and exception messages. */
    @Override
    public String toString() {
        return "OtpEmail[purpose=" + purpose + ", locale=" + locale + "]";
    }
}
