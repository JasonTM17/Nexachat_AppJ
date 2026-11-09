package com.nexachat.invite;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.conversation.ConversationUpdatedEvent;
import com.nexachat.conversation.dto.MemberResponse;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Listens to {@link ConversationUpdatedEvent} to track admin member removals
 * and clear invite bans when an admin re-invites a user directly.
 */
@Component
public class InviteEventListener {

    private static final Logger log = LoggerFactory.getLogger(InviteEventListener.class);

    private final GroupInviteBanRepository banRepository;
    private final GroupInviteLinkRepository linkRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;

    public InviteEventListener(
            GroupInviteBanRepository banRepository,
            GroupInviteLinkRepository linkRepository,
            ConversationRepository conversationRepository,
            UserRepository userRepository) {
        this.banRepository = banRepository;
        this.linkRepository = linkRepository;
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
    }

    @EventListener
    @Transactional
    public void onConversationUpdated(ConversationUpdatedEvent event) {
        if (event == null || event.payload() == null || event.payload().change() == null) {
            return;
        }

        String change = event.payload().change();
        UUID conversationId = event.conversationId();

        if ("MEMBER_REMOVED".equals(change)) {
            // An admin removed user(s) named in extraAudienceUserIds
            Optional<Conversation> convOpt = conversationRepository.findById(conversationId);
            if (convOpt.isEmpty()) {
                return;
            }
            Conversation conversation = convOpt.get();
            Optional<GroupInviteLink> activeLink =
                    linkRepository.findByConversation_IdAndRevokedAtIsNull(conversationId);

            for (UUID removedUserId : event.extraAudienceUserIds()) {
                Optional<User> userOpt = userRepository.findById(removedUserId);
                if (userOpt.isPresent()) {
                    GroupInviteBan ban = new GroupInviteBan(
                            conversation, userOpt.get(), activeLink.orElse(null));
                    banRepository.save(ban);
                    log.info("Invite ban recorded for user {} in conversation {}", removedUserId, conversationId);
                }
            }
        } else if ("MEMBERS_ADDED".equals(change)) {
            // Admin directly re-invited / added members: clear any ban for current members
            if (event.payload().members() != null) {
                for (MemberResponse member : event.payload().members()) {
                    banRepository.deleteAllByConversationIdAndUserId(conversationId, member.userId());
                }
            }
        }
    }
}
