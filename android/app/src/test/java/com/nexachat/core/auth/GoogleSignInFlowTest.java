package com.nexachat.core.auth;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;

import org.junit.Test;

import io.reactivex.rxjava3.core.Single;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nonce -> Credential Manager -> server chain, including every short
 * circuit, without Android classes: the fake client ignores the context.
 */
public final class GoogleSignInFlowTest {

    private final AuthRepository repository = mock(AuthRepository.class);
    private final AuthResponse session = new AuthResponse(
            new UserDto("u-1", "+84912345678", "Minh", null), "access", 900, "refresh");

    @Test
    public void signInExchangesTheNonceAndToken() {
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        when(repository.googleSignIn("id-token", "nonce-1")).thenReturn(
                Single.just(new Result.Success<>(session)));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true, "id-token");
        GoogleSignInFlow flow = new GoogleSignInFlow(repository, client);

        Result<AuthResponse> result = flow.signIn(null).blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        assertEquals("nonce-1", client.lastNonce);
        assertEquals(1, client.calls);
        verify(repository).googleSignIn("id-token", "nonce-1");
    }

    @Test
    public void signInIsUnavailableWithoutACompiledClientId() {
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(false, "id-token");
        GoogleSignInFlow flow = new GoogleSignInFlow(repository, client);

        Result<AuthResponse> result = flow.signIn(null).blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        assertEquals(GoogleSignInFlow.CODE_UNAVAILABLE,
                ((ApiError.Unknown) ((Result.Failure<?>) result).getError()).getCode());
        verify(repository, never()).requestGoogleNonce();
        assertEquals(0, client.calls);
    }

    @Test
    public void aFailedNonceRequestStopsBeforeCredentialManager() {
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(Result.failure(new ApiError.Network("offline"))));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true, "id-token");
        GoogleSignInFlow flow = new GoogleSignInFlow(repository, client);

        Result<AuthResponse> result = flow.signIn(null).blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        assertTrue(((Result.Failure<?>) result).getError() instanceof ApiError.Network);
        assertEquals(0, client.calls);
        verify(repository, never()).googleSignIn(anyString(), anyString());
    }

    @Test
    public void anEmptyNonceIsRejectedBeforeCredentialManager() {
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("", 300))));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true, "id-token");
        GoogleSignInFlow flow = new GoogleSignInFlow(repository, client);

        Result<AuthResponse> result = flow.signIn(null).blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        assertEquals(0, client.calls);
    }

    @Test
    public void cancellationIsReportedWithItsOwnCode() {
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true,
                new GoogleSignInException(GoogleSignInException.Reason.CANCELLED));
        GoogleSignInFlow flow = new GoogleSignInFlow(repository, client);

        Result<AuthResponse> result = flow.signIn(null).blockingGet();

        assertEquals(GoogleSignInFlow.CODE_CANCELLED,
                ((ApiError.Unknown) ((Result.Failure<?>) result).getError()).getCode());
        verify(repository, never()).googleSignIn(anyString(), anyString());
    }

    @Test
    public void noAccountIsReportedWithItsOwnCode() {
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true,
                new GoogleSignInException(GoogleSignInException.Reason.NO_ACCOUNT));
        GoogleSignInFlow flow = new GoogleSignInFlow(repository, client);

        Result<AuthResponse> result = flow.signIn(null).blockingGet();

        assertEquals(GoogleSignInFlow.CODE_NO_ACCOUNT,
                ((ApiError.Unknown) ((Result.Failure<?>) result).getError()).getCode());
    }

    @Test
    public void linkSendsTokenNonceAndPassword() {
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-2", 300))));
        when(repository.linkGoogle("id-token", "nonce-2", "secret123")).thenReturn(
                Single.just(new Result.Success<>(Unit.INSTANCE)));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true, "id-token");
        GoogleSignInFlow flow = new GoogleSignInFlow(repository, client);

        Result<Unit> result = flow.link(null, "secret123").blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        verify(repository).linkGoogle(eq("id-token"), eq("nonce-2"), eq("secret123"));
    }

    @Test
    public void linkSurfacesTheServerError() {
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-3", 300))));
        when(repository.linkGoogle(anyString(), anyString(), anyString())).thenReturn(
                Single.just(Result.failure(new ApiError.Unknown("GOOGLE_ACCOUNT_ALREADY_LINKED"))));
        FakeGoogleSignInClient client = new FakeGoogleSignInClient(true, "id-token");
        GoogleSignInFlow flow = new GoogleSignInFlow(repository, client);

        Result<Unit> result = flow.link(null, "secret123").blockingGet();

        assertEquals("GOOGLE_ACCOUNT_ALREADY_LINKED",
                ((ApiError.Unknown) ((Result.Failure<?>) result).getError()).getCode());
    }
}
