package com.nexachat.user.dto;

import com.nexachat.user.User;

import java.util.UUID;

/** Owner-only profile shape. Recovery email is intentionally absent from UserResponse. */
public record MyProfileResponse(
        UUID id,
        String phone,
        String displayName,
        String avatarUrl,
        String email,
        boolean emailVerified) {

    public static MyProfileResponse from(User user) {
        return new MyProfileResponse(
                user.getId(),
                user.getDeletedAt() == null ? user.getPhone() : null,
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getEmail(),
                user.hasVerifiedEmail());
    }
}
