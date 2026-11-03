package com.nexachat.user.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH /users/me body. {@code displayName} is optional: absent/null means
 * "no change"; a present-but-blank value is rejected in the service layer.
 */
public record UpdateProfileRequest(
        @Size(max = 60) String displayName
) {
}
