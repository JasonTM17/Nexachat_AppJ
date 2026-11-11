package com.nexachat.core.network.dto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import org.junit.Test;

public final class UserDtoRecoveryEmailTest {
    private final Moshi moshi = new Moshi.Builder().build();

    @Test
    public void parsesEmailVerifiedFields() throws Exception {
        UserDto dto = moshi.adapter(UserDto.class).fromJson(
                "{\"id\":\"u1\",\"phone\":\"+1\",\"displayName\":\"A\","
                        + "\"email\":\"a@b.c\",\"emailVerified\":true}");

        assertTrue(dto.hasVerifiedRecoveryEmail());
    }

    @Test
    public void unverifiedEmailIsNotRecoveryReady() throws Exception {
        UserDto dto = new UserDto("u1", "+1", "A", null, "a@b.c", false);
        assertFalse(dto.hasVerifiedRecoveryEmail());
    }
}
