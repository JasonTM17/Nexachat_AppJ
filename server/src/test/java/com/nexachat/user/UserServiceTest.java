package com.nexachat.user;

import com.nexachat.common.error.ApiException;
import com.nexachat.common.error.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private static User userWithId(String displayName) {
        User user = User.create("+84912345678", "hash", displayName);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void getById_missingUser_throwsUserNotFound404() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(ApiException.class, () -> userService.getById(id));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void updateDisplayName_trimsAndPersists() {
        User user = userWithId("Old Name");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        User updated = userService.updateDisplayName(user.getId(), "  Lan Nguyen  ");

        assertThat(updated.getDisplayName()).isEqualTo("Lan Nguyen");
        verify(userRepository).save(user);
    }

    @Test
    void updateDisplayName_blank_throwsValidationFailed() {
        User user = userWithId("Old Name");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        ApiException ex = catchThrowableOfType(ApiException.class, 
                () -> userService.updateDisplayName(user.getId(), "   "));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        assertThat(user.getDisplayName()).isEqualTo("Old Name");
        verify(userRepository, never()).save(user);
    }

    @Test
    void updateDisplayName_null_isNoOp() {
        User user = userWithId("Old Name");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        User updated = userService.updateDisplayName(user.getId(), null);

        assertThat(updated.getDisplayName()).isEqualTo("Old Name");
        verify(userRepository, never()).save(user);
    }
}
