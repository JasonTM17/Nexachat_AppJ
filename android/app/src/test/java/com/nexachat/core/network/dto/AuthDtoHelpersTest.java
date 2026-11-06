package com.nexachat.core.network.dto;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class AuthDtoHelpersTest {

    @Test
    public void extractsOnlyTheNestedServerErrorCode() {
        assertEquals("PHONE_EXISTS", AuthDtosKt.errorCodeOf(
                "{\"error\":{\"code\":\"PHONE_EXISTS\",\"message\":\"Already registered\"}}"));
        assertNull(AuthDtosKt.errorCodeOf("{\"code\":\"TOP_LEVEL\"}"));
    }

    @Test
    public void absentOrNullErrorCodeReturnsNull() {
        assertNull(AuthDtosKt.errorCodeOf("{\"error\":{}}"));
        assertNull(AuthDtosKt.errorCodeOf("{\"error\":null}"));
        assertNull(AuthDtosKt.errorCodeOf("{\"error\":{\"code\":null}}"));
    }

    @Test
    public void emptyMalformedAndWrongShapedBodiesAreIgnored() {
        assertNull(AuthDtosKt.errorCodeOf(null));
        assertNull(AuthDtosKt.errorCodeOf("  \n "));
        assertNull(AuthDtosKt.errorCodeOf("not json"));
        assertNull(AuthDtosKt.errorCodeOf("{\"error\":[]}"));
    }
}
