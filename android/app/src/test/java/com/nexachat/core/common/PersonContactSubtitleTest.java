package com.nexachat.core.common;

import com.nexachat.core.network.dto.UserDto;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class PersonContactSubtitleTest {

    @Test
    public void nullEmailReturnsNothing() {
        assertNull(PersonContactSubtitle.subtitle((String) null, null));
    }

    @Test
    public void blankEmailReturnsNothing() {
        assertNull(PersonContactSubtitle.subtitle("   ", true));
    }

    @Test
    public void literalNullStringReturnsNothing() {
        assertNull(PersonContactSubtitle.subtitle("null", true));
    }

    @Test
    public void unverifiedEmailReturnsNothing() {
        assertNull(PersonContactSubtitle.subtitle("user@example.com", false));
        assertNull(PersonContactSubtitle.subtitle("user@example.com", null));
    }

    @Test
    public void verifiedEmailIsReturnedTrimmed() {
        assertEquals("user@example.com", PersonContactSubtitle.subtitle(
                "  user@example.com  ", true));
    }

    @Test
    public void userDtoDelegatesToEmailRules() {
        UserDto user = new UserDto(
                "id",
                null,
                "Name",
                null,
                "demo@gmail.com",
                true);
        assertEquals("demo@gmail.com", PersonContactSubtitle.subtitle(user));
    }
}
