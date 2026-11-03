package com.nexachat.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime exception carrying an {@link ApiErrorCode} plus an optional detail
 * map for structured error responses.
 *
 * <p>Use {@link #of(ApiErrorCode)} for the common case and add context with
 * {@link #of(ApiErrorCode, String)} or {@link #of(ApiErrorCode, String, Map)}:</p>
 *
 * <pre>{@code
 * throw ApiException.of(ApiErrorCode.PHONE_EXISTS, "Phone number already registered");
 * }</pre>
 */
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final Map<String, Object> details;

    /**
     * Creates an exception whose message defaults to the error code string.
     *
     * @param code the error code; must not be {@code null}
     */
    public ApiException(ApiErrorCode code) {
        this(code, code.getCode());
    }

    /**
     * Creates an exception without structured details.
     *
     * @param code    the error code; must not be {@code null}
     * @param message human-readable explanation
     */
    public ApiException(ApiErrorCode code, String message) {
        this(code, message, null);
    }

    /**
     * Creates an exception with optional structured details.
     *
     * @param code    the error code; must not be {@code null}
     * @param message human-readable explanation
     * @param details optional context, copied defensively; may be {@code null}
     */
    public ApiException(ApiErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /**
     * @return the error code carried by this exception
     */
    public ApiErrorCode getCode() {
        return code;
    }

    /**
     * @return the HTTP status carried by the error code
     */
    public int getHttpStatus() {
        return code.getHttpStatus();
    }

    /**
     * @return the optional detail map; never {@code null}, empty when absent
     */
    public Map<String, Object> getDetails() {
        return details;
    }

    public static ApiException of(ApiErrorCode code) {
        return new ApiException(code);
    }

    public static ApiException of(ApiErrorCode code, String message) {
        return new ApiException(code, message);
    }

    public static ApiException of(ApiErrorCode code, Map<String, Object> details) {
        return new ApiException(code, code.getCode(), details);
    }

    public static ApiException of(ApiErrorCode code, String message, Map<String, Object> details) {
        return new ApiException(code, message, details);
    }
}
