package com.nexachat.firebase;

import com.nexachat.firebase.client.FirebaseProviderException;

/** Log-safe failure descriptions; only sanitized provider messages are surfaced. */
public final class FirebaseFailures {

    private FirebaseFailures() {
    }

    public static String describe(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        if (failure instanceof FirebaseProviderException && failure.getMessage() != null) {
            return name + ": " + failure.getMessage();
        }
        return name;
    }
}
