package com.nexachat.core.network.dto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import org.junit.Test;

public final class UserDtoPasswordSetTest {
    private final Moshi moshi = new Moshi.Builder().build();

    @Test
    public void absentPasswordSetDefaultsToTrue() throws Exception {
        UserDto dto = moshi.adapter(UserDto.class).fromJson(
                "{\"id\":\"u1\",\"displayName\":\"A\"}");
        assertTrue(dto.isPasswordSet());
    }

    @Test
    public void explicitFalseDisablesPasswordFlow() {
        UserDto dto = new UserDto("u1", null, "A", null, "a@b.c", true, false);
        assertFalse(dto.isPasswordSet());
    }

    @Test
    public void explicitTrueKeepsPasswordFlow() {
        UserDto dto = new UserDto("u1", "+1", "A", null, null, null, true);
        assertTrue(dto.isPasswordSet());
    }
}
