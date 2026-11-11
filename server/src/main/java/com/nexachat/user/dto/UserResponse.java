package com.nexachat.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexachat.user.DeletedAccountPolicy;
import com.nexachat.user.User;

import java.util.UUID;

/**
 * Public profile shape — the {@code user} object in auth responses and /users/me
 * (plan §10.3). Deliberately excludes passwordHash; entities never leave the service layer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(UUID id, String phone, String displayName, String avatarUrl, boolean deleted) {

    public static UserResponse from(User user) {
        boolean deleted = user.getDeletedAt() != null;
        String phone = deleted ? null : user.getPhone();
        String displayName = deleted ? DeletedAccountPolicy.DISPLAY_NAME : user.getDisplayName();
        return new UserResponse(user.getId(), phone, displayName, user.getAvatarUrl(), deleted);
    }

    /** {@code includePhone} false omits the number from display-name discovery hits. */
    public static UserResponse forSearchResult(User user, boolean includePhone) {
        UserResponse response = from(user);
        return new UserResponse(response.id(), includePhone ? response.phone() : null,
                response.displayName(), response.avatarUrl(), response.deleted());
    }
}
