package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.AuthRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import javax.inject.Inject;

/** Session restore behind SplashScreen; emits exactly one navigation destination. */
@HiltViewModel
public final class SplashViewModel extends ViewModel {

    private final CompositeDisposable disposables = new CompositeDisposable();
    private final OneShotLiveData<SplashNavEvent> navEvents = new OneShotLiveData<>();

    @Inject
    public SplashViewModel(TokenStore tokenStore, AuthRepository authRepository) {
        Objects.requireNonNull(tokenStore, "tokenStore");
        Objects.requireNonNull(authRepository, "authRepository");

        disposables.add(tokenStore.getHasValidRefresh()
                .first(false)
                .subscribeOn(Schedulers.io())
                .flatMap(hasRefresh -> hasRefresh
                        ? authRepository.refresh().map(SplashViewModel::destinationFor)
                        : Single.just(SplashNavEvent.ToLogin.INSTANCE))
                .subscribe(
                        navEvents::postValue,
                        error -> navEvents.postValue(SplashNavEvent.ToLogin.INSTANCE)));
    }

    @NonNull
    public LiveData<SplashNavEvent> getNavEvents() {
        return navEvents;
    }

    private static SplashNavEvent destinationFor(Result<Boolean> result) {
        if (result instanceof Result.Success<?>) {
            return SplashNavEvent.ToInbox.INSTANCE;
        }
        ApiError error = ((Result.Failure<?>) result).getError();
        return error instanceof ApiError.Network
                ? SplashNavEvent.ToInbox.INSTANCE
                : SplashNavEvent.ToLogin.INSTANCE;
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
