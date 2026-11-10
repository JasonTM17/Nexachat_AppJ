package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Reflective Moshi bean for {@code POST /api/v1/auth/google}. */
public final class GoogleSignInRequest {
    private final String idToken;
    private final String nonce;

    public GoogleSignInRequest() {
        this(null, null);
    }

    public GoogleSignInRequest(@NonNull String idToken, @NonNull String nonce) {
        this.idToken = idToken;
        this.nonce = nonce;
    }

    @NonNull public String getIdToken() {
        return idToken;
    }

    @NonNull public String getNonce() {
        return nonce;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GoogleSignInRequest that)) {
            return false;
        }
        return Objects.equals(idToken, that.idToken) && Objects.equals(nonce, that.nonce);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idToken, nonce);
    }
}
