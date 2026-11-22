package com.nexachat.firebase.auth;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.auth.oauth2.GoogleCredentials;
import com.nexachat.firebase.client.FirebaseProviderException;

import java.util.Map;

/** Firebase Admin SDK adapter; custom-token signing is local to the service account key. */
public final class FirebaseAdminAuthClient implements FirebaseAdminClient, AutoCloseable {

    private static final String APP_NAME = "nexachat-ephemeral-state";

    private final FirebaseApp app;
    private final FirebaseAuth auth;

    public FirebaseAdminAuthClient(GoogleCredentials credentials,
                                   String projectId,
                                   String databaseUrl,
                                   int connectTimeoutMillis,
                                   int readTimeoutMillis) {
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(projectId)
                .setDatabaseUrl(databaseUrl)
                .setConnectTimeout(connectTimeoutMillis)
                .setReadTimeout(readTimeoutMillis)
                .setWriteTimeout(readTimeoutMillis)
                .build();
        FirebaseApp firebaseApp;
        try {
            firebaseApp = FirebaseApp.initializeApp(options, APP_NAME);
        } catch (IllegalStateException ex) {
            throw new IllegalStateException("Firebase Admin app could not be initialized");
        }
        this.app = firebaseApp;
        this.auth = FirebaseAuth.getInstance(firebaseApp);
    }

    FirebaseAdminAuthClient(FirebaseApp app, FirebaseAuth auth) {
        this.app = app;
        this.auth = auth;
    }

    @Override
    public String createCustomToken(String uid, Map<String, Object> additionalClaims) {
        try {
            return auth.createCustomToken(uid, Map.copyOf(additionalClaims));
        } catch (FirebaseAuthException ex) {
            throw new FirebaseProviderException("Firebase custom-token signing failed");
        }
    }

    @Override
    public void revokeRefreshTokens(String uid) {
        try {
            auth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException ex) {
            if (ex.getAuthErrorCode() == AuthErrorCode.USER_NOT_FOUND) {
                return;
            }
            throw new FirebaseProviderException("Firebase Auth session revocation failed");
        }
    }

    @Override
    public void close() {
        app.delete();
    }
}
