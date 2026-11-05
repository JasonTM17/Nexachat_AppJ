package com.nexachat.summary;

import com.nexachat.common.dto.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SummaryController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SummaryExceptionHandler {

    @ExceptionHandler(SummaryTooFewMessagesException.class)
    public ResponseEntity<ErrorResponse> handleTooFewMessages(SummaryTooFewMessagesException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                new ErrorResponse.ErrorBody("TOO_FEW_MESSAGES", exception.getMessage(), null)));
    }
}
