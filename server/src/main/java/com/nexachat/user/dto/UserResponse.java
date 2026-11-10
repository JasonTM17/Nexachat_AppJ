package com.nexachat.user.dto;

import com.nexachat.user.User;

import java.util.UUID;

/**
 * Public profile shape — the {@code user} object in auth responses and /users/me
 * (plan §10.3). Deliberately excludes passwordHash; entities never leave the service layer.
 */
public record UserResponse(UUID id, String phone, String displayName, String avatarUrl) {

    public static UserResponse from(User user) {
        String phone = user.getDeletedAt() != null ? null : user.getPhone();
        return new UserResponse(user.getId(), phone, user.getDisplayName(), user.getAvatarUrl());
    }
}
