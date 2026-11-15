package com.nexachat.core.auth;

import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialCustomException;
import androidx.credentials.exceptions.GetCredentialInterruptedException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;
import androidx.credentials.exceptions.NoCredentialException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Each Credential Manager outcome lands on a distinct local reason so the UI
 * can pick an honest message instead of one generic failure.
 */
public final class CredentialManagerGoogleSignInTest {

    @Test
    public void aDismissedSheetMapsToCancelled() {
        assertEquals(GoogleSignInException.Reason.CANCELLED,
                CredentialManagerGoogleSignIn.map(
                        new GetCredentialCancellationException("dismissed"))
                        .getReason());
    }

    @Test
    public void anInterruptedSheetMapsToFailed() {
        assertEquals(GoogleSignInException.Reason.FAILED,
                CredentialManagerGoogleSignIn.map(
                        new GetCredentialInterruptedException("interrupted"))
                        .getReason());
    }

    @Test
    public void noUsableCredentialMapsToNoAccount() {
        assertEquals(GoogleSignInException.Reason.NO_ACCOUNT,
                CredentialManagerGoogleSignIn.map(
                        new NoCredentialException("none available"))
                        .getReason());
    }

    @Test
    public void aProviderConfigurationErrorMapsToDeveloperError() {
        assertEquals(GoogleSignInException.Reason.DEVELOPER_ERROR,
                CredentialManagerGoogleSignIn.map(
                        new GetCredentialProviderConfigurationException("bad setup"))
                        .getReason());
    }

    @Test
    public void anUnsupportedProviderMapsToUnavailable() {
        assertEquals(GoogleSignInException.Reason.UNAVAILABLE,
                CredentialManagerGoogleSignIn.map(
                        new GetCredentialUnsupportedException("unsupported"))
                        .getReason());
    }

    @Test
    public void aProviderNetworkTypeMapsToNetwork() {
        assertEquals(GoogleSignInException.Reason.NETWORK,
                CredentialManagerGoogleSignIn.map(new GetCredentialCustomException(
                                "androidx.credentials.provider.TYPE_NETWORK_ERROR",
                                "offline"))
                        .getReason());
    }

    @Test
    public void anUnknownProviderErrorMapsToFailed() {
        assertEquals(GoogleSignInException.Reason.FAILED,
                CredentialManagerGoogleSignIn.map(new GetCredentialCustomException(
                                "androidx.credentials.TYPE_UNKNOWN", "mystery"))
                        .getReason());
    }
}
