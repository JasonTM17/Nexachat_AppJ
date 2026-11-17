package com.nexachat.recovery;

/** Identifies the account by {@code email} (a verified address) or, for phone accounts, {@code phone}. */
public record RequestRecoveryCode(String phone, String email) {

    public RequestRecoveryCode(String phone) {
        this(phone, null);
    }
}
