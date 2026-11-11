package com.nexachat.presence;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.dto.InboxEntry;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class PresenceService {

    private final UserRepository userRepository;
    private final ConversationMemberRepository memberRepository;
    private final PresenceSessionState sessionState;
    private final ApplicationEventPublisher eventPublisher;

    public PresenceService(UserRepository userRepository,
                           ConversationMemberRepository memberRepository,
                           PresenceSessionState sessionState,
                           ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.sessionState = sessionState;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void connected(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.isShowPresence()) {
            eventPublisher.publishEvent(new UserPresenceChangedEvent(
                    userId, true, null, memberRepository.findDirectPeerUserIds(userId)));
        }
    }

    @Transactional
    public Instant recordLastSeen(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null) {
            return null;
        }
        Instant lastSeenAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        user.recordLastSeen(lastSeenAt);
        return lastSeenAt;
    }

    @Transactional
    public void disconnected(UUID userId, Instant lastSeenAt) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.isShowPresence()) {
            eventPublisher.publishEvent(new UserPresenceChangedEvent(
                    userId, false, lastSeenAt, memberRepository.findDirectPeerUserIds(userId)));
        }
    }

    @Transactional(readOnly = true)
    public Map<UUID, InboxEntry.PeerPresence> peerPresence(UUID userId,
                                                           Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, InboxEntry.PeerPresence> presenceByConversation = new LinkedHashMap<>();
        for (DirectPeerPresenceRow row : memberRepository.findDirectPeerPresence(userId, conversationIds)) {
            if (row.showPresence()) {
                boolean online = sessionState.isOnline(row.userId());
                presenceByConversation.put(row.conversationId(), new InboxEntry.PeerPresence(
                        online, online ? null : row.lastSeenAt()));
            }
        }
        return presenceByConversation;
    }
}
