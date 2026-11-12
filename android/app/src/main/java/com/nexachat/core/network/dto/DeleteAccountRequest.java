package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/** DELETE /users/me body — password or email OTP confirmation. */
public final class DeleteAccountRequest {
    @Nullable private final String password;
    @Nullable private final String code;

    public DeleteAccountRequest(@NonNull String password) {
        this(password, null);
    }

    @NonNull
    public static DeleteAccountRequest withCode(@NonNull String code) {
        return new DeleteAccountRequest(null, Objects.requireNonNull(code, "code"));
    }

    DeleteAccountRequest(@Nullable String password, @Nullable String code) {
        this.password = password;
        this.code = code;
    }

    @Nullable
    public String getPassword() {
        return password;
    }

    @Nullable
    public String getCode() {
        return code;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DeleteAccountRequest that)) {
            return false;
        }
        return Objects.equals(password, that.password) && Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(password, code);
    }
}
