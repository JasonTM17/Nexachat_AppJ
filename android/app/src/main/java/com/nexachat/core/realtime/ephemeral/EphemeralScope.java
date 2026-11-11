package com.nexachat.core.realtime.ephemeral;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;

/** Bounded, authoritative observer scope for Firebase presence and typing. */
public final class EphemeralScope {
    public static final int MAX_LEASED_CONVERSATIONS = 30;

    private static final EphemeralScope EMPTY = new EphemeralScope(Map.of(), null, Set.of());

    private final Map<String, String> presenceConversationByUser;
    @Nullable private final String typingConversationId;
    private final Set<String> typingUserIds;

    private EphemeralScope(
            Map<String, String> presenceConversationByUser,
            @Nullable String typingConversationId,
            Set<String> typingUserIds) {
        this.presenceConversationByUser = Collections.unmodifiableMap(
                new LinkedHashMap<>(presenceConversationByUser));
        this.typingConversationId = typingConversationId;
        this.typingUserIds = Collections.unmodifiableSet(new LinkedHashSet<>(typingUserIds));
    }

    public static EphemeralScope empty() {
        return EMPTY;
    }

    public EphemeralScope withPresencePeers(Map<String, String> conversationByUserId) {
        Map<String, String> safe = new TreeMap<>();
        for (Map.Entry<String, String> entry : conversationByUserId.entrySet()) {
            if (safePath(entry.getKey()) && safePath(entry.getValue())) {
                safe.put(entry.getKey(), entry.getValue());
            }
        }
        return new EphemeralScope(safe, typingConversationId, typingUserIds);
    }

    public EphemeralScope withTypingConversation(
            @Nullable String conversationId, Set<String> memberIds) {
        String safeConversationId = safePath(conversationId) ? conversationId : null;
        if (safeConversationId == null) {
            return new EphemeralScope(presenceConversationByUser, null, Set.of());
        }
        Set<String> safeMembers = new LinkedHashSet<>();
        for (String memberId : memberIds) {
            if (safePath(memberId)) safeMembers.add(memberId);
        }
        return new EphemeralScope(presenceConversationByUser, safeConversationId, safeMembers);
    }

    public EphemeralScope withoutTypingConversation(String conversationId) {
        if (!Objects.equals(typingConversationId, conversationId)) return this;
        return new EphemeralScope(presenceConversationByUser, null, Set.of());
    }

    public Map<String, String> getPresenceConversationByUser() {
        return presenceConversationByUser;
    }

    @Nullable public String getTypingConversationId() { return typingConversationId; }
    public Set<String> getTypingUserIds() { return typingUserIds; }

    /** Open chat first, followed by at most 29 direct-peer conversation leases. */
    public List<String> leasedConversationIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (typingConversationId != null) ids.add(typingConversationId);
        for (String id : presenceConversationByUser.values()) {
            if (ids.size() >= MAX_LEASED_CONVERSATIONS) break;
            ids.add(id);
        }
        return Collections.unmodifiableList(new ArrayList<>(ids));
    }

    public static boolean safePath(@Nullable String value) {
        if (value == null || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > 768) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '/' || character == '.' || character == '#' || character == '$'
                    || character == '[' || character == ']' || Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EphemeralScope that)) return false;
        return presenceConversationByUser.equals(that.presenceConversationByUser)
                && Objects.equals(typingConversationId, that.typingConversationId)
                && typingUserIds.equals(that.typingUserIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(presenceConversationByUser, typingConversationId, typingUserIds);
    }
}
