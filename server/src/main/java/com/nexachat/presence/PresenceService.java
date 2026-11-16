package com.nexachat.presence;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.dto.InboxEntry;
import com.nexachat.user.User;
import com.nexachat.user.UserBlockRepository;
import com.nexachat.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

@Service
public class PresenceService {

    private final UserRepository userRepository;
    private final ConversationMemberRepository memberRepository;
    private final UserBlockRepository blockRepository;
    private final PresenceSessionState sessionState;
    private final ApplicationEventPublisher eventPublisher;

    public PresenceService(UserRepository userRepository,
                           ConversationMemberRepository memberRepository,
                           UserBlockRepository blockRepository,
                           PresenceSessionState sessionState,
                           ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.blockRepository = blockRepository;
        this.sessionState = sessionState;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void connected(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null && user.isShowPresence()) {
            eventPublisher.publishEvent(new UserPresenceChangedEvent(
                    userId, true, null, audienceExcludingBlocked(userId)));
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
                    userId, false, lastSeenAt, audienceExcludingBlocked(userId)));
        }
    }

    @Transactional(readOnly = true)
    public Map<UUID, InboxEntry.PeerPresence> peerPresence(UUID userId,
                                                           Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        List<DirectPeerPresenceRow> rows = memberRepository.findDirectPeerPresence(userId, conversationIds);
        Set<UUID> hidden = blockRelatedAmong(userId,
                rows.stream().map(DirectPeerPresenceRow::userId).toList());
        Map<UUID, InboxEntry.PeerPresence> presenceByConversation = new LinkedHashMap<>();
        for (DirectPeerPresenceRow row : rows) {
            // V38: a block edge hides presence/last-seen in BOTH directions —
            // the row is absent rather than shown offline, so the peer's real
            // state stays unknowable to both sides.
            if (hidden.contains(row.userId())) {
                continue;
            }
            if (row.showPresence()) {
                boolean online = sessionState.isOnline(row.userId());
                presenceByConversation.put(row.conversationId(), new InboxEntry.PeerPresence(
                        online, online ? null : row.lastSeenAt()));
            }
        }
        return presenceByConversation;
    }

    /** Direct-peer audience minus anyone with a block edge to the user (V38). */
    private List<UUID> audienceExcludingBlocked(UUID userId) {
        List<UUID> peers = memberRepository.findDirectPeerUserIds(userId);
        Set<UUID> hidden = blockRelatedAmong(userId, peers);
        return hidden.isEmpty()
                ? peers
                : peers.stream().filter(id -> !hidden.contains(id)).toList();
    }

    private Set<UUID> blockRelatedAmong(UUID userId, Collection<UUID> candidateIds) {
        if (blockRepository == null || candidateIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(blockRepository.findRelatedIdsEitherDirection(userId, candidateIds));
    }
}
