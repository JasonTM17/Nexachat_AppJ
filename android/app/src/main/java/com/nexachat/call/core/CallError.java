package com.nexachat.call.core;

/** Safe, typed error surface; raw server details are intentionally not exposed to UI. */
public final class CallError {
    public enum Code {
        BUSY,
        CALL_NOT_FOUND,
        CALL_STATE_INVALID,
        NOT_MEMBER,
        RATE_LIMITED,
        VALIDATION_FAILED,
        PEER_OFFLINE,
        MEDIA_UNAVAILABLE,
        PERMISSION_DENIED,
        NETWORK,
        UNKNOWN
    }

    private final Code code;

    public CallError(Code code) {
        this.code = code == null ? Code.UNKNOWN : code;
    }

    public Code getCode() { return code; }

    public static CallError fromServerCode(String code) {
        if (code == null) return new CallError(Code.UNKNOWN);
        switch (code) {
            case "CALL_BUSY":
            case "BUSY":
                return new CallError(Code.BUSY);
            case "CALL_NOT_FOUND":
                return new CallError(Code.CALL_NOT_FOUND);
            case "CALL_STATE_INVALID":
                return new CallError(Code.CALL_STATE_INVALID);
            case "NOT_MEMBER":
                return new CallError(Code.NOT_MEMBER);
            case "RATE_LIMITED":
                return new CallError(Code.RATE_LIMITED);
            case "VALIDATION_FAILED":
                return new CallError(Code.VALIDATION_FAILED);
            default:
                return new CallError(Code.UNKNOWN);
        }
    }
}
