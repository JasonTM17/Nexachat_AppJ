package com.nexachat.summary;

import com.nexachat.common.dto.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = SummaryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SummaryExceptionHandler {

    @ExceptionHandler(SummaryTooFewMessagesException.class)
    public ResponseEntity<ErrorResponse> handleTooFewMessages(SummaryTooFewMessagesException exception) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (exception.scope() != null) {
            details.put("scope", exception.scope().name());
        }
        details.put("available", exception.available());
        details.put("required", exception.required());
        if (exception.period() != null) {
            details.put("period", exception.period());
        }
        return ResponseEntity.badRequest().body(new ErrorResponse(
                new ErrorResponse.ErrorBody("TOO_FEW_MESSAGES", exception.getMessage(), details)));
    }

    @ExceptionHandler(SummaryInvalidTimeZoneException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTimeZone(SummaryInvalidTimeZoneException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                new ErrorResponse.ErrorBody("INVALID_TIME_ZONE", exception.getMessage(), null)));
    }
}
