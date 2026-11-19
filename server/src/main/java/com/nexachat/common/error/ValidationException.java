package com.nexachat.common.error;

/**
 * A KNOWN domain-validation failure: client input rejected by a domain rule
 * (e.g. phone normalization, invite-token shape). Mapped to
 * {@code 400 VALIDATION_FAILED} by {@link GlobalExceptionHandler}.
 *
 * <p>Extends {@link IllegalArgumentException} so existing
 * {@code catch (IllegalArgumentException)} call sites keep translating it.
 * A plain {@code IllegalArgumentException} that reaches the advice is a
 * server-side bug and is answered {@code 500 INTERNAL_ERROR} — never throw a
 * raw IAE for client input.</p>
 */
public class ValidationException extends IllegalArgumentException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
