package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class GoogleNonceResponse {
    private String nonce;
    private long expiresInSeconds;

    public GoogleNonceResponse() {
        this(null, 0L);
    }

    public GoogleNonceResponse(String nonce, long expiresInSeconds) {
        this.nonce = nonce;
        this.expiresInSeconds = expiresInSeconds;
    }

    @NonNull public String getNonce() {
        return nonce == null ? "" : nonce;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }
}
