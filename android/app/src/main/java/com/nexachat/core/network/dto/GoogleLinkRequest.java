package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean for {@code POST /api/v1/auth/identities/google}. */
public final class GoogleLinkRequest {
    private final String idToken;
    private final String nonce;
    private final String password;

    public GoogleLinkRequest() {
        this(null, null, null);
    }

    public GoogleLinkRequest(@NonNull String idToken, @NonNull String nonce,
                             @NonNull String password) {
        this.idToken = idToken;
        this.nonce = nonce;
        this.password = password;
    }

    @NonNull public String getIdToken() {
        return idToken;
    }

    @NonNull public String getNonce() {
        return nonce;
    }

    @NonNull public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GoogleLinkRequest that)) {
            return false;
        }
        return Objects.equals(idToken, that.idToken)
                && Objects.equals(nonce, that.nonce)
                && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idToken, nonce, password);
    }
}
