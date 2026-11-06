package com.nexachat.user;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.presence.UserPresenceChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserSettingsServiceTest {

    @Test
    void settingsReadAndUpdatePersistOnlyTheCallerPreference() {
        UserRepository repository = mock(UserRepository.class);
        UserSettingsService service = service(repository, mock(ConversationMemberRepository.class),
                mock(PresenceSessionState.class), mock(ApplicationEventPublisher.class));
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
        UserSettingsService service = service(repository, mock(ConversationMemberRepository.class),
                mock(PresenceSessionState.class), mock(ApplicationEventPublisher.class));
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
        UserSettingsService service = service(repository, mock(ConversationMemberRepository.class),
                mock(PresenceSessionState.class), mock(ApplicationEventPublisher.class));
        UUID userId = UUID.randomUUID();
        when(repository.findById(userId)).thenReturn(Optional.empty());

        ApiException error = catchThrowableOfType(ApiException.class, () -> service.get(userId));

        assertThat(error.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
    }

    @Test
    void hidingPresenceWhileOnlinePublishesOfflineWithoutLastSeen() {
        UserRepository repository = mock(UserRepository.class);
        ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
        PresenceSessionState sessionState = mock(PresenceSessionState.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        UserSettingsService service = service(repository, memberRepository, sessionState, eventPublisher);
        UUID userId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        User user = User.create("+84901112235", "hash", "User");
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(sessionState.isOnline(userId)).thenReturn(true);
        when(memberRepository.findDirectPeerUserIds(userId)).thenReturn(List.of(peerId));

        service.updateSettings(userId, false, null);

        verify(eventPublisher).publishEvent(
                new UserPresenceChangedEvent(userId, false, null, List.of(peerId)));
    }

    @Test
    void showingPresenceWhileOnlinePublishesOnline() {
        UserRepository repository = mock(UserRepository.class);
        ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
        PresenceSessionState sessionState = mock(PresenceSessionState.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        UserSettingsService service = service(repository, memberRepository, sessionState, eventPublisher);
        UUID userId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();
        User user = User.create("+84901112236", "hash", "User");
        user.changeShowPresence(false);
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(sessionState.isOnline(userId)).thenReturn(true);
        when(memberRepository.findDirectPeerUserIds(userId)).thenReturn(List.of(peerId));

        service.updateSettings(userId, true, null);

        verify(eventPublisher).publishEvent(
                new UserPresenceChangedEvent(userId, true, null, List.of(peerId)));
    }

    @Test
    void unchangedOrOfflineVisibilityUpdateDoesNotPublishPresence() {
        UserRepository repository = mock(UserRepository.class);
        ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
        PresenceSessionState sessionState = mock(PresenceSessionState.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        UserSettingsService service = service(repository, memberRepository, sessionState, eventPublisher);
        UUID userId = UUID.randomUUID();
        User user = User.create("+84901112237", "hash", "User");
        when(repository.findById(userId)).thenReturn(Optional.of(user));
        when(sessionState.isOnline(userId)).thenReturn(true);

        service.updateSettings(userId, true, null);

        when(sessionState.isOnline(userId)).thenReturn(false);
        service.updateSettings(userId, false, null);

        verifyNoInteractions(eventPublisher, memberRepository);
    }

    private static UserSettingsService service(UserRepository repository,
                                               ConversationMemberRepository memberRepository,
                                               PresenceSessionState sessionState,
                                               ApplicationEventPublisher eventPublisher) {
        return new UserSettingsService(repository, memberRepository, sessionState, eventPublisher);
    }
}
