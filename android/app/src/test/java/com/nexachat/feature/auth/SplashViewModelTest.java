package com.nexachat.feature.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.AuthRepository;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class SplashViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private TokenStore tokenStore;
    private AuthRepository repository;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        tokenStore = mock(TokenStore.class);
        repository = mock(AuthRepository.class);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void noStoredRefreshTokenNavigatesToLoginWithoutRefreshing() {
        when(tokenStore.getHasValidRefresh()).thenReturn(Flowable.just(false));
        when(tokenStore.hasStoredRefreshCredential()).thenReturn(Single.just(false));

        SplashViewModel viewModel = new SplashViewModel(tokenStore, repository);

        assertEquals(List.of(new SplashNavEvent.ToLogin(false)), observe(viewModel));
        verify(repository, never()).refresh();
    }

    @Test
    public void storedTokenWithSuccessfulRefreshNavigatesToInbox() {
        when(tokenStore.getHasValidRefresh()).thenReturn(Flowable.just(true));
        when(tokenStore.hasStoredRefreshCredential()).thenReturn(Single.just(true));
        when(repository.refresh()).thenReturn(Single.just(new Result.Success<>(true)));

        SplashViewModel viewModel = new SplashViewModel(tokenStore, repository);

        assertEquals(List.of(SplashNavEvent.ToInbox.INSTANCE), observe(viewModel));
    }

    @Test
    public void storedTokenWithFailedRefreshNavigatesToLogin() {
        when(tokenStore.getHasValidRefresh()).thenReturn(Flowable.just(true));
        when(tokenStore.hasStoredRefreshCredential()).thenReturn(Single.just(true));
        when(repository.refresh()).thenReturn(Single.just(
                Result.failure(ApiError.InvalidCredentials.INSTANCE)));

        SplashViewModel viewModel = new SplashViewModel(tokenStore, repository);

        assertEquals(List.of(new SplashNavEvent.ToLogin(false)), observe(viewModel));
    }

    private static List<SplashNavEvent> observe(SplashViewModel viewModel) {
        List<SplashNavEvent> events = new ArrayList<>();
        Observer<SplashNavEvent> observer = events::add;
        viewModel.getNavEvents().observeForever(observer);
        return events;
    }
}
