package com.nexachat.feature.invite;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class InvitePrivacyTest {
    private static final String TOKEN = "A".repeat(43);

    @Test
    public void inviteClientRemovesHttpLoggersAndKeepsAuthenticatedInterceptors() {
        Interceptor authInterceptor = chain -> chain.proceed(chain.request());
        HttpLoggingInterceptor logger = new HttpLoggingInterceptor(ignored -> { });
        OkHttpClient source = new OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .addInterceptor(logger)
                .addNetworkInterceptor(logger)
                .build();

        OkHttpClient inviteClient = InviteModule.withoutHttpLogging(source);

        assertTrue(inviteClient.interceptors().contains(authInterceptor));
        assertFalse(inviteClient.interceptors().stream()
                .anyMatch(HttpLoggingInterceptor.class::isInstance));
        assertFalse(inviteClient.networkInterceptors().stream()
                .anyMatch(HttpLoggingInterceptor.class::isInstance));
    }

    @Test
    public void inviteDiagnosticsNeverIncludeRawToken() {
        InviteLinkResponse link = FakeInviteRepository.link(TOKEN, true);
        InviteApiException error = new InviteApiException(InviteFailure.UNAVAILABLE);
        InviteLinkParser.ParsedInvite parsed = InviteLinkParser.parse(
                "https://nexachat.app/join/" + TOKEN).orElseThrow();

        assertFalse(link.toString().contains(TOKEN));
        assertFalse(error.toString().contains(TOKEN));
        assertFalse(parsed.toString().contains(TOKEN));
    }
}
