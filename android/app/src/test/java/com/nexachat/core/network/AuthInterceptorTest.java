package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import okhttp3.Protocol;
import okhttp3.Request;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class AuthInterceptorTest {

    @Test
    public void deferredRequestCannotUseCredentialsFromAReplacementSession() throws Exception {
        TokenStore store = mock(TokenStore.class);
        var chain = mock(okhttp3.Interceptor.Chain.class);
        Request oldUpload = new Request.Builder().url("https://example.invalid/api/v1/attachments")
                .tag(SessionBoundRequest.class, new SessionBoundRequest(1L)).build();
        when(chain.request()).thenReturn(oldUpload);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("replacement-access", 100, "refresh")));
        when(store.readTokensForSession(1L)).thenReturn(Maybe.empty());

        assertThrows(IOException.class, () -> new AuthInterceptor(store).intercept(chain));

        verify(chain, never()).proceed(any());
    }

    @Test
    public void deferredRequestUsesTheOriginatingSessionCredentials() throws Exception {
        TokenStore store = mock(TokenStore.class);
        var chain = mock(okhttp3.Interceptor.Chain.class);
        Request request = new Request.Builder().url("https://example.invalid/api/v1/attachments")
                .tag(SessionBoundRequest.class, new SessionBoundRequest(4L)).build();
        when(chain.request()).thenReturn(request);
        when(store.readTokensForSession(4L))
                .thenReturn(Maybe.just(new Tokens("matching-access", 100, "refresh")));

        new AuthInterceptor(store).intercept(chain);

        ArgumentCaptor<Request> authenticated = ArgumentCaptor.forClass(Request.class);
        verify(chain).proceed(authenticated.capture());
        assertEquals("Bearer matching-access",
                authenticated.getValue().header(AuthInterceptor.HEADER_AUTHORIZATION));
        verify(store, never()).readTokens();
    }

    @Test
    public void preservesAnExplicitBearerHeaderForAQueuedLogoutRequest() throws Exception {
        TokenStore tokenStore = mock(TokenStore.class);
        AuthInterceptor interceptor = new AuthInterceptor(tokenStore);
        Request request = new Request.Builder()
                .url("https://example.invalid/api/v1/users/device-token")
                .header(AuthInterceptor.HEADER_AUTHORIZATION, "Bearer explicit-fixture")
                .build();
        okhttp3.Interceptor.Chain chain = mock(okhttp3.Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(new okhttp3.Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .build());

        interceptor.intercept(chain);

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(chain).proceed(requestCaptor.capture());
        assertEquals("Bearer explicit-fixture",
                requestCaptor.getValue().header(AuthInterceptor.HEADER_AUTHORIZATION));
        verify(tokenStore, never()).readTokens();
    }
}
