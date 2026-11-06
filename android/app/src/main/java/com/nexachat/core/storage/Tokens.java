package com.nexachat.core.storage;

import java.util.Objects;

/** Decrypted session tokens loaded from encrypted DataStore preferences. */
public final class Tokens {

    private final String accessToken;
    private final long accessTokenExpiresAtEpochMs;
    private final String refreshToken;

    public Tokens(String accessToken, long accessTokenExpiresAtEpochMs, String refreshToken) {
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.accessTokenExpiresAtEpochMs = accessTokenExpiresAtEpochMs;
        this.refreshToken = Objects.requireNonNull(refreshToken, "refreshToken");
    }

    public String getAccessToken() {
        return accessToken;
    }

    public long getAccessTokenExpiresAtEpochMs() {
        return accessTokenExpiresAtEpochMs;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public boolean isAccessExpired(long nowEpochMs) {
        return nowEpochMs >= accessTokenExpiresAtEpochMs;
    }

    public boolean isAccessExpired() {
        return isAccessExpired(System.currentTimeMillis());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Tokens tokens)) {
            return false;
        }
        return accessTokenExpiresAtEpochMs == tokens.accessTokenExpiresAtEpochMs
                && accessToken.equals(tokens.accessToken)
                && refreshToken.equals(tokens.refreshToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accessToken, accessTokenExpiresAtEpochMs, refreshToken);
    }

    @Override
    public String toString() {
        return "Tokens(accessToken=***, accessTokenExpiresAtEpochMs="
                + accessTokenExpiresAtEpochMs
                + ", refreshToken=***)";
    }
}
