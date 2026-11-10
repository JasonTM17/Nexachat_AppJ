package com.nexachat.firebase.client;

/** Sanitized provider failure; response bodies and credentials are never retained. */
public final class FirebaseProviderException extends RuntimeException {

    public FirebaseProviderException(String message) {
        super(message);
    }
}
