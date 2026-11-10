package com.nexachat.core.auth;

import android.content.Context;

import io.reactivex.rxjava3.core.Single;

/**
 * Thin seam over Google Credential Manager so the sign-in flow can be tested
 * on the JVM and the Activity context stays a call argument, never a field.
 */
public interface GoogleSignInClient {

    /** True when a web client ID was compiled in; false hides every Google entry point. */
    boolean isConfigured();

    /**
     * Shows the Credential Manager UI and returns the Google ID token.
     * Emits {@link GoogleSignInException} for cancellation, no account, or failure.
     */
    Single<String> idToken(Context activityContext, String nonce);
}
