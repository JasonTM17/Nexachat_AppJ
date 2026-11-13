package com.nexachat.data;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.nexachat.core.common.Result;
import com.nexachat.core.media.MediaCacheCleaner;
import com.nexachat.core.network.*;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.feature.auth.LoginNotice;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.ResponseBody;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

public final class LogoutAllOutcomeTest {
    @Test public void httpFailureClearsHereAndNotifiesBeforeLogin() throws Exception {
        exercise(Response.error(503, ResponseBody.create(null, "unavailable")), null, true);
    }

    @Test public void networkFailureClearsHereAndNotifiesBeforeLogin() throws Exception {
        exercise(null, new IOException("offline"), true);
    }

    @Test public void successClearsHereWithoutExtraNotice() throws Exception {
        exercise(Response.success(204, null), null, false);
    }

    private void exercise(Response<Void> response, IOException failure, boolean unconfirmed)
            throws Exception {
        AuthApi api = mock(AuthApi.class);
        Call<Void> call = mock(Call.class);
        when(api.logoutAll()).thenReturn(call);
        if (failure != null) when(call.execute()).thenThrow(failure);
        else when(call.execute()).thenReturn(response);
        TokenStore store = mock(TokenStore.class);
        AtomicBoolean cleared = new AtomicBoolean();
        when(store.clear()).thenReturn(Completable.fromAction(() -> cleared.set(true)));
        TokenRefresher refresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager devices = mock(DeviceTokenRegistrationManager.class);
        when(devices.onLogout()).thenReturn(Completable.complete());
        AccountDataCleaner cleaner = mock(AccountDataCleaner.class);
        when(cleaner.clearForLogout(any(), eq(true)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        LoginNotice notice = new LoginNotice();
        SessionEvents events = new SessionEvents();
        AtomicReference<Integer> shown = new AtomicReference<>();
        AtomicBoolean localClearedAtNavigation = new AtomicBoolean();
        var subscription = events.getEvents().subscribe(event -> {
            localClearedAtNavigation.set(cleared.get());
            shown.set(notice.consume());
        });
        try {
            AuthRepository repository = new AuthRepository(api, mock(UserApi.class), store, events,
                    refresher, devices, cleaner, mock(MediaCacheCleaner.class),
                    new Moshi.Builder().build(), notice, mock(ServerWakeGate.class));
            assertTrue(repository.logoutAll().blockingGet() instanceof Result.Success<?>);
            assertTrue(localClearedAtNavigation.get());
            verify(refresher).onLogout();
            verify(devices).onLogout();
            verify(cleaner).clearForLogout(any(), eq(true));
            verify(store).clear();
            if (unconfirmed) assertEquals(Integer.valueOf(com.nexachat.R.string.logout_all_unconfirmed), shown.get());
            else assertNull(shown.get());
            assertNull("Notice is one-shot", notice.consume());
        } finally {
            subscription.dispose();
        }
    }
}
