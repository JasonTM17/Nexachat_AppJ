package com.nexachat.core.auth;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.AuthSessionResult;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
    /** Server answers 404 {@code NOT_FOUND} on the Google endpoints: the feature is off there. */
    public static final String CODE_NOT_AVAILABLE = "GOOGLE_SIGN_IN_NOT_AVAILABLE";
    /** Credential Manager rejected the app's OAuth setup (fingerprint or client ID). */
    public static final String CODE_DEVELOPER_ERROR = "GOOGLE_DEVELOPER_ERROR";

    /**
     * A taken nonce must still have this much lifetime when the sheet opens:
     * the user can sit in the account chooser and the exchange round trip must
     * land before the server-side expiry. With the default 300 s TTL a
     * prefetched nonce stays usable for ~3 min of idle screen time.
     */
    static final long PREFETCH_MIN_REMAINING_MS = 120_000L;

    private final AuthRepository authRepository;
    private final GoogleSignInClient client;
    private final LongSupplier epochMillis;
    /** At most one fetched nonce waits for the next tap; it is consumed once and never reused. */
    private final AtomicReference<Single<TimedNonce>> prefetchedSignInNonce = new AtomicReference<>();

    @Inject
    public GoogleSignInFlow(AuthRepository authRepository, GoogleSignInClient client) {
        this(authRepository, client, System::currentTimeMillis);
    }

    /** Clock seam so tests can age a prefetched nonce past the reuse margin. */
    GoogleSignInFlow(AuthRepository authRepository, GoogleSignInClient client,
                     LongSupplier epochMillis) {
        this.authRepository = authRepository;
        this.client = client;
        this.epochMillis = epochMillis;
    }

    public boolean isConfigured() {
        return client.isConfigured();
    }

    /**
     * Starts the server-wait and nonce request while the sign-in screen is
     * visible, so the tap -> sheet path skips both round trips. Safe because
     * each sign-in consumes one nonce and a nonce near expiry is discarded.
     */
    public void prefetchSignInNonce() {
        if (!isConfigured()) {
            return;
        }
        Single<TimedNonce> inFlight = issueNonce().cache();
        if (prefetchedSignInNonce.compareAndSet(null, inFlight)) {
            // The cached single defers the HTTP call until subscribed; failures
            // surface to the consuming sign-in, so prefetch errors are ignored.
            inFlight.subscribe(ignored -> { }, ignored -> { });
        }
    }

    public Single<Result<AuthSessionResult>> signIn(android.content.Context activityContext) {
        if (!isConfigured()) {
            return Single.just(Result.<AuthSessionResult>failure(unavailable()));
        }
        return takeSignInNonce().flatMap(nonce -> {
            String value = nonceValue(nonce);
            if (value == null) {
                return Single.just(Result.<AuthSessionResult>failure(
                        disabledEndpointOr(errorOf(nonce))));
            }
            return client.idToken(activityContext, value)
                    .flatMap(idToken -> authRepository.googleSignIn(idToken, value))
                    .map(GoogleSignInFlow::disabledEndpointOr)
                    .onErrorResumeNext(error -> Single.just(
                            Result.<AuthSessionResult>failure(map(error))));
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
                return Single.just(Result.<com.nexachat.core.common.Unit>failure(
                        disabledEndpointOr(errorOf(nonce))));
            }
            return client.idToken(activityContext, value)
                    .flatMap(idToken -> authRepository.linkGoogle(idToken, value, password))
                    .map(GoogleSignInFlow::disabledEndpointOr)
                    .onErrorResumeNext(error -> Single.just(
                            Result.<com.nexachat.core.common.Unit>failure(map(error))));
        });
    }

    /**
     * Consumes the waiting nonce if it has at least {@link #PREFETCH_MIN_REMAINING_MS}
     * of TTL left (or it failed and must surface that error); otherwise issues
     * fresh. Each tap consumes exactly one nonce and the slot is refilled so a
     * retry never replays the previous attempt's nonce.
     */
    private Single<Result<GoogleNonceResponse>> takeSignInNonce() {
        Single<TimedNonce> waiting = prefetchedSignInNonce.getAndSet(null);
        if (waiting == null) {
            return issueNonce().map(timed -> timed.result);
        }
        // Refill early so a retry after a cancelled sheet still finds a warm nonce.
        prefetchSignInNonce();
        return waiting.flatMap(timed -> stillUsable(timed)
                ? Single.just(timed.result)
                : issueNonce().map(next -> next.result));
    }

    private Single<TimedNonce> issueNonce() {
        return authRepository.requestGoogleNonce()
                .map(result -> new TimedNonce(result, epochMillis.getAsLong()));
    }

    private boolean stillUsable(TimedNonce timed) {
        GoogleNonceResponse value = timed.result.getOrNull();
        if (value == null) {
            return true; // failures flow through unchanged
        }
        long remainingMs = value.getExpiresInSeconds() * 1000L
                - (epochMillis.getAsLong() - timed.fetchedAtMillis);
        return remainingMs >= PREFETCH_MIN_REMAINING_MS;
    }

    private static final class TimedNonce {
        final Result<GoogleNonceResponse> result;
        final long fetchedAtMillis;

        TimedNonce(Result<GoogleNonceResponse> result, long fetchedAtMillis) {
            this.result = result;
            this.fetchedAtMillis = fetchedAtMillis;
        }
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

    /**
     * The Google routes answer 404 {@code NOT_FOUND} while the server flag is
     * off; that is a feature state, not a missing resource, so it is rewritten
     * to a dedicated code before the UI picks a message.
     */
    private static <T> Result<T> disabledEndpointOr(Result<T> result) {
        if (result instanceof Result.Failure<?> failure) {
            return Result.failure(disabledEndpointOr(failure.getError()));
        }
        return result;
    }

    private static ApiError disabledEndpointOr(ApiError error) {
        if (error instanceof ApiError.Unknown unknown
                && "NOT_FOUND".equals(unknown.getCode())) {
            return new ApiError.Unknown(CODE_NOT_AVAILABLE);
        }
        return error;
    }

    private static ApiError map(Throwable error) {
        if (error instanceof GoogleSignInException signIn) {
            return switch (signIn.getReason()) {
                case CANCELLED -> new ApiError.Unknown(CODE_CANCELLED);
                case NO_ACCOUNT -> new ApiError.Unknown(CODE_NO_ACCOUNT);
                case UNAVAILABLE -> new ApiError.Unknown(CODE_UNAVAILABLE);
                case DEVELOPER_ERROR -> new ApiError.Unknown(CODE_DEVELOPER_ERROR);
                case NETWORK -> new ApiError.Network(error.getMessage());
                case FAILED -> new ApiError.Unknown("GOOGLE_SIGN_IN_FAILED");
            };
        }
        return new ApiError.Network(error.getMessage());
    }

    private static ApiError unavailable() {
        return new ApiError.Unknown(CODE_UNAVAILABLE);
    }
}
