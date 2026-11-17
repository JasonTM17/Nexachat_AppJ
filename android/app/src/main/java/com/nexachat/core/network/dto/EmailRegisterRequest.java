package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** {@code POST /api/v1/auth/register/email}: the mailed code plus the new account's details. */
public final class EmailRegisterRequest {
    private final String email;
    private final String code;
    private final String password;
    private final String displayName;

    public EmailRegisterRequest() {
        this(null, null, null, null);
    }

    public EmailRegisterRequest(
            @NonNull String email,
            @NonNull String code,
            @NonNull String password,
            @NonNull String displayName) {
        this.email = email;
        this.code = code;
        this.password = password;
        this.displayName = displayName;
    }

    @NonNull public String getEmail() {
        return email;
    }

    @NonNull public String getCode() {
        return code;
    }

    @NonNull public String getPassword() {
        return password;
    }

    @NonNull public String getDisplayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EmailRegisterRequest that)) {
            return false;
        }
        return Objects.equals(email, that.email)
                && Objects.equals(code, that.code)
                && Objects.equals(password, that.password)
                && Objects.equals(displayName, that.displayName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(email, code, password, displayName);
    }
}
