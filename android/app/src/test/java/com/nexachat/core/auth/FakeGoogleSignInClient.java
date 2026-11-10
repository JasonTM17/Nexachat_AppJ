package com.nexachat.core.auth;

import android.content.Context;

import io.reactivex.rxjava3.core.Single;

/** JVM stand-in for Credential Manager; records the nonce it was asked for. */
public final class FakeGoogleSignInClient implements GoogleSignInClient {

    private final boolean configured;
    private final String token;
    private final GoogleSignInException error;

    public int calls;
    public String lastNonce;

    public FakeGoogleSignInClient(boolean configured, String token) {
        this(configured, token, null);
    }

    public FakeGoogleSignInClient(boolean configured, GoogleSignInException error) {
        this(configured, null, error);
    }

    private FakeGoogleSignInClient(boolean configured, String token,
                                   GoogleSignInException error) {
        this.configured = configured;
        this.token = token;
        this.error = error;
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public Single<String> idToken(Context activityContext, String nonce) {
        calls++;
        lastNonce = nonce;
        if (error != null) {
            return Single.error(error);
        }
        return Single.just(token);
    }
}
