package com.nexachat.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.call.CallException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void callParticipantErrorsMapToStructuredHttpStatuses() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        assertThat(handler.handleCall(CallException.notMember()).getStatusCode().value()).isEqualTo(403);
        assertThat(handler.handleCall(CallException.notMember()).getBody().error().code())
                .isEqualTo(ApiErrorCode.NOT_MEMBER.getCode());
        assertThat(handler.handleCall(CallException.notFound()).getStatusCode().value()).isEqualTo(404);
        assertThat(handler.handleCall(CallException.notFound()).getBody().error().code())
                .isEqualTo(ApiErrorCode.CALL_NOT_FOUND.getCode());
        assertThat(handler.handleCall(CallException.invalidState()).getStatusCode().value()).isEqualTo(409);
        assertThat(handler.handleCall(CallException.invalidState()).getBody().error().code())
                .isEqualTo(ApiErrorCode.CALL_STATE_INVALID.getCode());
    }

    @Test
    void rangeNotSatisfiableKeeps416ContentRangeAndEnvelope() {
        ResponseEntity<com.nexachat.common.dto.ErrorResponse> response =
                new GlobalExceptionHandler().handleRangeNotSatisfiable(
                        new com.nexachat.attachment.RangeNotSatisfiableException(10, "Requested range not satisfiable"));

        assertThat(response.getStatusCode().value()).isEqualTo(416);
        assertThat(response.getHeaders().getFirst("Content-Range")).isEqualTo("bytes */10");
        assertThat(response.getHeaders().getFirst("Accept-Ranges")).isEqualTo("bytes");
        assertThat(response.getBody().error().code())
                .isEqualTo(ApiErrorCode.RANGE_NOT_SATISFIABLE.getCode());
    }

    @Test
    void knownDomainValidationMapsTo400ButPlainIllegalArgumentFallsThroughTo500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<com.nexachat.common.dto.ErrorResponse> known =
                handler.handleValidation(new ValidationException("bad phone"));
        assertThat(known.getStatusCode().value()).isEqualTo(400);
        assertThat(known.getBody().error().code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED.getCode());

        // An unexpected IAE is a server bug: the generic advice answers 500.
        ResponseEntity<com.nexachat.common.dto.ErrorResponse> unexpected =
                handler.handleGeneric(new IllegalArgumentException("server bug"));
        assertThat(unexpected.getStatusCode().value()).isEqualTo(500);
        assertThat(unexpected.getBody().error().code()).isEqualTo(ApiErrorCode.INTERNAL_ERROR.getCode());
    }

    @Test
    void genericErrorJsonDoesNotExposeExceptionOrStackDetails() throws Exception {
        ResponseEntity<com.nexachat.common.dto.ErrorResponse> response =
                new GlobalExceptionHandler().handleGeneric(new IllegalStateException());
        String body = new ObjectMapper().writeValueAsString(response.getBody());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(body).contains("INTERNAL_ERROR", "Internal server error")
                .doesNotContain("IllegalStateException", "stackTrace", "trace");
    }

    @Test
    void missingMultipartPartIsAClientError() {
        ResponseEntity<com.nexachat.common.dto.ErrorResponse> response =
                new GlobalExceptionHandler().handleMissingPart(
                        new MissingServletRequestPartException("file"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().error().code())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED.getCode());
        assertThat(response.getBody().error().message()).contains("file");
    }

    @Test
    void malformedMultipartFramingIsAClientError() {
        ResponseEntity<com.nexachat.common.dto.ErrorResponse> response =
                new GlobalExceptionHandler().handleMultipart(
                        new MultipartException("bad boundary"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().error().code())
                .isEqualTo(ApiErrorCode.VALIDATION_FAILED.getCode());
        assertThat(response.getBody().error().message())
                .doesNotContain("bad boundary");
    }
}
