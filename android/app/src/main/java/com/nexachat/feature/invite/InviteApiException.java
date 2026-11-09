package com.nexachat.feature.invite;

/** An API failure without server text, URL, or token data. */
public final class InviteApiException extends RuntimeException {
    private final InviteFailure failure;

    public InviteApiException(InviteFailure failure) {
        super(failure.name());
        this.failure = failure;
    }

    public InviteFailure getFailure() {
        return failure;
    }

    @Override
    public String toString() {
        return "InviteApiException{" + failure.name() + "}";
    }
}
