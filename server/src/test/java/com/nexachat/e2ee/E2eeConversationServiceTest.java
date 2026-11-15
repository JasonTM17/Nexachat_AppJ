package com.nexachat.e2ee;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.ConversationUpdatedEvent;
import com.nexachat.message.MessageService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyBoolean;

/**
 * Ordering of the experimental v1 activation gate inside
 * {@link E2eeConversationService#setEnabled}: lock -&gt; membership -&gt; DIRECT
 * -&gt; idempotent no-op -&gt; activation policy (false-&gt;true only) -&gt;
 * device readiness -&gt; write/system/event.
 */
class E2eeConversationServiceTest {

    private final ConversationRepository conversationRepository =
            mock(ConversationRepository.class);
    private final ConversationMemberRepository memberRepository =
            mock(ConversationMemberRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final E2eeDeviceService deviceService = mock(E2eeDeviceService.class);
    private final MessageService messageService = mock(MessageService.class);
    private final ApplicationEventPublisher eventPublisher =
            mock(ApplicationEventPublisher.class);
    private final E2eeProperties properties = new E2eeProperties();

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private E2eeConversationService service;

    @BeforeEach
    void setUp() {
        service = new E2eeConversationService(conversationRepository, memberRepository,
                userRepository, deviceService, messageService, eventPublisher, properties);
    }

    private Conversation directConversation(boolean e2eeEnabled) {
        Conversation conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn(conversationId);
        when(conversation.getType()).thenReturn(Conversation.Type.DIRECT);
        when(conversation.isE2eeEnabled()).thenReturn(e2eeEnabled);
        return conversation;
    }

    private void memberDirect(Conversation conversation) {
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
    }

    @Test
    void activationDeniedWhenGateIsOffWithNoSideEffects() {
        Conversation conversation = directConversation(false);
        memberDirect(conversation);

        assertThatThrownBy(() -> service.setEnabled(userId, conversationId, true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ApiErrorCode.E2EE_V1_ACTIVATION_DISABLED);

        // Denial precedes the device-readiness query, the write, the SYSTEM
        // message and the update event.
        verify(deviceService, never()).allMembersHaveDevices(anyList());
        verify(conversationRepository, never()).save(any());
        verify(messageService, never()).postSystemMessage(any(), any(), any());
        // publishEvent(Object) is the only overload a record event can hit.
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        verify(conversation, never()).setE2eeEnabled(anyBoolean());
    }

    @Test
    void nonMemberDeniedBeforePolicy() {
        Conversation conversation = directConversation(false);
        when(conversationRepository.findByIdForUpdate(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.setEnabled(userId, conversationId, true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ApiErrorCode.NOT_MEMBER);
    }

    @Test
    void nonDirectDeniedBeforePolicy() {
        Conversation conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn(conversationId);
        when(conversation.getType()).thenReturn(Conversation.Type.GROUP);
        when(conversation.isE2eeEnabled()).thenReturn(false);
        memberDirect(conversation);

        assertThatThrownBy(() -> service.setEnabled(userId, conversationId, true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ApiErrorCode.E2EE_UNSUPPORTED_CONVERSATION);
    }

    @Test
    void idempotentEnableIsNoOpEvenWhenGateIsOff() {
        Conversation conversation = directConversation(true);
        memberDirect(conversation);
        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of());
        when(deviceService.activeDevicesForMembers(anyList())).thenReturn(List.of());

        service.setEnabled(userId, conversationId, true);

        verify(conversationRepository, never()).save(any());
        verify(messageService, never()).postSystemMessage(any(), any(), any());
        verify(conversation, never()).setE2eeEnabled(anyBoolean());
    }

    @Test
    void idempotentDisableIsNoOpWhenGateIsOff() {
        Conversation conversation = directConversation(false);
        memberDirect(conversation);
        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of());
        when(deviceService.activeDevicesForMembers(anyList())).thenReturn(List.of());

        service.setEnabled(userId, conversationId, false);

        verify(conversationRepository, never()).save(any());
        verify(conversation, never()).setE2eeEnabled(anyBoolean());
    }

    @Test
    void explicitDisableAllowedWhenGateIsOff() {
        Conversation conversation = directConversation(true);
        memberDirect(conversation);
        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of());
        when(deviceService.activeDevicesForMembers(anyList())).thenReturn(List.of());
        User actor = mock(User.class);
        when(actor.getDisplayName()).thenReturn("An");
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        service.setEnabled(userId, conversationId, false);

        verify(conversation).setE2eeEnabled(false);
        verify(conversationRepository).save(conversation);
        verify(messageService).postSystemMessage(any(), any(), any());
        verify(eventPublisher).publishEvent(any(ConversationUpdatedEvent.class));
    }

    @Test
    void gateOnStillRequiresDeviceReadiness() {
        properties.setV1ActivationEnabled(true);
        Conversation conversation = directConversation(false);
        memberDirect(conversation);
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(userId, UUID.randomUUID()));
        when(deviceService.allMembersHaveDevices(anyList())).thenReturn(false);

        assertThatThrownBy(() -> service.setEnabled(userId, conversationId, true))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ApiErrorCode.E2EE_PEER_NOT_READY);
    }

    @Test
    void gateOnEnablesAndReportsCapability() {
        properties.setV1ActivationEnabled(true);
        Conversation conversation = directConversation(false);
        memberDirect(conversation);
        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of(userId));
        when(deviceService.allMembersHaveDevices(anyList())).thenReturn(true);
        when(deviceService.activeDevicesForMembers(anyList())).thenReturn(List.of());
        User actor = mock(User.class);
        when(actor.getDisplayName()).thenReturn("An");
        when(userRepository.findById(userId)).thenReturn(Optional.of(actor));
        when(memberRepository.findMembers(conversationId)).thenReturn(List.of());

        assertThat(service.setEnabled(userId, conversationId, true)
                .experimentalV1ActivationAllowed()).isTrue();
        verify(conversation).setE2eeEnabled(true);
        verify(conversationRepository).save(conversation);
    }

    @Test
    void stateReportsGateOffCapability() {
        Conversation conversation = directConversation(true);
        when(conversationRepository.findById(conversationId))
                .thenReturn(Optional.of(conversation));
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        when(memberRepository.findMemberUserIds(conversationId)).thenReturn(List.of());
        when(deviceService.activeDevicesForMembers(anyList())).thenReturn(List.of());

        assertThat(service.getState(userId, conversationId)
                .experimentalV1ActivationAllowed()).isFalse();
    }
}
