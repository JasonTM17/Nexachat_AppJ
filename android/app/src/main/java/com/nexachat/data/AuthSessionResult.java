package com.nexachat.data;

import androidx.annotation.NonNull;
import com.nexachat.core.network.dto.AuthResponse;
import java.util.Objects;

/** Google or password sign-in outcome after the session is stored. */
public final class AuthSessionResult {

    private final AuthResponse response;
    private final boolean newAccount;

    private AuthSessionResult(@NonNull AuthResponse response, boolean newAccount) {
        this.response = Objects.requireNonNull(response, "response");
        this.newAccount = newAccount;
    }

    @NonNull
    public static AuthSessionResult existing(@NonNull AuthResponse response) {
        return new AuthSessionResult(response, false);
    }

    @NonNull
    public static AuthSessionResult newAccount(@NonNull AuthResponse response) {
        return new AuthSessionResult(response, true);
    }

    @NonNull
    public AuthResponse getResponse() {
        return response;
    }

    public boolean isNewAccount() {
        return newAccount;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AuthSessionResult that)) {
            return false;
        }
        return newAccount == that.newAccount && response.equals(that.response);
    }

    @Override
    public int hashCode() {
        return Objects.hash(response, newAccount);
    }
}
