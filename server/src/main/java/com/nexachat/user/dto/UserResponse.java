package com.nexachat.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexachat.user.User;

import java.util.UUID;

/**
 * Public profile shape — the {@code user} object in auth responses and /users/me
 * (plan §10.3). Deliberately excludes passwordHash; entities never leave the service layer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(UUID id, String phone, String displayName, String avatarUrl) {

    public static UserResponse from(User user) {
        return forSearchResult(user, true);
    }

    /** {@code includePhone} false omits the number from display-name discovery hits. */
    public static UserResponse forSearchResult(User user, boolean includePhone) {
        String phone = includePhone ? user.getPhone() : null;
        return new UserResponse(user.getId(), phone, user.getDisplayName(), user.getAvatarUrl());
    }
}
