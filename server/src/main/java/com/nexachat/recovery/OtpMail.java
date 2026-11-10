package com.nexachat.recovery;

import java.util.UUID;

/** Short-lived plaintext code held only while handing a message to the sender. */
public record OtpMail(UUID otpId, String recipient, OtpPurpose purpose, String code) {
}
