package com.nexachat.call;

public class CallException extends RuntimeException {

    public enum Code {
        CALL_NOT_FOUND,
        CALL_STATE_INVALID,
        NOT_MEMBER,
        VALIDATION_FAILED,
        RATE_LIMITED
    }

    private final Code code;

    public CallException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    public static CallException notFound() {
        return new CallException(Code.CALL_NOT_FOUND, "Call not found");
    }

    public static CallException invalidState() {
        return new CallException(Code.CALL_STATE_INVALID, "Call action is invalid in the current state");
    }

    public static CallException validation(String message) {
        return new CallException(Code.VALIDATION_FAILED, message);
    }

    public static CallException notMember() {
        return new CallException(Code.NOT_MEMBER, "Conversation member required");
    }

    public static CallException rateLimited() {
        return new CallException(Code.RATE_LIMITED, "Call rate limit exceeded");
    }
}
