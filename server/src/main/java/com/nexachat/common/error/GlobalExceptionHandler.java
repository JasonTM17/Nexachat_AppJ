package com.nexachat.common.error;

import com.nexachat.call.CallException;
import com.nexachat.common.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders every error in the uniform envelope
 * {@code {"error":{"code","message","details?"}}} (plan §10.1).
 *
 * <p>Stack traces are logged server-side but NEVER serialized to the client
 * (security table §13 — "Sensitive log"). Error codes and their HTTP status
 * come from {@link ApiErrorCode} — the single source of truth.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getHttpStatus()).body(ErrorResponse.of(ex));
    }

    @ExceptionHandler(CallException.class)
    public ResponseEntity<ErrorResponse> handleCall(CallException ex) {
        return switch (ex.getCode()) {
            case NOT_MEMBER -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                    ApiErrorCode.NOT_MEMBER, ex.getMessage()));
            case VALIDATION_FAILED -> ResponseEntity.badRequest().body(ErrorResponse.of(
                    ApiErrorCode.VALIDATION_FAILED, ex.getMessage()));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ErrorResponse.of(
                    ApiErrorCode.RATE_LIMITED, ex.getMessage()));
            case CALL_NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(new ErrorResponse.ErrorBody(
                            CallException.Code.CALL_NOT_FOUND.name(), ex.getMessage(), null)));
            case CALL_STATE_INVALID -> ResponseEntity.status(HttpStatus.CONFLICT).body(
                    new ErrorResponse(new ErrorResponse.ErrorBody(
                            CallException.Code.CALL_STATE_INVALID.name(), ex.getMessage(), null)));
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        // details = {field: first violation message}, insertion-ordered for a stable payload
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                ApiErrorCode.VALIDATION_FAILED, "Request validation failed", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, Object> violations = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v ->
                violations.put(String.valueOf(v.getPropertyPath()), v.getMessage()));
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                ApiErrorCode.VALIDATION_FAILED, "Request validation failed", violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                ApiErrorCode.VALIDATION_FAILED, "Malformed or unreadable request body"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                ApiErrorCode.VALIDATION_FAILED, "Missing request parameter: " + ex.getParameterName()));
    }

    /**
     * A multipart request that omits its required part is a client error, not
     * an internal failure. Spring raises this before the controller runs, so
     * without this mapping the generic advice answered 500.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                ApiErrorCode.VALIDATION_FAILED, "Missing request part: " + ex.getRequestPartName()));
    }

    /**
     * Malformed multipart framing (bad boundary, truncated body) is likewise a
     * client error; only the upload-size overflow has its own 413 mapping.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                ApiErrorCode.VALIDATION_FAILED, "Malformed multipart request"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                ApiErrorCode.VALIDATION_FAILED, "Invalid value for parameter: " + ex.getName()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
                ApiErrorCode.NOT_FOUND, "Resource not found"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse.of(
                ApiErrorCode.METHOD_NOT_ALLOWED, "Method not allowed"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(ErrorResponse.of(
                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of(
                ApiErrorCode.FILE_TOO_LARGE, "File exceeds the maximum allowed size"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(
                ApiErrorCode.FORBIDDEN, "Access denied"));
    }

    /**
     * Safety net for argument-level validation raised from services (e.g. phone
     * normalization). Expected-domain IllegalArgumentExceptions should normally be
     * translated to {@link ApiException} at the call site; this keeps the contract
     * if one slips through. Only the exception class is logged; its message may
     * contain user input (e.g. a rejected phone string).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("IllegalArgumentException reached advice ({})", ex.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(ErrorResponse.of(
                ApiErrorCode.VALIDATION_FAILED, "Invalid request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        // Avoid exception messages and stack traces that may contain request data or SQL.
        log.error("Unhandled request exception ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(
                ApiErrorCode.INTERNAL_ERROR, "Internal server error"));
    }
}
