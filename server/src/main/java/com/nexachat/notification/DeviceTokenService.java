package com.nexachat.notification;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.notification.dto.DeviceTokenRequest;
import com.nexachat.notification.dto.DeviceTokenResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class DeviceTokenService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenService.class);

    private final UserDeviceTokenRepository tokenRepository;
    private final UserRepository userRepository;

    public DeviceTokenService(UserDeviceTokenRepository tokenRepository, UserRepository userRepository) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public DeviceTokenResponse registerToken(UUID userId, DeviceTokenRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "token is required");
        }
        String cleanToken = request.token().strip();
        UserDeviceToken.Platform platform = parsePlatform(request.platform());
        String deviceLabel = request.deviceLabel() != null ? request.deviceLabel().strip() : null;

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));

        Optional<UserDeviceToken> existing = tokenRepository.findByUserIdAndToken(userId, cleanToken);
        UserDeviceToken tokenEntity;
        if (existing.isPresent()) {
            tokenEntity = existing.get();
            tokenEntity.updateUsage(deviceLabel, platform);
        } else {
            tokenRepository.deleteByToken(cleanToken);
            tokenRepository.flush();
            tokenEntity = UserDeviceToken.create(user, cleanToken, platform, deviceLabel);
            tokenEntity = tokenRepository.save(tokenEntity);
        }

        log.info("Registered device token for user {} ({})", userId, platform);
        return new DeviceTokenResponse(
                tokenEntity.getId(),
                tokenEntity.getToken(),
                tokenEntity.getPlatform().name(),
                tokenEntity.getDeviceLabel(),
                tokenEntity.getUpdatedAt()
        );
    }

    @Transactional
    public void unregisterToken(UUID userId, String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "token is required");
        }
        tokenRepository.deleteByUserIdAndToken(userId, token.strip());
        log.info("Unregistered device token for user {}", userId);
    }

    @Transactional
    public void unregisterToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tokenRepository.deleteByToken(token.strip());
        log.info("Unregistered device token");
    }

    private static UserDeviceToken.Platform parsePlatform(String raw) {
        if (raw == null || raw.isBlank()) {
            return UserDeviceToken.Platform.ANDROID;
        }
        try {
            return UserDeviceToken.Platform.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UserDeviceToken.Platform.ANDROID;
        }
    }
}
