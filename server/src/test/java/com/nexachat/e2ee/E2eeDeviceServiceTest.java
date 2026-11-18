package com.nexachat.e2ee;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class E2eeDeviceServiceTest {

    @Mock
    private E2eeDeviceRepository deviceRepository;
    @Mock
    private UserRepository userRepository;

    private E2eeDeviceService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final String publicKey = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[32]);

    @BeforeEach
    void setUp() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryConsume(anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RateLimitService.Consumption(true, 0));
        service = new E2eeDeviceService(deviceRepository, userRepository,
                rateLimitService, Clock.systemUTC());
    }

    private User userWithId(UUID id) {
        User user = mock(User.class);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(id);
        return user;
    }

    @Test
    void register_losingTheDeviceIdRaceAnswersConflictNot500() {
        User owner = userWithId(userId);
        when(userRepository.findByIdForUpdate(userId))
                .thenReturn(Optional.of(owner));
        // The pre-check missed the row the racing winner committed.
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());
        when(deviceRepository.countByUser_IdAndRevokedAtIsNull(userId)).thenReturn(0L);
        when(deviceRepository.saveAndFlush(any(E2eeDevice.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        ApiException ex = catchThrowableOfType(
                () -> service.register(userId, deviceId, publicKey), ApiException.class);
        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.E2EE_DEVICE_CONFLICT);
    }
}
