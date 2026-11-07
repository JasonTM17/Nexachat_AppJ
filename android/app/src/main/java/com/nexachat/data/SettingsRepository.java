package com.nexachat.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.ApiResultKt;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.dto.UpdateUserSettingsRequest;
import com.nexachat.core.network.dto.UserSettingsDto;
import com.nexachat.core.storage.TokenStore;
import dagger.hilt.android.qualifiers.ApplicationContext;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.nexachat.core.common.Unit;

/**
 * Local preferences (theme) plus the server-backed privacy flag.
 *
 * <p>Theme is device-level and never cleared on logout. Presence visibility is
 * account-level and resets to the permissive default when the session ends.
 * Public state is exposed as read-only RxJava streams; API work uses the network
 * wrapper's IO scheduler.
 */
@Singleton
public final class SettingsRepository implements AutoCloseable {

    private static final String PREFS = "nexachat_settings";
    private static final String KEY_THEME = "theme_mode";

    private final UserApi userApi;
    private final SharedPreferences prefs;
    private final BehaviorProcessor<ThemeMode> mutableThemeMode;
    private final Flowable<ThemeMode> themeMode;
    private final BehaviorProcessor<Boolean> mutableShowPresence =
            BehaviorProcessor.createDefault(Boolean.TRUE);
    private final Flowable<Boolean> showPresence = mutableShowPresence.hide().distinctUntilChanged();
    private final CompositeDisposable subscriptions = new CompositeDisposable();

    @Inject
    public SettingsRepository(
            @ApplicationContext @NonNull Context context,
            @NonNull UserApi userApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents) {
        this.userApi = Objects.requireNonNull(userApi, "userApi");
        this.prefs = Objects.requireNonNull(context, "context")
                .getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.mutableThemeMode = BehaviorProcessor.createDefault(
                ThemeMode.fromName(prefs.getString(KEY_THEME, null)));
        this.themeMode = mutableThemeMode.hide().distinctUntilChanged();

        Disposable sessionState = tokenStore.getHasValidRefresh()
                .distinctUntilChanged()
                .subscribe(
                        hasSession -> {
                            if (hasSession) {
                                subscriptions.add(refreshShowPresence().subscribe(
                                        ignored -> { },
                                        ignored -> { }));
                            } else {
                                mutableShowPresence.onNext(Boolean.TRUE);
                            }
                        },
                        ignored -> { });
        subscriptions.add(sessionState);
        subscriptions.add(sessionEvents.getEvents().subscribe(
                event -> {
                    if (event instanceof SessionEvents.Event.LoggedOut) {
                        mutableShowPresence.onNext(Boolean.TRUE);
                    }
                },
                ignored -> { }));
    }

    @NonNull
    public Flowable<ThemeMode> getThemeMode() {
        return themeMode;
    }

    @NonNull
    public Flowable<Boolean> getShowPresence() {
        return showPresence;
    }

    public void setThemeMode(@NonNull ThemeMode mode) {
        prefs.edit().putString(KEY_THEME, mode.name()).apply();
        mutableThemeMode.onNext(mode);
    }

    @NonNull
    public Single<Result<Unit>> refreshShowPresence() {
        return Single.defer(() -> ApiResultKt.safeApiCall(userApi.getSettings())
                .map(result -> {
                    if (result instanceof ApiResult.Success<?> success) {
                        UserSettingsDto settings = (UserSettingsDto) success.getBody();
                        if (settings.getShowPresence() != null) {
                            mutableShowPresence.onNext(settings.getShowPresence());
                        }
                        return new Result.Success<Unit>(Unit.INSTANCE);
                    }
                    return Result.<Unit>cast(settingsFailure(result));
                }));
    }

    @NonNull
    public Single<Result<Unit>> setShowPresence(boolean value) {
        return Single.defer(() -> {
            boolean previous = mutableShowPresence.getValue();
            if (previous == value) {
                return Single.<Result<Unit>>just(new Result.Success<>(Unit.INSTANCE));
            }

            mutableShowPresence.onNext(value);
            return ApiResultKt.safeApiCall(
                            userApi.updateSettings(new UpdateUserSettingsRequest(value)))
                    .map(result -> {
                        if (result instanceof ApiResult.Success<?> success) {
                            UserSettingsDto settings = (UserSettingsDto) success.getBody();
                            if (settings.getShowPresence() != null) {
                                mutableShowPresence.onNext(settings.getShowPresence());
                            }
                            return new Result.Success<Unit>(Unit.INSTANCE);
                        }
                        mutableShowPresence.onNext(previous);
                        return Result.<Unit>cast(settingsFailure(result));
                    });
        });
    }

    private static Result.Failure<Unit> settingsFailure(@NonNull ApiResult<?> result) {
        if (result instanceof ApiResult.HttpError<?> httpError) {
            return new Result.Failure<>(ApiError.from(
                    httpError.getStatus(), httpError.getErrorCode()));
        }
        ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
        return new Result.Failure<>(new ApiError.Network(networkError.getCause().getMessage()));
    }

    /** Releases the app-lifetime state subscriptions; primarily useful to scoped tests. */
    @Override
    public void close() {
        subscriptions.clear();
    }
}
