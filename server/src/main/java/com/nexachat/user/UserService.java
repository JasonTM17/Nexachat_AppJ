package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Profile read/update for the authenticated user (/users/me scope).
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public User getById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
    }

    /**
     * PATCH /users/me semantics: {@code null} displayName = no change;
     * a provided value is trimmed and must be non-blank (and ≤60 via DTO validation).
     */
    @Transactional
    public User updateDisplayName(UUID userId, String displayName) {
        User user = getById(userId);
        if (displayName != null) {
            String trimmed = displayName.trim();
            if (trimmed.isEmpty()) {
                throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "displayName must not be blank");
            }
            user.changeDisplayName(trimmed);
            userRepository.save(user);
        }
        return user;
    }
}
