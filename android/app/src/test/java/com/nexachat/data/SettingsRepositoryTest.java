package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.dto.UpdateUserSettingsRequest;
import com.nexachat.core.network.dto.UserSettingsDto;
import com.nexachat.core.storage.TokenStore;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Theme persistence (device-level) and the optimistic presence toggle
 * (account-level, reverted on failure, reset on logout).
 */
public final class SettingsRepositoryTest {

    private final UserApi userApi = mock(UserApi.class);
    private final TokenStore tokenStore = mock(TokenStore.class);
    private final SessionEvents sessionEvents = mock(SessionEvents.class);
    private final PublishProcessor<SessionEvents.Event> sessionSignals = PublishProcessor.create();
    private final BehaviorProcessor<Boolean> hasSession = BehaviorProcessor.createDefault(false);

    private SettingsRepository repository;

    @After
    public void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    private void build(Map<String, String> store) {
        when(tokenStore.getHasValidRefresh()).thenReturn(hasSession.hide());
        when(sessionEvents.getEvents()).thenReturn(sessionSignals);
        repository = new SettingsRepository(fakeContext(store), userApi, tokenStore, sessionEvents);
    }

    @Test
    public void themeDefaultsToSystemAndPersistsSelectedMode() {
        Map<String, String> store = new HashMap<>();
        build(store);
        assertEquals(ThemeMode.SYSTEM, repository.getThemeMode().blockingFirst());

        repository.setThemeMode(ThemeMode.DARK);

        assertEquals(ThemeMode.DARK, repository.getThemeMode().blockingFirst());
        assertEquals("DARK", store.get("theme_mode"));

        repository.setThemeMode(ThemeMode.LIGHT);
        assertEquals("LIGHT", store.get("theme_mode"));
    }

    @Test
    public void themeIsRestoredFromLastStoredValue() {
        build(new HashMap<>(Map.of("theme_mode", "DARK")));

        assertEquals(ThemeMode.DARK, repository.getThemeMode().blockingFirst());
    }

    @Test
    public void setShowPresenceAppliesOptimisticallyThenAcceptsServerValue() throws Exception {
        build(new HashMap<>());
        Call<UserSettingsDto> updateCall = mock(Call.class);
        when(userApi.updateSettings(new UpdateUserSettingsRequest(false))).thenReturn(updateCall);
        when(updateCall.execute()).thenReturn(Response.success(new UserSettingsDto(false)));

        Result<?> result = await(repository.setShowPresence(false).test());

        assertTrue(result instanceof Result.Success<?>);
        assertEquals(false, repository.getShowPresence().blockingFirst());
        verify(userApi, times(1)).updateSettings(new UpdateUserSettingsRequest(false));
    }

    @Test
    public void rejectedPresenceToggleRevertsToPreviousValue() throws Exception {
        build(new HashMap<>());
        Call<UserSettingsDto> updateCall = mock(Call.class);
        when(userApi.updateSettings(any(UpdateUserSettingsRequest.class))).thenReturn(updateCall);
        when(updateCall.execute()).thenReturn(Response.error(
                429, okhttp3.ResponseBody.create(null, "")));

        Result<?> result = await(repository.setShowPresence(false).test());

        assertTrue(result instanceof Result.Failure<?>);
        assertEquals(true, repository.getShowPresence().blockingFirst());
    }

    @Test
    public void refreshShowPresenceLoadsServerFlagOnceSessionExists() throws Exception {
        build(new HashMap<>());
        Call<UserSettingsDto> settingsCall = mock(Call.class);
        when(userApi.getSettings()).thenReturn(settingsCall);
        when(settingsCall.execute()).thenReturn(Response.success(new UserSettingsDto(false)));
        TestObserver<Boolean> becameHidden = repository.getShowPresence()
                .filter(value -> !value)
                .firstOrError()
                .test();

        hasSession.onNext(true);

        becameHidden.awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertValue(false);
        verify(userApi, times(1)).getSettings();
    }

    @Test
    public void logoutResetsPrivacyFlagToPermissiveDefault() throws Exception {
        build(new HashMap<>());
        Call<UserSettingsDto> settingsCall = mock(Call.class);
        when(userApi.getSettings()).thenReturn(settingsCall);
        when(settingsCall.execute()).thenReturn(Response.success(new UserSettingsDto(false)));
        await(repository.refreshShowPresence().test());
        assertEquals(false, repository.getShowPresence().blockingFirst());

        sessionSignals.onNext(SessionEvents.Event.LoggedOut.INSTANCE);

        assertEquals(true, repository.getShowPresence().blockingFirst());
    }

    @Test
    public void setShowSeenAppliesOptimisticallyThenAcceptsServerValue() throws Exception {
        build(new HashMap<>());
        Call<UserSettingsDto> updateCall = mock(Call.class);
        when(userApi.updateSettings(new UpdateUserSettingsRequest(null, false))).thenReturn(updateCall);
        when(updateCall.execute()).thenReturn(Response.success(new UserSettingsDto(true, false)));

        Result<?> result = await(repository.setShowSeen(false).test());

        assertTrue(result instanceof Result.Success<?>);
        assertEquals(false, repository.getShowSeen().blockingFirst());
        verify(userApi, times(1)).updateSettings(new UpdateUserSettingsRequest(null, false));
    }

    @Test
    public void rejectedSeenToggleRevertsToPreviousValue() throws Exception {
        build(new HashMap<>());
        Call<UserSettingsDto> updateCall = mock(Call.class);
        when(userApi.updateSettings(any(UpdateUserSettingsRequest.class))).thenReturn(updateCall);
        when(updateCall.execute()).thenReturn(Response.error(
                429, okhttp3.ResponseBody.create(null, "")));

        Result<?> result = await(repository.setShowSeen(false).test());

        assertTrue(result instanceof Result.Failure<?>);
        assertEquals(true, repository.getShowSeen().blockingFirst());
    }

    @Test
    public void refreshShowPresenceLoadsServerSeenFlagOnceSessionExists() throws Exception {
        build(new HashMap<>());
        Call<UserSettingsDto> settingsCall = mock(Call.class);
        when(userApi.getSettings()).thenReturn(settingsCall);
        when(settingsCall.execute()).thenReturn(Response.success(new UserSettingsDto(true, false)));
        TestObserver<Boolean> becameHidden = repository.getShowSeen()
                .filter(value -> !value)
                .firstOrError()
                .test();

        hasSession.onNext(true);

        becameHidden.awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertValue(false);
        verify(userApi, times(1)).getSettings();
    }

    @Test
    public void logoutResetsSeenPrivacyFlagToPermissiveDefault() throws Exception {
        build(new HashMap<>());
        Call<UserSettingsDto> settingsCall = mock(Call.class);
        when(userApi.getSettings()).thenReturn(settingsCall);
        when(settingsCall.execute()).thenReturn(Response.success(new UserSettingsDto(true, false)));
        await(repository.refreshShowPresence().test());
        assertEquals(false, repository.getShowSeen().blockingFirst());

        sessionSignals.onNext(SessionEvents.Event.LoggedOut.INSTANCE);

        assertEquals(true, repository.getShowSeen().blockingFirst());
    }

    private Context fakeContext(Map<String, String> store) {
        SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        when(editor.putString(anyString(), nullable(String.class))).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return editor;
        });
        doNothing().when(editor).apply();

        SharedPreferences prefs = mock(SharedPreferences.class);
        when(prefs.getString(anyString(), nullable(String.class))).thenAnswer(invocation -> {
            String stored = store.get(invocation.getArgument(0));
            return stored != null ? stored : invocation.getArgument(1);
        });
        when(prefs.edit()).thenReturn(editor);

        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(prefs);
        return context;
    }

    private static Result<?> await(TestObserver<? extends Result<?>> observer) {
        observer.awaitDone(5, TimeUnit.SECONDS).assertNoErrors();
        return observer.values().get(0);
    }
}
