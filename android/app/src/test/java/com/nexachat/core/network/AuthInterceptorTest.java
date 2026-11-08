package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.storage.TokenStore;
import okhttp3.Protocol;
import okhttp3.Request;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class AuthInterceptorTest {

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
