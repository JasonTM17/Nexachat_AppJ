package com.nexachat.firebase.projection;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.auth.FirebaseBridgeLock;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.user.User;
import com.nexachat.user.UserAccountDeletedEvent;
import com.nexachat.user.UserRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Writes only current, server-derived authorization mirrors to RTDB. */
public class FirebaseAuthorizationProjectionService {

    private final UserRepository users;
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final FirebaseRtdbClient rtdb;
    private final FirebaseBridgeLock locks;
    private final FirebaseProjectionMetrics metrics;

    public FirebaseAuthorizationProjectionService(UserRepository users,
                                                  ConversationRepository conversations,
                                                  ConversationMemberRepository members,
                                                  FirebaseRtdbClient rtdb,
                                                  FirebaseBridgeLock locks,
                                                  FirebaseProjectionMetrics metrics) {
        this.users = users;
        this.conversations = conversations;
        this.members = members;
        this.rtdb = rtdb;
        this.locks = locks;
        this.metrics = metrics;
    }

    /** Re-reads and locks the durable account row before updating privacy mirrors. */
    @Transactional
    public void refreshAccount(UUID userId) {
        locks.withUserLock(userId, () -> {
            User user = users.findByIdForUpdate(userId).orElse(null);
            if (user != null) {
                metrics.write("account", () -> rtdb.applyAuthorizationProjection(Map.of(
                        accountPath(userId), accountValue(user))));
            }
        });
    }

    /**
     * Rebuilds both direct-reader edges from current PostgreSQL membership.
     * Group conversations never create peer-visibility edges.
     */
    @Transactional
    public void reconcileConversation(UUID conversationId) {
        Conversation conversation = conversations.findByIdForUpdate(conversationId).orElse(null);
        if (conversation == null || conversation.getType() != Conversation.Type.DIRECT) {
            return;
        }
        List<UUID> memberIds = members.findMemberUserIds(conversationId).stream().distinct().sorted().toList();
        if (memberIds.size() != 2) {
            return;
        }
        withUserLocks(memberIds, () -> {
            Conversation current = conversations.findById(conversationId).orElse(null);
            if (current == null || current.getType() != Conversation.Type.DIRECT) {
                return;
            }
            List<UUID> currentMembers = members.findMemberUserIds(conversationId)
                    .stream().distinct().sorted().toList();
            if (currentMembers.size() != 2
                    || users.findByIdForUpdate(currentMembers.get(0)).isEmpty()
                    || users.findByIdForUpdate(currentMembers.get(1)).isEmpty()) {
                return;
            }
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(readerPath(currentMembers.get(0), currentMembers.get(1)), conversationId.toString());
            updates.put(readerPath(currentMembers.get(1), currentMembers.get(0)), conversationId.toString());
            metrics.write("direct-readers", () -> rtdb.applyAuthorizationProjection(updates));
        });
    }

    /** Invalidates stale authorization and typing immediately after a member leaves or is removed. */
    @Transactional
    public void revokeMember(UUID conversationId, UUID userId) {
        locks.withUserLock(userId, () -> {
            users.findByIdForUpdate(userId);
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(memberPath(conversationId, userId), null);
            updates.put(typingPath(conversationId, userId), null);
            metrics.write("member-revoke", () -> rtdb.applyAuthorizationProjection(updates));
        });
    }

    /** Removes every known server-owned authorization edge for a deleted account. */
    @Transactional
    public void deleteAccount(UserAccountDeletedEvent event) {
        locks.withUserLock(event.userId(), () -> {
            Map<String, Object> updates = new LinkedHashMap<>();
            UUID userId = event.userId();
            updates.put(accountPath(userId), null);
            updates.put("sessions/" + userId, null);
            updates.put("presence/" + userId, null);
            updates.put("presenceReaders/" + userId, null);
            updates.put("blocked/" + userId, null);
            for (UUID conversationId : event.conversationIds()) {
                updates.put(memberPath(conversationId, userId), null);
                updates.put(typingPath(conversationId, userId), null);
            }
            for (UUID peerId : event.directPeerUserIds()) {
                updates.put(readerPath(peerId, userId), null);
            }
            for (UUID peerId : event.blockedPeerUserIds()) {
                updates.put("blocked/" + peerId + "/" + userId, null);
            }
            for (UUID callId : event.callIds()) {
                updates.put("calls/" + callId, null);
            }
            metrics.write("account-delete", () -> rtdb.applyAuthorizationProjection(updates));
        });
    }

    private void withUserLocks(List<UUID> userIds, Runnable action) {
        List<UUID> sorted = userIds.stream().distinct().sorted().toList();
        withUserLockAt(sorted, 0, action);
    }

    private void withUserLockAt(List<UUID> userIds, int index, Runnable action) {
        if (index == userIds.size()) {
            action.run();
            return;
        }
        locks.withUserLock(userIds.get(index), () -> withUserLockAt(userIds, index + 1, action));
    }

    private static Map<String, Object> accountValue(User user) {
        return Map.of("active", true, "showPresence", user.isShowPresence());
    }

    private static String accountPath(UUID userId) {
        return "accounts/" + userId;
    }

    private static String readerPath(UUID subjectId, UUID viewerId) {
        return "presenceReaders/" + subjectId + "/" + viewerId;
    }

    private static String memberPath(UUID conversationId, UUID userId) {
        return "members/" + conversationId + "/" + userId;
    }

    private static String typingPath(UUID conversationId, UUID userId) {
        return "typing/" + conversationId + "/" + userId;
    }
}
