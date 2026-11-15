package com.nexachat.core.auth;

/** Local Credential Manager outcome; the server never sees these reasons. */
public final class GoogleSignInException extends Exception {

    public enum Reason {
        /** The user dismissed the Credential Manager sheet. */
        CANCELLED,
        /** No Google account on the device is usable for this app. */
        NO_ACCOUNT,
        /** Credential Manager or Play Services is unavailable or misconfigured. */
        UNAVAILABLE,
        /** The provider rejected the request setup (client ID, fingerprint, or consent). */
        DEVELOPER_ERROR,
        /** The provider reported a network failure reaching Google. */
        NETWORK,
        /** The returned credential was not a parseable Google ID token. */
        FAILED
    }

    private final Reason reason;

    public GoogleSignInException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public GoogleSignInException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
