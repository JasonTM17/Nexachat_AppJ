package com.nexachat.notification;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.notification.dto.DeviceTokenRequest;
import com.nexachat.notification.dto.DeviceTokenResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceTokenControllerTest {

    @Mock
    private UserDeviceTokenRepository tokenRepository;
    @Mock
    private UserRepository userRepository;

    private DeviceTokenService deviceTokenService;
    private DeviceTokenController controller;

    private final UUID userId = UUID.randomUUID();
    private final JwtPrincipal principal = new JwtPrincipal(userId);

    @BeforeEach
    void setUp() {
        deviceTokenService = new DeviceTokenService(tokenRepository, userRepository);
        controller = new DeviceTokenController(deviceTokenService);
    }

    @Test
    void registerTokenInsertsNewEntityWhenNotFound() {
        User user = mock(User.class);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tokenRepository.findByUserIdAndToken(userId, "test-fcm-token-123")).thenReturn(Optional.empty());

        when(tokenRepository.save(any(UserDeviceToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DeviceTokenRequest request = new DeviceTokenRequest("test-fcm-token-123", "ANDROID", "Pixel 8");
        ResponseEntity<DeviceTokenResponse> response = controller.registerToken(principal, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isEqualTo("test-fcm-token-123");
        assertThat(response.getBody().platform()).isEqualTo("ANDROID");
        assertThat(response.getBody().deviceLabel()).isEqualTo("Pixel 8");

        verify(tokenRepository).save(any(UserDeviceToken.class));
    }

    @Test
    void registerTokenUpdatesExistingEntityWhenFound() {
        User user = mock(User.class);
        UserDeviceToken existing = UserDeviceToken.create(user, "test-fcm-token-123", UserDeviceToken.Platform.ANDROID, "Pixel 7");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tokenRepository.findByUserIdAndToken(userId, "test-fcm-token-123")).thenReturn(Optional.of(existing));

        DeviceTokenRequest request = new DeviceTokenRequest("test-fcm-token-123", "ANDROID", "Pixel 8");
        ResponseEntity<DeviceTokenResponse> response = controller.registerToken(principal, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(existing.getDeviceLabel()).isEqualTo("Pixel 8");
    }

    @Test
    void unregisterTokenDeletesByUserIdAndToken() {
        ResponseEntity<Void> response = controller.unregisterToken(principal, "test-fcm-token-123", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(tokenRepository).deleteByUserIdAndToken(userId, "test-fcm-token-123");
    }

    @Test
    void rejectsUnauthenticatedCaller() {
        DeviceTokenRequest request = new DeviceTokenRequest("token", "ANDROID", "label");
        assertThatThrownBy(() -> controller.registerToken(null, request))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.UNAUTHORIZED);

        assertThatThrownBy(() -> controller.unregisterToken(null, "token", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.UNAUTHORIZED);
    }

    @Test
    void rejectsBlankToken() {
        DeviceTokenRequest request = new DeviceTokenRequest("   ", "ANDROID", "label");
        assertThatThrownBy(() -> controller.registerToken(principal, request))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> controller.unregisterToken(principal, "  ", null))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);
    }
}
