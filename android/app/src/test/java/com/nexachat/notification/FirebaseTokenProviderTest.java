package com.nexachat.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import org.junit.Test;

public final class FirebaseTokenProviderTest {
    private static final String TOKEN = "fcm-token-fixture";

    @Test
    public void returnsNullWithoutFetchingWhenFirebaseIsNotConfigured() throws Exception {
        FirebaseTokenProvider.TokenSource tokenSource = mock(FirebaseTokenProvider.TokenSource.class);
        FirebaseTokenProvider provider = new FirebaseTokenProvider(() -> false, tokenSource);

        assertNull(provider.getTokenIfConfigured());

        verify(tokenSource, never()).fetchToken();
    }

    @Test
    public void returnsTokenWhenFirebaseIsConfigured() throws Exception {
        FirebaseTokenProvider.TokenSource tokenSource = mock(FirebaseTokenProvider.TokenSource.class);
        when(tokenSource.fetchToken()).thenReturn(TOKEN);
        FirebaseTokenProvider provider = new FirebaseTokenProvider(() -> true, tokenSource);

        assertEquals(TOKEN, provider.getTokenIfConfigured());
    }

    @Test
    public void blankTokenIsReportedAsAbsent() throws Exception {
        FirebaseTokenProvider.TokenSource tokenSource = mock(FirebaseTokenProvider.TokenSource.class);
        when(tokenSource.fetchToken()).thenReturn("   ");
        FirebaseTokenProvider provider = new FirebaseTokenProvider(() -> true, tokenSource);

        assertNull(provider.getTokenIfConfigured());
    }

    @Test
    public void fetchFailurePropagatesForRetry() throws Exception {
        FirebaseTokenProvider.TokenSource tokenSource = mock(FirebaseTokenProvider.TokenSource.class);
        when(tokenSource.fetchToken()).thenThrow(new IOException("no play services"));
        FirebaseTokenProvider provider = new FirebaseTokenProvider(() -> true, tokenSource);

        assertThrows(IOException.class, provider::getTokenIfConfigured);
    }

    @Test
    public void configurationFailurePropagatesWithoutFetching() throws Exception {
        FirebaseTokenProvider.FirebaseConfigProbe probe = mock(FirebaseTokenProvider.FirebaseConfigProbe.class);
        when(probe.isDefaultAppConfigured()).thenThrow(new IllegalStateException("firebase runtime absent"));
        FirebaseTokenProvider.TokenSource tokenSource = mock(FirebaseTokenProvider.TokenSource.class);
        FirebaseTokenProvider provider = new FirebaseTokenProvider(probe, tokenSource);

        assertThrows(IllegalStateException.class, provider::getTokenIfConfigured);

        verify(tokenSource, never()).fetchToken();
    }

    @Test
    public void isConfiguredMirrorsTheProbe() {
        FirebaseTokenProvider.TokenSource tokenSource = mock(FirebaseTokenProvider.TokenSource.class);
        FirebaseTokenProvider configured = new FirebaseTokenProvider(() -> true, tokenSource);
        FirebaseTokenProvider absent = new FirebaseTokenProvider(() -> false, tokenSource);

        assertTrue(configured.isConfigured());
        assertFalse(absent.isConfigured());
    }

    @Test
    public void configurationFailurePropagatesFromIsConfigured() {
        FirebaseTokenProvider.FirebaseConfigProbe probe = mock(FirebaseTokenProvider.FirebaseConfigProbe.class);
        when(probe.isDefaultAppConfigured()).thenThrow(new IllegalStateException("firebase runtime absent"));
        FirebaseTokenProvider provider = new FirebaseTokenProvider(
                probe, mock(FirebaseTokenProvider.TokenSource.class));

        assertThrows(IllegalStateException.class, provider::isConfigured);
    }

    @Test
    public void tokenIsNotWrittenToStandardStreams() throws Exception {
        FirebaseTokenProvider.TokenSource tokenSource = mock(FirebaseTokenProvider.TokenSource.class);
        when(tokenSource.fetchToken()).thenReturn(TOKEN);
        FirebaseTokenProvider provider = new FirebaseTokenProvider(() -> true, tokenSource);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));
            provider.getTokenIfConfigured();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertFalse(out.toString().contains(TOKEN));
        assertFalse(err.toString().contains(TOKEN));
    }
}
