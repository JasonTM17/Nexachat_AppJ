package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSettingsServiceTest {

    @Test
    void settingsReadAndUpdatePersistOnlyTheCallerPreference() {
        UserRepository repository = mock(UserRepository.class);
        UserSettingsService service = new UserSettingsService(repository);
        UUID userId = UUID.randomUUID();
        User user = User.create("+84901112233", "hash", "User");
        when(repository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(service.get(userId).showPresence()).isTrue();
        assertThat(service.get(userId).aiSummaryConsent()).isFalse();
        assertThat(service.setShowPresence(userId, false).showPresence()).isFalse();
        assertThat(service.get(userId).aiSummaryConsent()).isFalse();
        verify(repository, org.mockito.Mockito.times(4)).findById(userId);
    }

    @Test
    void partialUpdatesLeaveTheOmittedSettingUnchanged() {
        UserRepository repository = mock(UserRepository.class);
        UserSettingsService service = new UserSettingsService(repository);
        UUID userId = UUID.randomUUID();
        User user = User.create("+84901112234", "hash", "User");
        when(repository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(service.updateSettings(userId, null, true))
                .satisfies(settings -> {
                    assertThat(settings.showPresence()).isTrue();
                    assertThat(settings.aiSummaryConsent()).isTrue();
                });
        assertThat(service.updateSettings(userId, false, null))
                .satisfies(settings -> {
                    assertThat(settings.showPresence()).isFalse();
                    assertThat(settings.aiSummaryConsent()).isTrue();
                });
        assertThat(service.updateSettings(userId, null, false))
                .satisfies(settings -> {
                    assertThat(settings.showPresence()).isFalse();
                    assertThat(settings.aiSummaryConsent()).isFalse();
                });
    }

    @Test
    void settingsForMissingAccountReturnsUserNotFound() {
        UserRepository repository = mock(UserRepository.class);
        UserSettingsService service = new UserSettingsService(repository);
        UUID userId = UUID.randomUUID();
        when(repository.findById(userId)).thenReturn(Optional.empty());

        ApiException error = catchThrowableOfType(ApiException.class, () -> service.get(userId));

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }
}
