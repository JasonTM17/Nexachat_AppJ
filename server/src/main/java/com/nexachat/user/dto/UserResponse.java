package com.nexachat.user.dto;

import com.nexachat.user.User;

import java.util.UUID;

/**
 * Public user shape — the {@code user} object in auth responses, profile updates, and search results.
 * Deliberately excludes passwordHash; entities never leave the service layer.
 */
public record UserResponse(UUID id, String phone, String displayName, String avatarUrl) {

    public static UserResponse from(User user) {
        String phone = user.getDeletedAt() != null ? null : user.getPhone();
        return new UserResponse(user.getId(), phone, user.getDisplayName(), user.getAvatarUrl());
    }
}
