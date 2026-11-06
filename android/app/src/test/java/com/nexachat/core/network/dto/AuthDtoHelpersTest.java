package com.nexachat.core.network.dto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class AuthDtoHelpersTest {

    @Test
    public void extractsOnlyTheNestedServerErrorCode() {
        assertEquals("PHONE_EXISTS", ErrorResponse.errorCodeOf(
                "{\"error\":{\"code\":\"PHONE_EXISTS\",\"message\":\"Already registered\"}}"));
        assertNull(ErrorResponse.errorCodeOf("{\"code\":\"TOP_LEVEL\"}"));
    }

    @Test
    public void absentOrNullErrorCodeReturnsNull() {
        assertNull(ErrorResponse.errorCodeOf("{\"error\":{}}"));
        assertNull(ErrorResponse.errorCodeOf("{\"error\":null}"));
        assertNull(ErrorResponse.errorCodeOf("{\"error\":{\"code\":null}}"));
    }

    @Test
    public void emptyMalformedAndWrongShapedBodiesAreIgnored() {
        assertNull(ErrorResponse.errorCodeOf(null));
        assertNull(ErrorResponse.errorCodeOf("  \n "));
        assertNull(ErrorResponse.errorCodeOf("not json"));
        assertNull(ErrorResponse.errorCodeOf("{\"error\":[]}"));
    }
}
