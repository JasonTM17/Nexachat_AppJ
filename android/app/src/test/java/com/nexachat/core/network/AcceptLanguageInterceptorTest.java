package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class AcceptLanguageInterceptorTest {
    private Locale defaultLocale;

    @Before
    public void keepLocale() {
        defaultLocale = Locale.getDefault();
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    @Test
    public void sendsTheDefaultLocaleAsTheAcceptLanguage() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("vi-VN"));
        Request request = new Request.Builder()
                .url("https://example.invalid/api/v1/auth/register")
                .build();
        Interceptor.Chain chain = chainReturning(request);

        new AcceptLanguageInterceptor().intercept(chain);

        assertEquals("vi-VN",
                proceededRequest(chain).header(AcceptLanguageInterceptor.HEADER_ACCEPT_LANGUAGE));
    }

    @Test
    public void keepsAnExistingAcceptLanguageHeader() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("vi-VN"));
        Request request = new Request.Builder()
                .url("https://example.invalid/api/v1/users/me")
                .header(AcceptLanguageInterceptor.HEADER_ACCEPT_LANGUAGE, "fr-FR")
                .build();
        Interceptor.Chain chain = chainReturning(request);

        new AcceptLanguageInterceptor().intercept(chain);

        assertEquals("fr-FR",
                proceededRequest(chain).header(AcceptLanguageInterceptor.HEADER_ACCEPT_LANGUAGE));
    }

    @Test
    public void fallsBackToEnglishWhenTheLocaleIsUndefined() throws Exception {
        Locale.setDefault(Locale.ROOT);
        Request request = new Request.Builder()
                .url("https://example.invalid/api/v1/auth/forgot-password")
                .build();
        Interceptor.Chain chain = chainReturning(request);

        new AcceptLanguageInterceptor().intercept(chain);

        assertEquals("en",
                proceededRequest(chain).header(AcceptLanguageInterceptor.HEADER_ACCEPT_LANGUAGE));
    }

    private static Interceptor.Chain chainReturning(Request request) throws Exception {
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .build());
        return chain;
    }

    private static Request proceededRequest(Interceptor.Chain chain) throws Exception {
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(chain).proceed(requestCaptor.capture());
        return requestCaptor.getValue();
    }
}
