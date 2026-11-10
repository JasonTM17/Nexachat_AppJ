package com.nexachat.core.auth;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.data.AuthRepository;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Single;

/**
 * Orchestrates nonce -> Credential Manager -> server exchange for both sign-in
 * and account linking. Pure RxJava: no Android view state, so it is testable
 * with a fake {@link GoogleSignInClient}.
 */
@Singleton
public class GoogleSignInFlow {

    /** Local codes consumed by the UI to choose a message or silence a cancellation. */
    public static final String CODE_CANCELLED = "GOOGLE_SIGN_IN_CANCELLED";
    public static final String CODE_NO_ACCOUNT = "GOOGLE_NO_ACCOUNT";
    public static final String CODE_UNAVAILABLE = "GOOGLE_SIGN_IN_UNAVAILABLE";

    private final AuthRepository authRepository;
    private final GoogleSignInClient client;

    @Inject
    public GoogleSignInFlow(AuthRepository authRepository, GoogleSignInClient client) {
        this.authRepository = authRepository;
        this.client = client;
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    public Single<Result<AuthResponse>> signIn(android.content.Context activityContext) {
        if (!isConfigured()) {
            return Single.just(Result.<AuthResponse>failure(unavailable()));
        }
        return authRepository.requestGoogleNonce().flatMap(nonce -> {
            String value = nonceValue(nonce);
            if (value == null) {
                return Single.just(Result.<AuthResponse>failure(errorOf(nonce)));
            }
            return client.idToken(activityContext, value)
                    .flatMap(idToken -> authRepository.googleSignIn(idToken, value))
                    .onErrorResumeNext(error -> Single.just(Result.<AuthResponse>failure(map(error))));
        });
    }

    public Single<Result<com.nexachat.core.common.Unit>> link(
            android.content.Context activityContext, String password) {
        if (!isConfigured()) {
            return Single.just(Result.<com.nexachat.core.common.Unit>failure(unavailable()));
        }
        return authRepository.requestGoogleLinkNonce().flatMap(nonce -> {
            String value = nonceValue(nonce);
            if (value == null) {
                return Single.just(Result.<com.nexachat.core.common.Unit>failure(errorOf(nonce)));
            }
            return client.idToken(activityContext, value)
                    .flatMap(idToken -> authRepository.linkGoogle(idToken, value, password))
                    .onErrorResumeNext(error -> Single.just(
                            Result.<com.nexachat.core.common.Unit>failure(map(error))));
        });
    }

    private static String nonceValue(Result<GoogleNonceResponse> nonce) {
        GoogleNonceResponse value = nonce.getOrNull();
        if (value == null || value.getNonce().isEmpty()) {
            return null;
        }
        return value.getNonce();
    }

    private static ApiError errorOf(Result<?> result) {
        return result instanceof Result.Failure<?> failure
                ? failure.getError() : new ApiError.Unknown(CODE_UNAVAILABLE);
    }

    private static ApiError map(Throwable error) {
        if (error instanceof GoogleSignInException signIn) {
            return switch (signIn.getReason()) {
                case CANCELLED -> new ApiError.Unknown(CODE_CANCELLED);
                case NO_ACCOUNT -> new ApiError.Unknown(CODE_NO_ACCOUNT);
                case UNAVAILABLE -> new ApiError.Unknown(CODE_UNAVAILABLE);
                case FAILED -> new ApiError.Unknown("GOOGLE_SIGN_IN_FAILED");
            };
        }
        return new ApiError.Network(error.getMessage());
    }

    private static ApiError unavailable() {
        return new ApiError.Unknown(CODE_UNAVAILABLE);
    }
}
