package com.nexachat.firebase;

import com.nexachat.firebase.client.FirebaseProviderException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FirebaseFailuresTest {

    @Test
    void providerExceptionIncludesItsSanitizedMessage() {
        assertThat(FirebaseFailures.describe(
                new FirebaseProviderException("Firebase Realtime Database returned HTTP 400")))
                .isEqualTo("FirebaseProviderException: Firebase Realtime Database returned HTTP 400");
    }

    @Test
    void otherExceptionsKeepClassNameOnly() {
        assertThat(FirebaseFailures.describe(new IllegalStateException("provider response secret")))
                .isEqualTo("IllegalStateException")
                .doesNotContain("provider response secret");
    }

    @Test
    void providerExceptionWithoutMessageKeepsClassNameOnly() {
        assertThat(FirebaseFailures.describe(new FirebaseProviderException(null)))
                .isEqualTo("FirebaseProviderException");
    }
}
