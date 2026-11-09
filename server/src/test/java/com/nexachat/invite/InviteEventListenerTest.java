package com.nexachat.invite;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMember;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.ConversationUpdatedEvent;
import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.conversation.dto.MemberResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InviteEventListenerTest {

    @Mock
    private GroupInviteBanRepository banRepository;
    @Mock
    private GroupInviteLinkRepository linkRepository;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private UserRepository userRepository;

    private InviteEventListener listener;

    private final UUID conversationId = UUID.randomUUID();
    private final UUID kickedUserId = UUID.randomUUID();
    private Conversation conversation;
    private User kickedUser;

    @BeforeEach
    void setUp() {
        listener = new InviteEventListener(banRepository, linkRepository, conversationRepository, userRepository);

        kickedUser = User.create("+84901111111", "hash", "Kicked");
        ReflectionTestUtils.setField(kickedUser, "id", kickedUserId);

        conversation = Conversation.group(kickedUser, "Group");
        ReflectionTestUtils.setField(conversation, "id", conversationId);
    }

    @Test
    void onMemberRemoved_recordsBanWithActiveInviteLink() {
        GroupInviteLink activeLink = new GroupInviteLink(
                conversation, kickedUser, "hash", false, null, null);
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(linkRepository.findByConversation_IdAndRevokedAtIsNull(conversationId)).thenReturn(Optional.of(activeLink));
        when(userRepository.findById(kickedUserId)).thenReturn(Optional.of(kickedUser));

        ConversationUpdatePayload payload = new ConversationUpdatePayload(
                conversationId, "Group", null, "MEMBER_REMOVED", List.of());
        ConversationUpdatedEvent event = new ConversationUpdatedEvent(
                conversationId, payload, List.of(kickedUserId));

        listener.onConversationUpdated(event);

        ArgumentCaptor<GroupInviteBan> captor = ArgumentCaptor.forClass(GroupInviteBan.class);
        verify(banRepository).save(captor.capture());
        GroupInviteBan ban = captor.getValue();
        assertThat(ban.getConversation()).isEqualTo(conversation);
        assertThat(ban.getUser()).isEqualTo(kickedUser);
        assertThat(ban.getInviteLink()).isEqualTo(activeLink);
    }

    @Test
    void onMembersAdded_clearsBanForReinvitedUsers() {
        MemberResponse member = new MemberResponse(
                kickedUserId, "Kicked", "+84901111111", null,
                ConversationMember.Role.MEMBER, Instant.now());
        ConversationUpdatePayload payload = new ConversationUpdatePayload(
                conversationId, "Group", null, "MEMBERS_ADDED", List.of(member));
        ConversationUpdatedEvent event = new ConversationUpdatedEvent(
                conversationId, payload, List.of());

        listener.onConversationUpdated(event);

        verify(banRepository).deleteAllByConversationIdAndUserId(conversationId, kickedUserId);
    }

    @Test
    void ignoresUnrelatedChanges() {
        ConversationUpdatePayload payload = new ConversationUpdatePayload(
                conversationId, "Group", null, "RENAMED", List.of());
        ConversationUpdatedEvent event = new ConversationUpdatedEvent(
                conversationId, payload, List.of());

        listener.onConversationUpdated(event);

        verify(banRepository, never()).save(any());
        verify(banRepository, never()).deleteAllByConversationIdAndUserId(any(), any());
    }
}
