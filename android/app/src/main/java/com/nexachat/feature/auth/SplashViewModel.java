package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;
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

        disposables.add(SplashSession.restoreDestination(
                        tokenStore.getHasValidRefresh().first(false),
                        tokenStore.hasStoredRefreshCredential(),
                        Single.defer(authRepository::refresh))
                .subscribeOn(Schedulers.io())
                .subscribe(
                        navEvents::postValue,
                        error -> navEvents.postValue(new SplashNavEvent.ToLogin(false))));
    }

    @NonNull
    public LiveData<SplashNavEvent> getNavEvents() {
        return navEvents;
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
