package com.nexachat.feature.auth;

import androidx.lifecycle.ViewModel;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.AuthRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import javax.inject.Inject;
import kotlinx.coroutines.channels.Channel;
import kotlinx.coroutines.flow.Flow;

/**
 * Session-restore logic behind SplashScreen. Emits exactly one
 * {@link SplashNavEvent}.
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
        SplashViewModelSupportKt.startSplashRestore(
                this, tokenStore, authRepository, navEventsChannel);
    }

    public Flow<SplashNavEvent> getNavEvents() {
        return navEvents;
    }
}
