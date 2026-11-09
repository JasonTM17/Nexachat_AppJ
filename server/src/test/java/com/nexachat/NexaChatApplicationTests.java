package com.nexachat;

import com.nexachat.archive.ArchiveRepository;
import com.nexachat.auth.RefreshTokenRepository;
import com.nexachat.contact.ContactRepository;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.invite.GroupInviteBanRepository;
import com.nexachat.invite.GroupInviteLinkRepository;
import com.nexachat.invite.GroupJoinRequestRepository;
import com.nexachat.message.MessageAttachmentRepository;
import com.nexachat.message.MessageReactionRepository;
import com.nexachat.message.MessageRepository;
import com.nexachat.message.PinnedMessageRepository;
import com.nexachat.message.ReactionEventRepository;
import com.nexachat.message.RequestIdempotencyRepository;
import com.nexachat.reminder.ConversationReminderRepository;
import com.nexachat.poll.PollOptionRepository;
import com.nexachat.poll.PollRepository;
import com.nexachat.poll.PollVoteRepository;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class NexaChatApplicationTests {

    // The "test" profile excludes JPA autoconfiguration (no live database), so the
    // repositories are not real beans — register mocks to satisfy service wiring.
    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private ContactRepository contactRepository;

    @MockitoBean
    private ConversationRepository conversationRepository;

    @MockitoBean
    private ConversationMemberRepository conversationMemberRepository;

    @MockitoBean
    private MessageRepository messageRepository;

    @MockitoBean
    private MessageAttachmentRepository messageAttachmentRepository;

    @MockitoBean
    private MessageReactionRepository messageReactionRepository;

    @MockitoBean
    private ReactionEventRepository reactionEventRepository;

    @MockitoBean
    private RequestIdempotencyRepository requestIdempotencyRepository;

    @MockitoBean
    private PinnedMessageRepository pinnedMessageRepository;

    @MockitoBean
    private com.nexachat.notification.UserDeviceTokenRepository userDeviceTokenRepository;

    @MockitoBean
    private ConversationReminderRepository conversationReminderRepository;

    @MockitoBean
    private ArchiveRepository archiveRepository;

    @MockitoBean
    private PollRepository pollRepository;

    @MockitoBean
    private PollOptionRepository pollOptionRepository;

    @MockitoBean
    private PollVoteRepository pollVoteRepository;

    @MockitoBean
    private GroupInviteLinkRepository groupInviteLinkRepository;

    @MockitoBean
    private GroupJoinRequestRepository groupJoinRequestRepository;

    @MockitoBean
    private GroupInviteBanRepository groupInviteBanRepository;

    @Test
    void contextLoads() {
    }
}
