package com.nexachat.notification;

public record PushResult(
        boolean success,
        String messageId,
        String errorCode,
        boolean unregistered
) {
    public static PushResult success(String messageId) {
        return new PushResult(true, messageId, null, false);
    }

    public static PushResult failure(String errorCode, boolean unregistered) {
        return new PushResult(false, null, errorCode, unregistered);
    }

    public static PushResult unregistered(String errorCode) {
        return new PushResult(false, null, errorCode, true);
    }
}
