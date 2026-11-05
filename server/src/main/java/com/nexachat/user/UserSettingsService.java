package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.dto.UserSettingsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserSettingsService {

    private final UserRepository userRepository;

    public UserSettingsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserSettingsResponse get(UUID userId) {
        return toResponse(findUser(userId));
    }

    @Transactional
    public UserSettingsResponse setShowPresence(UUID userId, boolean showPresence) {
        return updateSettings(userId, showPresence, null);
    }

    @Transactional
    public UserSettingsResponse updateSettings(
            UUID userId,
            Boolean showPresence,
            Boolean aiSummaryConsent) {
        User user = findUser(userId);
        if (showPresence != null) {
            user.changeShowPresence(showPresence);
        }
        if (aiSummaryConsent != null) {
            user.changeAiSummaryConsent(aiSummaryConsent);
        }
        return toResponse(user);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
    }

    private static UserSettingsResponse toResponse(User user) {
        return new UserSettingsResponse(user.isShowPresence(), user.isAiSummaryConsent());
    }
}
