package com.nexachat.feature.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.nexachat.core.auth.FakeGoogleSignInClient;
import com.nexachat.core.auth.GoogleSignInException;
import com.nexachat.core.auth.GoogleSignInFlow;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.dto.LinkedIdentitiesResponse;
import com.nexachat.core.network.dto.LinkedIdentityDto;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.data.AuthRepository;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Linked-accounts state machine: load, link, unlink, error surfacing. */
public final class LinkedAccountsViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private AuthRepository repository;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        repository = mock(AuthRepository.class);
    }

    @org.junit.After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void anUnconfiguredBuildNeverLoadsAndStaysHidden() {
        LinkedAccountsViewModel viewModel = new LinkedAccountsViewModel(
                repository, new GoogleSignInFlow(repository,
                        new FakeGoogleSignInClient(false, "id-token")));

        assertFalse(viewModel.getUiState().getValue().isConfigured());
        verify(repository, never()).linkedIdentities();
    }

    @Test
    public void loadMarksTheAccountLinkedWithTheStoredEmail() {
        when(repository.linkedIdentities()).thenReturn(Single.just(new Result.Success<>(
                new LinkedIdentitiesResponse(List.of(
                        new LinkedIdentityDto("google", "user@example.com", "2026-01-01T00:00:00Z"))))));

        LinkedAccountsViewModel viewModel = configuredViewModel(new FakeGoogleSignInClient(true, "id-token"));

        assertTrue(viewModel.getUiState().getValue().isConfigured());
        assertTrue(viewModel.getUiState().getValue().isLinked());
        assertEquals("user@example.com", viewModel.getUiState().getValue().getEmail());
        assertFalse(viewModel.getUiState().getValue().isLoading());
    }

    @Test
    public void anEmptyListMeansNotLinked() {
        when(repository.linkedIdentities()).thenReturn(Single.just(
                new Result.Success<>(new LinkedIdentitiesResponse(List.of()))));

        LinkedAccountsViewModel viewModel = configuredViewModel(new FakeGoogleSignInClient(true, "id-token"));

        assertFalse(viewModel.getUiState().getValue().isLinked());
        assertNull(viewModel.getUiState().getValue().getEmail());
    }

    @Test
    public void loadFailureSurfacesTheErrorAndCanBeConsumed() {
        when(repository.linkedIdentities()).thenReturn(
                Single.just(Result.failure(new ApiError.Network("offline"))));

        LinkedAccountsViewModel viewModel = configuredViewModel(new FakeGoogleSignInClient(true, "id-token"));
        assertTrue(viewModel.getUiState().getValue().getError() instanceof ApiError.Network);

        viewModel.consumeError();

        assertNull(viewModel.getUiState().getValue().getError());
    }

    @Test
    public void aServerWithTheFeatureDisabledHidesTheRowWithoutAnError() {
        when(repository.linkedIdentities()).thenReturn(
                Single.just(Result.failure(new ApiError.Unknown("NOT_FOUND", 404))));

        LinkedAccountsViewModel viewModel = configuredViewModel(
                new FakeGoogleSignInClient(true, "id-token"));

        assertFalse(viewModel.getUiState().getValue().isConfigured());
        assertNull(viewModel.getUiState().getValue().getError());
    }

    @Test
    public void linkUsesTheNonceThenReloads() {
        when(repository.linkedIdentities())
                .thenReturn(Single.just(new Result.Success<>(new LinkedIdentitiesResponse(List.of()))))
                .thenReturn(Single.just(new Result.Success<>(new LinkedIdentitiesResponse(List.of(
                        new LinkedIdentityDto("google", "user@example.com", "2026-01-01T00:00:00Z"))))));
        when(repository.requestGoogleLinkNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        when(repository.linkGoogle("id-token", "nonce-1", "secret123")).thenReturn(
                Single.just(new Result.Success<>(Unit.INSTANCE)));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true, "id-token");
        LinkedAccountsViewModel viewModel = configuredViewModel(client);

        viewModel.link(null, "secret123");

        assertTrue(viewModel.getUiState().getValue().isLinked());
        assertEquals("nonce-1", client.lastNonce);
    }

    @Test
    public void linkConflictSurfacesTheServerCode() {
        when(repository.linkedIdentities()).thenReturn(Single.just(
                new Result.Success<>(new LinkedIdentitiesResponse(List.of()))));
        when(repository.requestGoogleLinkNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        when(repository.linkGoogle(anyString(), anyString(), anyString())).thenReturn(
                Single.just(Result.failure(new ApiError.Unknown("GOOGLE_ACCOUNT_ALREADY_LINKED"))));
        LinkedAccountsViewModel viewModel = configuredViewModel(
                new FakeGoogleSignInClient(true, "id-token"));

        viewModel.link(null, "secret123");

        assertEquals("GOOGLE_ACCOUNT_ALREADY_LINKED",
                ((ApiError.Unknown) viewModel.getUiState().getValue().getError()).getCode());
        assertFalse(viewModel.getUiState().getValue().isLinked());
    }

    @Test
    public void cancelledLinkChooserClearsLoadingWithoutShowingAnError() {
        when(repository.linkedIdentities()).thenReturn(Single.just(
                new Result.Success<>(new LinkedIdentitiesResponse(List.of()))));
        when(repository.requestGoogleLinkNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true,
                new GoogleSignInException(GoogleSignInException.Reason.CANCELLED));
        LinkedAccountsViewModel viewModel = configuredViewModel(client);

        viewModel.link(null, "secret123");

        assertFalse(viewModel.getUiState().getValue().isLoading());
        assertNull(viewModel.getUiState().getValue().getError());
        verify(repository, never()).linkGoogle(anyString(), anyString(), anyString());
    }

    @Test
    public void unlinkClearsTheLinkedState() {
        when(repository.linkedIdentities()).thenReturn(Single.just(new Result.Success<>(
                new LinkedIdentitiesResponse(List.of(
                        new LinkedIdentityDto("google", "user@example.com", "2026-01-01T00:00:00Z"))))));
        when(repository.unlinkGoogle("secret123")).thenReturn(
                Single.just(new Result.Success<>(Unit.INSTANCE)));
        LinkedAccountsViewModel viewModel = configuredViewModel(
                new FakeGoogleSignInClient(true, "id-token"));

        viewModel.unlink("secret123");

        assertFalse(viewModel.getUiState().getValue().isLinked());
        assertNull(viewModel.getUiState().getValue().getEmail());
        verify(repository).unlinkGoogle("secret123");
    }

    @Test
    public void unlinkWithoutPasswordSurfacesLastSignInMethodInline() {
        when(repository.linkedIdentities()).thenReturn(Single.just(new Result.Success<>(
                new LinkedIdentitiesResponse(List.of(
                        new LinkedIdentityDto("google", "user@example.com", "2026-01-01T00:00:00Z"))))));
        when(repository.unlinkGoogle(anyString())).thenReturn(Single.just(
                Result.failure(new ApiError.Unknown("LAST_SIGN_IN_METHOD", 409))));

        LinkedAccountsViewModel viewModel = configuredViewModel(
                new FakeGoogleSignInClient(true, "id-token"));

        viewModel.unlinkWithoutPassword();

        assertEquals("LAST_SIGN_IN_METHOD",
                ((ApiError.Unknown) viewModel.getUiState().getValue().getInlineError()).getCode());
        assertNull(viewModel.getUiState().getValue().getError());
        assertTrue(viewModel.getUiState().getValue().isLinked());
        verify(repository).unlinkGoogle(LinkedAccountsViewModel.GOOGLE_ONLY_UNLINK_PASSWORD);
    }

    @Test
    public void emptyPasswordNeverCallsTheServer() {
        when(repository.linkedIdentities()).thenReturn(Single.just(
                new Result.Success<>(new LinkedIdentitiesResponse(List.of()))));
        LinkedAccountsViewModel viewModel = configuredViewModel(
                new FakeGoogleSignInClient(true, "id-token"));

        viewModel.link(null, "");
        viewModel.unlink("");

        verify(repository, never()).requestGoogleLinkNonce();
        verify(repository, never()).unlinkGoogle(anyString());
    }

    private LinkedAccountsViewModel configuredViewModel(FakeGoogleSignInClient client) {
        return new LinkedAccountsViewModel(repository, new GoogleSignInFlow(repository, client));
    }
}
