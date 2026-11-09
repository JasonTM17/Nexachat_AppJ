package com.nexachat.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.call.CallException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

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
                .isEqualTo("CALL_NOT_FOUND");
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
}
