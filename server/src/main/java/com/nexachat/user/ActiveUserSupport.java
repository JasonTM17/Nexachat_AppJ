package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.util.UUID;

/** Row-locking reads for authenticated user mutations after account deletion. */
final class ActiveUserSupport {

    private ActiveUserSupport() {
    }

    static User requireActiveForUpdate(UserRepository userRepository, UUID userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        if (user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
        return user;
    }

    static void assertActive(User user) {
        if (user.getDeletedAt() != null) {
            throw ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found");
        }
    }
}
