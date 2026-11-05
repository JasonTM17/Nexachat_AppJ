package com.nexachat.common.error;

/**
 * Canonical API error codes and the HTTP status each maps to.
 *
 * <p>The {@code code} string is what clients receive in
 * {@link com.nexachat.common.dto.ErrorResponse}; the HTTP status is what the
 * API returns alongside it. Codes are part of the public contract and must
 * stay stable once released.</p>
 */
public enum ApiErrorCode {

    VALIDATION_FAILED("VALIDATION_FAILED", 400),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", 401),
    TOKEN_EXPIRED("TOKEN_EXPIRED", 401),
    TOKEN_INVALID("TOKEN_INVALID", 401),
    TOKEN_REUSED("TOKEN_REUSED", 401),
    UNAUTHORIZED("UNAUTHORIZED", 401),
    WRONG_PASSWORD("WRONG_PASSWORD", 401),
    FORBIDDEN("FORBIDDEN", 403),
    PHONE_EXISTS("PHONE_EXISTS", 409),
    USER_NOT_FOUND("USER_NOT_FOUND", 404),
    ALREADY_CONTACT("ALREADY_CONTACT", 409),
    CONVERSATION_NOT_FOUND("CONVERSATION_NOT_FOUND", 404),
    NOT_MEMBER("NOT_MEMBER", 403),
    NOT_ADMIN("NOT_ADMIN", 403),
    NOT_MESSAGE_AUTHOR("NOT_MESSAGE_AUTHOR", 403),
    MESSAGE_NOT_FOUND("MESSAGE_NOT_FOUND", 404),
    MESSAGE_DELETED("MESSAGE_DELETED", 409),
    FILE_NOT_FOUND("FILE_NOT_FOUND", 404),
    ATTACHMENT_NOT_FOUND("ATTACHMENT_NOT_FOUND", 404),
    PIN_LIMIT("PIN_LIMIT", 409),
    MIME_NOT_ALLOWED("MIME_NOT_ALLOWED", 415),
    FILE_TOO_LARGE("FILE_TOO_LARGE", 413),
    RATE_LIMITED("RATE_LIMITED", 429),
    NOT_FOUND("NOT_FOUND", 404),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", 405),
    UNSUPPORTED_MEDIA_TYPE("UNSUPPORTED_MEDIA_TYPE", 415),
    INTERNAL_ERROR("INTERNAL_ERROR", 500);

    private final String code;
    private final int httpStatus;

    ApiErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /**
     * @return the stable client-facing code string
     */
    public String getCode() {
        return code;
    }

    /**
     * @return the HTTP status that accompanies this code
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
