package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** DELETE /users/me body — current password confirmation. */
public final class DeleteAccountRequest {
    private final String password;

    public DeleteAccountRequest(@NonNull String password) {
        this.password = password;
    }

    @NonNull
    public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DeleteAccountRequest that)) {
            return false;
        }
        return Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(password);
    }
}
