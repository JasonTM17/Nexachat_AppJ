package com.nexachat.core.auth;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.nexachat.BuildConfig;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.Single;

/**
 * Credential Manager implementation of {@link GoogleSignInClient}.
 *
 * <p>The requested option deliberately disables the "authorized accounts" filter:
 * linking is offered to accounts that never signed in to this app, and the
 * server-side check (password + nonce + sub) is what grants the session.
 * The nonce binds the returned token to one server-issued value.
 */
@Singleton
public final class CredentialManagerGoogleSignIn implements GoogleSignInClient {

    private final Context appContext;

    @Inject
    public CredentialManagerGoogleSignIn(@ApplicationContext Context appContext) {
        this.appContext = appContext;
    }

    @Override
    public boolean isConfigured() {
        return !BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty();
    }

    @Override
    public Single<String> idToken(Context activityContext, String nonce) {
        return Single.create(emitter -> {
            if (!isConfigured()) {
                emitter.tryOnError(new GoogleSignInException(
                        GoogleSignInException.Reason.UNAVAILABLE));
                return;
            }
            GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                    .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                    .setNonce(nonce)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build();
            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build();
            CredentialManager manager = CredentialManager.create(appContext);
            manager.getCredentialAsync(
                    activityContext,
                    request,
                    null,
                    ContextCompat.getMainExecutor(appContext),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse response) {
                            if (emitter.isDisposed()) {
                                return;
                            }
                            try {
                                emitter.onSuccess(extractIdToken(response));
                            } catch (GoogleSignInException failure) {
                                emitter.tryOnError(failure);
                            }
                        }

                        @Override
                        public void onError(GetCredentialException error) {
                            if (!emitter.isDisposed()) {
                                emitter.tryOnError(map(error));
                            }
                        }
                    });
        });
    }

    private static String extractIdToken(GetCredentialResponse response)
            throws GoogleSignInException {
        Credential credential = response.getCredential();
        if (!(credential instanceof CustomCredential custom)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(
                        custom.getType())) {
            throw new GoogleSignInException(GoogleSignInException.Reason.FAILED);
        }
        Bundle data = custom.getData();
        try {
            GoogleIdTokenCredential googleCredential = GoogleIdTokenCredential.createFrom(data);
            String token = googleCredential.getIdToken();
            if (token == null || token.isBlank()) {
                throw new GoogleSignInException(GoogleSignInException.Reason.FAILED);
            }
            return token;
        } catch (RuntimeException exception) {
            // The googleid AAR does not declare its parsing failure in the JVM
            // signature; treat any parse failure as a local failure.
            throw new GoogleSignInException(GoogleSignInException.Reason.FAILED, exception);
        }
    }

    private static GoogleSignInException map(@NonNull GetCredentialException error) {
        if (error instanceof GetCredentialCancellationException) {
            return new GoogleSignInException(GoogleSignInException.Reason.CANCELLED, error);
        }
        if (error instanceof NoCredentialException) {
            return new GoogleSignInException(GoogleSignInException.Reason.NO_ACCOUNT, error);
        }
        return new GoogleSignInException(GoogleSignInException.Reason.FAILED, error);
    }
}
