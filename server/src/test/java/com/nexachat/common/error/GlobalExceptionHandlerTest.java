package com.nexachat.common.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

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
