package com.nexachat.e2ee;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.ConversationUpdatedEvent;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.e2ee.dto.ConversationE2eeStateResponse;
import com.nexachat.message.MessageService;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The {@code e2ee_enabled} flag on DIRECT conversations. Enabling requires
 * every member to own at least one active device, otherwise peers could never
 * wrap a key for them. A change posts a SYSTEM line and publishes the regular
 * {@code CONVERSATION_UPDATED} snapshot so both clients refresh in place.
 */
@Service
public class E2eeConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final E2eeDeviceService deviceService;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;

    public E2eeConversationService(ConversationRepository conversationRepository,
                                   ConversationMemberRepository memberRepository,
                                   UserRepository userRepository,
                                   E2eeDeviceService deviceService,
                                   MessageService messageService,
                                   ApplicationEventPublisher eventPublisher) {
        this.conversationRepository = conversationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.deviceService = deviceService;
        this.messageService = messageService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public ConversationE2eeStateResponse getState(UUID userId, UUID conversationId) {
        Conversation conversation = requireDirectForMember(conversationId, userId);
        return state(conversation);
    }

    @Transactional
    public ConversationE2eeStateResponse setEnabled(UUID userId, UUID conversationId, boolean enabled) {
        Conversation conversation = conversationRepository.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        requireMember(conversationId, userId);
        requireDirect(conversation);

        if (conversation.isE2eeEnabled() == enabled) {
            return state(conversation);
        }
        if (enabled) {
            List<UUID> memberIds = memberRepository.findMemberUserIds(conversationId);
            if (!deviceService.allMembersHaveDevices(memberIds)) {
                throw ApiException.of(ApiErrorCode.E2EE_PEER_NOT_READY,
                        "Every member needs at least one registered device");
            }
        }
        conversation.setE2eeEnabled(enabled);
        conversationRepository.save(conversation);

        User actor = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.of(ApiErrorCode.USER_NOT_FOUND, "User not found"));
        messageService.postSystemMessage(userId, conversationId,
                actor.getDisplayName()
                        + (enabled ? " turned on end-to-end encryption"
                                   : " turned off end-to-end encryption"));
        ConversationUpdatePayload payload = new ConversationUpdatePayload(
                conversationId, conversation.getTitle(), conversation.getAvatarUrl(),
                "UPDATED", memberRepository.findMembers(conversationId),
                conversation.isE2eeEnabled());
        eventPublisher.publishEvent(new ConversationUpdatedEvent(conversationId, payload, List.of()));
        return state(conversation);
    }

    private ConversationE2eeStateResponse state(Conversation conversation) {
        List<UUID> memberIds = memberRepository.findMemberUserIds(conversation.getId());
        return new ConversationE2eeStateResponse(
                conversation.isE2eeEnabled(), deviceService.activeDevicesForMembers(memberIds));
    }

    private Conversation requireDirectForMember(UUID conversationId, UUID userId) {
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ApiException.of(
                        ApiErrorCode.CONVERSATION_NOT_FOUND, "Conversation not found"));
        requireMember(conversationId, userId);
        requireDirect(conversation);
        return conversation;
    }

    private void requireMember(UUID conversationId, UUID userId) {
        if (!memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            throw ApiException.of(ApiErrorCode.NOT_MEMBER, "Not a member of this conversation");
        }
    }

    private static void requireDirect(Conversation conversation) {
        if (conversation.getType() != Conversation.Type.DIRECT) {
            throw ApiException.of(ApiErrorCode.E2EE_UNSUPPORTED_CONVERSATION,
                    "End-to-end encryption is only available for direct chats");
        }
    }
}
