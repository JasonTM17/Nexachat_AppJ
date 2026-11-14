package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.PhoneNormalizer;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.WhitespaceTrim;
import com.nexachat.core.media.MediaCacheCleaner;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.ServerWakeGate;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.network.TokenRefreshOutcome;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.ChangePasswordRequest;
import com.nexachat.core.network.dto.DeleteAccountRequest;
import com.nexachat.core.network.dto.GoogleLinkRequest;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.core.network.dto.GoogleSignInRequest;
import com.nexachat.core.network.dto.GoogleUnlinkRequest;
import com.nexachat.core.network.dto.LinkedIdentitiesResponse;
import com.nexachat.core.network.dto.LoginRequest;
import com.nexachat.core.network.dto.LogoutRequest;
import com.nexachat.core.network.dto.RegisterRequest;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.feature.auth.LoginNotice;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import com.nexachat.notification.MessageNotifications;
import com.nexachat.notification.ReminderNotifications;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.nexachat.core.common.Unit;
import retrofit2.Call;

/**
 * Auth boundary for the rest of the app: speaks Retrofit to {@code /api/v1/auth/…},
 * persists sessions in {@link TokenStore}, and translates the server error envelope
 * into {@link ApiError}. Phone numbers are normalized to E.164 (region VN)
 * best-effort — the server re-validates anyway, so a parse failure sends raw.
 *
 * <p>Network and token-store work runs on RxJava's IO scheduler. The returned
 * {@link Single}s are cold, so callers own the subscription and its disposal.
 */
@Singleton
public final class AuthRepository {

    // SessionEvents and this repository share the application singleton lifetime.
    private final CompositeDisposable notificationObservers = new CompositeDisposable();

    private final AuthApi authApi;
    @Nullable private final UserApi userApi;
    private final TokenStore tokenStore;
    private final SessionEvents sessionEvents;
    private final TokenRefresher tokenRefresher;
    private final DeviceTokenRegistrationManager deviceTokenRegistrationManager;
    @Nullable private final AccountDataCleaner accountDataCleaner;
    @Nullable private final MediaCacheCleaner mediaCacheCleaner;
    @Nullable private final Moshi moshi;
    @Nullable private final LoginNotice loginNotice;
    @Nullable private final ServerWakeGate serverWakeGate;
    @Nullable private final com.nexachat.core.crypto.E2eeSession e2eeSession;

    /** Test seam without E2EE; production wiring uses the injected constructor. */
    public AuthRepository(
            @NonNull AuthApi authApi,
            @NonNull UserApi userApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull DeviceTokenRegistrationManager deviceTokenRegistrationManager,
            @NonNull AccountDataCleaner accountDataCleaner,
            @NonNull MediaCacheCleaner mediaCacheCleaner,
            @NonNull Moshi moshi,
            @NonNull LoginNotice loginNotice,
            @NonNull ServerWakeGate serverWakeGate) {
        this(authApi, userApi, tokenStore, sessionEvents, tokenRefresher,
                deviceTokenRegistrationManager, accountDataCleaner, mediaCacheCleaner,
                moshi, loginNotice, serverWakeGate, null);
    }

    @Inject
    public AuthRepository(
            @NonNull AuthApi authApi,
            @NonNull UserApi userApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull DeviceTokenRegistrationManager deviceTokenRegistrationManager,
            @NonNull AccountDataCleaner accountDataCleaner,
            @NonNull MediaCacheCleaner mediaCacheCleaner,
            @NonNull Moshi moshi,
            @NonNull LoginNotice loginNotice,
            @NonNull ServerWakeGate serverWakeGate,
            @Nullable com.nexachat.core.crypto.E2eeSession e2eeSession) {
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.userApi = Objects.requireNonNull(userApi, "userApi");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.sessionEvents = Objects.requireNonNull(sessionEvents, "sessionEvents");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
        this.deviceTokenRegistrationManager = Objects.requireNonNull(
                deviceTokenRegistrationManager, "deviceTokenRegistrationManager");
        this.accountDataCleaner = Objects.requireNonNull(accountDataCleaner, "accountDataCleaner");
        this.mediaCacheCleaner = Objects.requireNonNull(mediaCacheCleaner, "mediaCacheCleaner");
        this.moshi = Objects.requireNonNull(moshi, "moshi");
        this.loginNotice = Objects.requireNonNull(loginNotice, "loginNotice");
        this.serverWakeGate = Objects.requireNonNull(serverWakeGate, "serverWakeGate");
        this.e2eeSession = e2eeSession;
    }

    /** Preserves the narrow constructor used by local auth repository tests. */
    public AuthRepository(
            @NonNull AuthApi authApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull DeviceTokenRegistrationManager deviceTokenRegistrationManager,
            @NonNull AccountDataCleaner accountDataCleaner) {
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.userApi = null;
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.sessionEvents = Objects.requireNonNull(sessionEvents, "sessionEvents");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
        this.deviceTokenRegistrationManager = Objects.requireNonNull(
                deviceTokenRegistrationManager, "deviceTokenRegistrationManager");
        this.accountDataCleaner = Objects.requireNonNull(accountDataCleaner, "accountDataCleaner");
        this.mediaCacheCleaner = null;
        this.moshi = null;
        this.loginNotice = null;
        this.serverWakeGate = null;
        this.e2eeSession = null;
    }

    /** Focused constructor for tests that exercise the readiness gate before login. */
    public AuthRepository(
            @NonNull AuthApi authApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull DeviceTokenRegistrationManager deviceTokenRegistrationManager,
            @Nullable AccountDataCleaner accountDataCleaner,
            @NonNull ServerWakeGate serverWakeGate) {
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.userApi = null;
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.sessionEvents = Objects.requireNonNull(sessionEvents, "sessionEvents");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
        this.deviceTokenRegistrationManager = Objects.requireNonNull(
                deviceTokenRegistrationManager, "deviceTokenRegistrationManager");
        this.accountDataCleaner = accountDataCleaner;
        this.mediaCacheCleaner = null;
        this.moshi = null;
        this.loginNotice = null;
        this.serverWakeGate = Objects.requireNonNull(serverWakeGate, "serverWakeGate");
        this.e2eeSession = null;
    }

    /** Preserves the narrow constructor used by local auth repository tests. */
    public AuthRepository(
            @NonNull AuthApi authApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull DeviceTokenRegistrationManager deviceTokenRegistrationManager,
            @NonNull AccountDataCleaner accountDataCleaner,
            @NonNull Moshi moshi) {
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.userApi = null;
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.sessionEvents = Objects.requireNonNull(sessionEvents, "sessionEvents");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
        this.deviceTokenRegistrationManager = Objects.requireNonNull(
                deviceTokenRegistrationManager, "deviceTokenRegistrationManager");
        this.accountDataCleaner = Objects.requireNonNull(accountDataCleaner, "accountDataCleaner");
        this.mediaCacheCleaner = null;
        this.moshi = Objects.requireNonNull(moshi, "moshi");
        this.loginNotice = null;
        this.serverWakeGate = null;
        this.e2eeSession = null;
    }

    AuthRepository(
            @NonNull AuthApi authApi,
            @NonNull UserApi userApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull DeviceTokenRegistrationManager deviceTokenRegistrationManager,
            @NonNull AccountDataCleaner accountDataCleaner,
            @NonNull LoginNotice loginNotice) {
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.userApi = Objects.requireNonNull(userApi, "userApi");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.sessionEvents = Objects.requireNonNull(sessionEvents, "sessionEvents");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
        this.deviceTokenRegistrationManager = Objects.requireNonNull(
                deviceTokenRegistrationManager, "deviceTokenRegistrationManager");
        this.accountDataCleaner = Objects.requireNonNull(accountDataCleaner, "accountDataCleaner");
        this.mediaCacheCleaner = null;
        this.moshi = null;
        this.loginNotice = Objects.requireNonNull(loginNotice, "loginNotice");
        this.serverWakeGate = null;
        this.e2eeSession = null;
    }

    /** Preserves the narrow constructor used by local auth repository tests. */
    public AuthRepository(
            @NonNull AuthApi authApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull TokenRefresher tokenRefresher,
            @NonNull DeviceTokenRegistrationManager deviceTokenRegistrationManager) {
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.userApi = null;
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.sessionEvents = Objects.requireNonNull(sessionEvents, "sessionEvents");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
        this.deviceTokenRegistrationManager = Objects.requireNonNull(
                deviceTokenRegistrationManager, "deviceTokenRegistrationManager");
        this.accountDataCleaner = null;
        this.mediaCacheCleaner = null;
        this.moshi = null;
        this.loginNotice = null;
        this.serverWakeGate = null;
        this.e2eeSession = null;
    }

    /** Hilt also installs this at splash restore, including after a process restart. */
    @Inject
    void observeSessionNotifications(MessageNotifications messages, ReminderNotifications reminders) {
        notificationObservers.add(Flowable.merge(
                        sessionEvents.getEvents(), sessionEvents.getLifecycleEvents())
                .filter(event -> event instanceof SessionEvents.Event.SessionClosing
                        || event instanceof SessionEvents.Event.LoggedOut
                        || event instanceof SessionEvents.Event.ReauthenticationRequired)
                .subscribe(event -> {
                    messages.dismissForSessionEnd();
                    reminders.dismissForSessionEnd();
                }));
    }

    @NonNull
    public Single<Result<AuthResponse>> register(
            @NonNull String phone,
            @NonNull String password,
            @NonNull String displayName) {
        RegisterRequest request = registerRequest(phone, password, displayName);
        return Single.defer(() -> persistOnSuccess(authCall(authApi.register(request))));
    }

    @NonNull
    public Single<Result<AuthResponse>> login(
            @NonNull String phone,
            @NonNull String password) {
        LoginRequest request = loginRequest(phone, password);
        return Single.defer(() -> withServerReady(
                () -> persistOnSuccess(authCall(authApi.login(request)))));
    }

    @NonNull
    public Flowable<Boolean> getServerWakeState() {
        return serverWakeGate == null ? Flowable.just(false) : serverWakeGate.getIsStarting();
    }

    @NonNull
    private Single<Boolean> awaitServerReady() {
        return serverWakeGate == null ? Single.just(true) : serverWakeGate.awaitReady();
    }

    private <T> Single<Result<T>> withServerReady(Supplier<Single<Result<T>>> request) {
        return awaitServerReady().flatMap(ready -> ready
                ? Single.defer(request::get)
                : Single.just(Result.<T>failure(new ApiError.Network("server is not ready"))));
    }

    /**
     * Rotates the refresh token via {@link TokenRefresher} — the SAME mutex the
     * OkHttp authenticator uses, so splash-restore can never race a 401-triggered
     * rotation into a TOKEN_REUSED family revocation.
     */
    @NonNull
    public Single<Result<Boolean>> refresh() {
        return Single.defer(() -> bindLegacyOutboxToStoredSession()
                .andThen(Single.defer(tokenRefresher::refresh)).flatMap(outcome -> {
                    Result<Boolean> result = refreshMapping(outcome);
                    if (outcome instanceof TokenRefreshOutcome.FreshToken
                            || outcome instanceof TokenRefreshOutcome.TransientFailure) {
                        if (accountDataCleaner != null) accountDataCleaner.scheduleOutbox();
                        return deviceTokenRegistrationManager.onSessionAvailable()
                                .onErrorComplete()
                                .andThen(e2eeSession == null ? Completable.complete()
                                        : e2eeSession.onSessionAvailable().onErrorComplete())
                                .andThen(Single.just(result));
                    }
                    return Single.just(result);
                }))
                .subscribeOn(Schedulers.io());
    }

    private Completable bindLegacyOutboxToStoredSession() {
        if (accountDataCleaner == null || moshi == null) return Completable.complete();
        return tokenStore.readTokens().map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMapCompletable(stored -> {
                    if (stored.isEmpty()) {
                        return accountDataCleaner.sealUnassignedLegacyOutboxOwners();
                    }
                    String ownerUserId = TimeAndJwt.userIdFromJwt(
                            stored.get().getAccessToken(), moshi);
                    return ownerUserId == null || ownerUserId.isBlank()
                            ? accountDataCleaner.sealUnassignedLegacyOutboxOwners()
                            : accountDataCleaner.assignLegacyOutboxOwner(ownerUserId);
                });
    }

    /**
     * Best-effort server-side revocation; the local session is always cleared
     * and a {@link SessionEvents.Event.LoggedOut} is broadcast (drives global
     * nav → Login).
     */
    @NonNull
    public Single<Result<Unit>> logout() {
        return Single.<Result<Unit>>defer(() -> tokenStore.readTokens()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(tokens -> {
                    Completable revoke = tokens.isEmpty()
                            ? Completable.complete()
                            : ApiResult.safeApiCall(authApi.logout(
                                    logoutRequest(tokens.get().getRefreshToken())))
                                    .ignoreElement().onErrorComplete();
                    Completable revokeDevice = tokens.isEmpty() || e2eeSession == null
                            ? Completable.complete()
                            : Maybe.fromCallable(() -> TimeAndJwt.userIdFromJwt(
                                            tokens.get().getAccessToken(), moshi))
                                    .flatMapCompletable(userId ->
                                            e2eeSession.revokeCurrentDevice(userId))
                                    .onErrorComplete();
                    return Completable.fromAction(tokenRefresher::onLogout)
                            .andThen(revokeDevice)
                            .andThen(clearAndFinishLogout(deviceTokenRegistrationManager.onLogout()
                                    .onErrorComplete()
                                    .andThen(revoke)
                                    .andThen(tokenStore.clear())
                                    .andThen(Completable.fromAction(sessionEvents::emitLoggedOut))))
                            .andThen(Single.<Result<Unit>>fromCallable(
                                    () -> new Result.Success<>(Unit.INSTANCE)));
                }))
                .subscribeOn(Schedulers.io());
    }

    /**
     * FR-A4 "logout-all": revokes every refresh token server-side, then the
     * same local clear + global nav as {@link #logout}. Bearer-auth, so no body.
     */
    @NonNull
    public Single<Result<Unit>> logoutAll() {
        return Single.defer(() -> Completable.fromAction(tokenRefresher::onLogout)
                        .andThen(clearAndFinishLogout(deviceTokenRegistrationManager.onLogout()
                                .onErrorComplete()
                                .andThen(ApiResult.safeApiCall(authApi.logoutAll())
                                        .doOnSuccess(result -> {
                                            if (!(result instanceof ApiResult.Success<?>)) {
                                                markRemoteRevocationUnconfirmed();
                                            }
                                        })
                                        .doOnError(ignored -> markRemoteRevocationUnconfirmed())
                                        .ignoreElement().onErrorComplete())
                                .andThen(tokenStore.clear())
                                .andThen(Completable.fromAction(sessionEvents::emitLoggedOut)), true))
                        .andThen(Single.<Result<Unit>>fromCallable(
                                () -> new Result.Success<>(Unit.INSTANCE)))
                )
                .subscribeOn(Schedulers.io());
    }

    private void markRemoteRevocationUnconfirmed() {
        if (loginNotice != null) loginNotice.setRemoteRevocationUnconfirmed();
    }

    /**
     * Permanently deletes the signed-in account after password confirmation; on
     * success clears the local session like {@link #logout()} and queues a
     * one-time login notice.
     */
    @NonNull
    public Single<Result<Unit>> deleteAccount(@NonNull String password) {
        if (userApi == null) {
            return Single.just(Result.failure(new ApiError.Unknown("NO_USER_API")));
        }
        return Single.defer(() -> authCallUnit(userApi.deleteMe(deleteAccountRequest(password)))
                .flatMap(result -> result instanceof Result.Success<?>
                        ? finalizeAccountDeletion()
                        : Single.just(result)))
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    public Single<Result<Unit>> requestAccountDeletionCode() {
        if (userApi == null) {
            return Single.just(Result.failure(new ApiError.Unknown("NO_USER_API")));
        }
        return authCallUnit(userApi.requestAccountDeletionCode());
    }

    @NonNull
    public Single<Result<Unit>> deleteAccountWithCode(@NonNull String code) {
        if (userApi == null) {
            return Single.just(Result.failure(new ApiError.Unknown("NO_USER_API")));
        }
        return Single.defer(() -> authCallUnit(userApi.deleteMe(DeleteAccountRequest.withCode(code)))
                .flatMap(result -> result instanceof Result.Success<?>
                        ? finalizeAccountDeletion()
                        : Single.just(result)))
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    private Single<Result<Unit>> finalizeAccountDeletion() {
        return clearPendingDeviceUnregisterResidue()
                .andThen(Completable.fromAction(tokenRefresher::onLogout))
                .andThen(clearAndFinishLogout(deviceTokenRegistrationManager.onLogout()
                        .onErrorComplete(), true)
                        .onErrorComplete())
                .andThen(clearMediaCaches())
                .andThen(tokenStore.clear().onErrorComplete())
                .andThen(Completable.fromAction(this::markAccountDeletedNotice))
                .andThen(Completable.fromAction(sessionEvents::emitLoggedOut))
                .onErrorComplete()
                .andThen(Single.fromCallable(() -> new Result.Success<>(Unit.INSTANCE)));
    }

    @NonNull
    private Completable clearPendingDeviceUnregisterResidue() {
        return tokenStore.readPendingDeviceTokenUnregister()
                .flatMapCompletable(pending -> tokenStore.clearPendingDeviceTokenUnregister(
                        pending.getToken(), pending.getOwnerUserId()))
                .onErrorComplete();
    }

    @NonNull
    private Completable clearMediaCaches() {
        return mediaCacheCleaner == null
                ? Completable.complete()
                : mediaCacheCleaner.clearForAccountDeletion().onErrorComplete();
    }

    private void markAccountDeletedNotice() {
        if (loginNotice != null) {
            loginNotice.setAccountDeleted();
        }
    }

    @NonNull
    public Single<Result<Unit>> changePassword(
            @NonNull String oldPassword,
            @NonNull String newPassword) {
        return tokenStore.readTokens()
                .map(tokens -> Optional.of(tokens.getRefreshToken()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(keepToken -> authCallUnit(authApi.changePassword(
                        changePasswordRequest(oldPassword, newPassword, keepToken.orElse(null)))))
                .subscribeOn(Schedulers.io());
    }

    /** Fetches a single-use nonce before a Google credential request. */
    @NonNull
    public Single<Result<GoogleNonceResponse>> requestGoogleNonce() {
        return Single.defer(() -> withServerReady(() -> ApiResult.safeApiCall(authApi.googleNonce())
                .map(AuthRepository::valueResult)
                .subscribeOn(Schedulers.io())));
    }

    /** Fetches the nonce used by the authenticated link flow. */
    @NonNull
    public Single<Result<GoogleNonceResponse>> requestGoogleLinkNonce() {
        return Single.defer(() -> ApiResult.safeApiCall(authApi.googleLinkNonce())
                        .map(AuthRepository::valueResult)
                        .subscribeOn(Schedulers.io()));
    }

    /** Exchanges a Google ID token for a session; persists it exactly like a password login. */
    @NonNull
    public Single<Result<AuthSessionResult>> googleSignIn(
            @NonNull String idToken,
            @NonNull String nonce) {
        return Single.defer(() -> withServerReady(() -> persistSessionResult(
                googleAuthCall(authApi.googleSignIn(new GoogleSignInRequest(idToken, nonce))))));
    }

    @NonNull
    private Single<Result<AuthSessionResult>> googleAuthCall(@NonNull Call<AuthResponse> call) {
        return ApiResult.safeApiCall(call)
                .map(result -> {
                    if (result instanceof ApiResult.Success<?> success) {
                        AuthResponse body = (AuthResponse) success.getBody();
                        if (body == null) {
                            return Result.<AuthSessionResult>failure(
                                    new ApiError.Unknown("EMPTY_AUTH_RESPONSE"));
                        }
                        boolean created = success.getStatusCode() == 201 || body.isNewAccount();
                        return new Result.Success<>(created
                                ? AuthSessionResult.newAccount(body)
                                : AuthSessionResult.existing(body));
                    }
                    return Result.<AuthSessionResult>failure(errorOf(result));
                })
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    private Single<Result<AuthSessionResult>> persistSessionResult(
            Single<Result<AuthSessionResult>> request) {
        final long attemptBoundary = currentBoundary();
        return request.flatMap(result -> {
            if (!(result instanceof Result.Success<?> success)) {
                return Single.just(result);
            }
            AuthSessionResult session = (AuthSessionResult) success.getValue();
            return persistOnSuccess(Single.just(new Result.Success<>(session.getResponse())), attemptBoundary)
                    .flatMap(persisted -> {
                        if (persisted instanceof Result.Success<?>) {
                            return Single.just(new Result.Success<>(session));
                        }
                        ApiError error = ((Result.Failure<?>) persisted).getError();
                        return Single.just(Result.<AuthSessionResult>failure(error));
                    });
        }).subscribeOn(Schedulers.io());
    }

    /** Links a Google account; the server verifies the password and the nonce. */
    @NonNull
    public Single<Result<Unit>> linkGoogle(
            @NonNull String idToken,
            @NonNull String nonce,
            @NonNull String password) {
        return Single.defer(() -> authCallUnit(
                authApi.linkGoogle(new GoogleLinkRequest(idToken, nonce, password))));
    }

    @NonNull
    public Single<Result<Unit>> unlinkGoogle(@NonNull String password) {
        return Single.defer(() -> authCallUnit(authApi.unlinkGoogle(
                new GoogleUnlinkRequest(password))));
    }

    @NonNull
    public Single<Result<LinkedIdentitiesResponse>> linkedIdentities() {
        return Single.defer(() -> ApiResult.safeApiCall(authApi.linkedIdentities())
                .map(AuthRepository::valueResult)
                .subscribeOn(Schedulers.io()));
    }

    @NonNull
    private Single<Result<AuthResponse>> persistOnSuccess(
            Single<Result<AuthResponse>> request) {
        return persistOnSuccess(request, currentBoundary());
    }

    /** Boundary read when the sign-in starts, so a late response cannot replace a newer session. */
    private long currentBoundary() {
        return accountDataCleaner == null ? -1L : accountDataCleaner.sessionBoundary();
    }

    @NonNull
    private Single<Result<AuthResponse>> persistOnSuccess(
            Single<Result<AuthResponse>> request, long attemptBoundary) {
        return request.flatMap(result -> {
            if (!(result instanceof Result.Success<?> success)) {
                return Single.just(result);
            }
            AuthResponse response = (AuthResponse) success.getValue();
            return bindLegacyOutboxToStoredSession().andThen(Single.defer(() -> {
                RecallWindowConfig recall = new RecallWindowConfig(
                        response.getRecallWindowSeconds(),
                        response.getGroupAdminMayRecallAfterWindow());
                Completable saveSession = tokenStore.establishNewSession(
                                response.getAccessToken(),
                                System.currentTimeMillis()
                                        + response.getAccessTokenExpiresIn() * 1_000L,
                                response.getRefreshToken(),
                                recall)
                        .doOnSuccess(tokenRefresher::onSessionEstablished)
                        .ignoreElement();
                Completable establishSession = accountDataCleaner == null
                        ? saveSession.andThen(Completable.fromAction(sessionEvents::emitSessionAvailable))
                        : accountDataCleaner.startSession(saveSession,
                                Completable.fromAction(sessionEvents::emitSessionAvailable),
                                attemptBoundary);
                return establishSession
                        .andThen(deviceTokenRegistrationManager.onSessionAvailable().onErrorComplete())
                        .andThen(e2eeSession == null ? Completable.complete()
                                : e2eeSession.onSessionAvailable().onErrorComplete())
                        .andThen(Single.just(result));
            }));
        }).subscribeOn(Schedulers.io());
    }

    private Completable clearAndFinishLogout(Completable finishBoundary) {
        return clearAndFinishLogout(finishBoundary, false);
    }

    private Completable clearAndFinishLogout(Completable finishBoundary, boolean clearOutbox) {
        Completable wipe = e2eeSession == null
                ? Completable.complete()
                : Completable.fromAction(e2eeSession::wipeCurrent);
        return accountDataCleaner == null
                ? wipe.andThen(finishBoundary)
                : accountDataCleaner.clearForLogout(wipe.andThen(finishBoundary), clearOutbox);
    }

    @NonNull
    private Single<Result<AuthResponse>> authCall(@NonNull Call<AuthResponse> call) {
        return ApiResult.safeApiCall(call)
                .map(AuthRepository::authResult)
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    private Single<Result<Unit>> authCallUnit(@NonNull Call<Void> call) {
        return ApiResult.safeApiCall(call)
                .map(result -> {
                    if (result instanceof ApiResult.Success<?>) {
                        // Do not read the erased empty body from a successful 204 response.
                        return new Result.Success<Unit>(Unit.INSTANCE);
                    }
                    return Result.<Unit>cast(authResultFailure(result));
                })
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    private static Result<AuthResponse> authResult(@NonNull ApiResult<AuthResponse> result) {
        return valueResult(result);
    }

    @NonNull
    private static Result.Failure<AuthResponse> authResultFailure(
            @NonNull ApiResult<?> result) {
        return new Result.Failure<>(errorOf(result));
    }

    @SuppressWarnings("unchecked")
    private static <T> Result<T> valueResult(@NonNull ApiResult<T> result) {
        if (result instanceof ApiResult.Success<?> success) {
            return new Result.Success<>((T) success.getBody());
        }
        return new Result.Failure<>(errorOf(result));
    }

    @NonNull
    private static ApiError errorOf(@NonNull ApiResult<?> result) {
        if (result instanceof ApiResult.HttpError<?> httpError) {
            return ApiError.from(httpError.getStatus(), httpError.getErrorCode());
        }
        ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
        return new ApiError.Network(networkError.getCause().getMessage());
    }

    @NonNull
    RegisterRequest registerRequest(
            @NonNull String phone,
            @NonNull String password,
            @NonNull String displayName) {
        return new RegisterRequest(
                PhoneNormalizer.toE164(phone), password, WhitespaceTrim.trim(displayName));
    }

    @NonNull
    LoginRequest loginRequest(@NonNull String identifier, @NonNull String password) {
        String trimmed = identifier.trim();
        if (trimmed.contains("@")) {
            return LoginRequest.byEmail(trimmed.toLowerCase(java.util.Locale.ROOT), password);
        }
        return new LoginRequest(PhoneNormalizer.toE164(identifier), password);
    }

    @NonNull
    LogoutRequest logoutRequest(@NonNull String refreshToken) {
        return new LogoutRequest(refreshToken);
    }

    /**
     * Passing the live refresh token keeps THIS session; the server revokes
     * every OTHER device instead of kicking the user out right after.
     */
    @NonNull
    ChangePasswordRequest changePasswordRequest(
            @NonNull String oldPassword,
            @NonNull String newPassword,
            @Nullable String keepToken) {
        return new ChangePasswordRequest(oldPassword, newPassword, keepToken);
    }

    @NonNull
    DeleteAccountRequest deleteAccountRequest(@NonNull String password) {
        return new DeleteAccountRequest(password);
    }

    /**
     * Splash-restore mapping: {@code Success} = refreshed (the refresher already
     * persisted and handled rejection side-effects); {@link ApiError.Network} for
     * transient failures (caller may keep the session) vs any other error for a
     * dead session (caller should drop to Login).
     */
    @NonNull
    Result<Boolean> refreshMapping(@NonNull TokenRefreshOutcome outcome) {
        if (outcome instanceof TokenRefreshOutcome.FreshToken) {
            return new Result.Success<>(Boolean.TRUE);
        }
        if (outcome instanceof TokenRefreshOutcome.RecoveryRequired) {
            return Result.failure(ApiError.SessionExpired.INSTANCE);
        }
        if (outcome instanceof TokenRefreshOutcome.TransientFailure) {
            return Result.failure(new ApiError.Network("refresh request failed"));
        }
        if (outcome instanceof TokenRefreshOutcome.Rejected) {
            return Result.failure(new ApiError.Unknown("SESSION_REJECTED"));
        }
        return Result.failure(new ApiError.Unknown("NO_REFRESH_TOKEN"));
    }
}
