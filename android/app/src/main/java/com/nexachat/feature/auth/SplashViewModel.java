package com.nexachat.feature.auth;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.AuthRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import javax.inject.Inject;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.channels.Channel;
import kotlinx.coroutines.flow.Flow;

/**
 * Session-restore logic behind SplashScreen (phase-05 M1: kill app → reopen must not
 * require login). Emits exactly one {@link SplashNavEvent}.
 */
@HiltViewModel
public final class SplashViewModel extends ViewModel {

    private final Channel<SplashNavEvent> navEventsChannel =
            SplashViewModelSupportKt.newSplashNavChannel();
    private final Flow<SplashNavEvent> navEvents =
            SplashViewModelSupportKt.splashNavEvents(navEventsChannel);

    @Inject
    public SplashViewModel(TokenStore tokenStore, AuthRepository authRepository) {
        Objects.requireNonNull(tokenStore, "tokenStore");
        Objects.requireNonNull(authRepository, "authRepository");
        TokenStore store = tokenStore;
        AuthRepository repository = authRepository;
        launch(
                (scope, continuation) ->
                        SplashViewModelSupportKt.splashEmitRestoreDestination(
                                store, repository, navEventsChannel, continuation));
    }

    public Flow<SplashNavEvent> getNavEvents() {
        return navEvents;
    }

    private void launch(Function2<CoroutineScope, Continuation<? super Unit>, Object> block) {
        BuildersKt.launch(
                ViewModelKt.getViewModelScope(this),
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                block);
    }
}
