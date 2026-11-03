package com.nexachat.common.dto;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.util.Map;
import java.util.Objects;

/**
 * Standard API error envelope: {@code {"error": {"code", "message", "details"}}}.
 *
 * <pre>{@code
 * {
 *   "error": {
 *     "code": "PHONE_EXISTS",
 *     "message": "Phone number already registered",
 *     "details": { "phone": "+84901234567" }
 *   }
 * }
 * }</pre>
 */
public record ErrorResponse(ErrorBody error) {

    public ErrorResponse {
        Objects.requireNonNull(error, "error must not be null");
    }

    /**
     * Body of the error envelope.
     *
     * @param code    stable machine-readable code from {@link ApiErrorCode}
     * @param message human-readable explanation
     * @param details optional structured context; may be {@code null}
     */
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ErrorBody(String code, String message, Map<String, Object> details) {

        public ErrorBody {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    public static ErrorResponse of(ApiErrorCode code, String message) {
        return of(code, message, null);
    }

    public static ErrorResponse of(ApiErrorCode code, String message, Map<String, Object> details) {
        Objects.requireNonNull(code, "code must not be null");
        return new ErrorResponse(new ErrorBody(code.getCode(), message, details));
    }

    /**
     * Builds a response from a raised {@link ApiException}.
     *
     * @param exception the exception to project; must not be {@code null}
     * @return the matching error envelope
     */
    public static ErrorResponse of(ApiException exception) {
        Objects.requireNonNull(exception, "exception must not be null");
        return of(exception.getCode(), exception.getMessage(), exception.getDetails());
    }
}
