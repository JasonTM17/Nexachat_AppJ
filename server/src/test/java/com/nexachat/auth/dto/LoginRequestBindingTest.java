package com.nexachat.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class LoginRequestBindingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void legacyPhoneBodyStillBindsAndValidates() throws Exception {
        LoginRequest request = mapper.readValue("{\"phone\":\"+84912345678\",\"password\":\"secret123\"}", LoginRequest.class);
        assertThat(request.loginIdentifier()).isEqualTo("+84912345678");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void identifierBodyBindsAndWinsOverPhone() throws Exception {
        LoginRequest request = mapper.readValue(
                "{\"identifier\":\" a@b.co \",\"phone\":\"+84912345678\",\"password\":\"secret123\"}", LoginRequest.class);
        assertThat(request.loginIdentifier()).isEqualTo("a@b.co");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void bodyWithoutAnyIdentifierIsRejected() throws Exception {
        LoginRequest request = mapper.readValue("{\"password\":\"secret123\"}", LoginRequest.class);
        assertThat(validator.validate(request)).hasSize(1);
        LoginRequest blank = mapper.readValue("{\"phone\":\"  \",\"identifier\":\"\",\"password\":\"x\"}", LoginRequest.class);
        assertThat(validator.validate(blank)).hasSize(1);
    }
}