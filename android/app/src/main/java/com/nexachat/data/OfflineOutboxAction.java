package com.nexachat.data;

import androidx.annotation.Nullable;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Versioned action payload persisted in {@code offline_outbox.payloadJson}. */
public final class OfflineOutboxAction {
    public static final int CURRENT_VERSION = 1;
    public static final String REACTION_ADD = "REACTION_ADD";
    public static final String REACTION_REMOVE = "REACTION_REMOVE";
    public static final String PIN = "PIN";
    public static final String UNPIN = "UNPIN";

    private final int version;
    private final String actionType;
    private final String messageId;
    private final String conversationId;
    @Nullable private final String emoji;
    private final String idempotencyKey;
    private final long createdAt;
    private final int attemptCount;
    private final boolean stateBefore;
    private final int reactionCountBefore;
    private final long pinnedAtBefore;

    public OfflineOutboxAction(String actionType, String messageId, String conversationId,
            @Nullable String emoji, String idempotencyKey, long createdAt, int attemptCount,
            boolean stateBefore, int reactionCountBefore, long pinnedAtBefore) {
        this(CURRENT_VERSION, actionType, messageId, conversationId, emoji, idempotencyKey,
                createdAt, attemptCount, stateBefore, reactionCountBefore, pinnedAtBefore);
    }

    private OfflineOutboxAction(int version, String actionType, String messageId,
            String conversationId, @Nullable String emoji, String idempotencyKey,
            long createdAt, int attemptCount, boolean stateBefore, int reactionCountBefore,
            long pinnedAtBefore) {
        this.version = version;
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.messageId = Objects.requireNonNull(messageId, "messageId");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.emoji = emoji;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.createdAt = createdAt;
        this.attemptCount = attemptCount;
        this.stateBefore = stateBefore;
        this.reactionCountBefore = reactionCountBefore;
        this.pinnedAtBefore = pinnedAtBefore;
    }

    public int getVersion() { return version; }
    public String getActionType() { return actionType; }
    public String getMessageId() { return messageId; }
    public String getConversationId() { return conversationId; }
    @Nullable public String getEmoji() { return emoji; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getCreatedAt() { return createdAt; }
    public int getAttemptCount() { return attemptCount; }
    public boolean getStateBefore() { return stateBefore; }
    public int getReactionCountBefore() { return reactionCountBefore; }
    public long getPinnedAtBefore() { return pinnedAtBefore; }

    public boolean desiredState() {
        return REACTION_ADD.equals(actionType) || PIN.equals(actionType);
    }

    public boolean isReaction() {
        return REACTION_ADD.equals(actionType) || REACTION_REMOVE.equals(actionType);
    }

    public OfflineOutboxAction withAttemptCount(int count) {
        return new OfflineOutboxAction(version, actionType, messageId, conversationId,
                emoji, idempotencyKey, createdAt, count, stateBefore, reactionCountBefore,
                pinnedAtBefore);
    }

    public OfflineOutboxAction withBeforeState(
            boolean nextState, int nextReactionCount, long nextPinnedAt) {
        return new OfflineOutboxAction(version, actionType, messageId, conversationId,
                emoji, idempotencyKey, createdAt, attemptCount, nextState,
                nextReactionCount, nextPinnedAt);
    }

    public String toJson(Moshi moshi) throws IOException {
        return adapter(moshi).toJson(toMap());
    }

    public static OfflineOutboxAction fromJson(String json, Moshi moshi) throws IOException {
        Map<String, Object> value = adapter(moshi).fromJson(json);
        if (value == null) throw new IOException("empty offline outbox payload");
        int version = integer(value, "version", -1);
        if (version != CURRENT_VERSION) throw new IOException("unsupported offline outbox version");
        String actionType = string(value, "actionType");
        if (!REACTION_ADD.equals(actionType) && !REACTION_REMOVE.equals(actionType)
                && !PIN.equals(actionType) && !UNPIN.equals(actionType)) {
            throw new IOException("unknown offline outbox action");
        }
        String messageId = string(value, "messageId");
        String conversationId = string(value, "conversationId");
        String idempotencyKey = string(value, "idempotencyKey");
        String emoji = nullableString(value, "emoji");
        if ((REACTION_ADD.equals(actionType) || REACTION_REMOVE.equals(actionType))
                && (emoji == null || emoji.isEmpty())) {
            throw new IOException("reaction payload has no emoji");
        }
        return new OfflineOutboxAction(version, actionType, messageId, conversationId,
                emoji, idempotencyKey, number(value, "createdAt", -1L),
                integer(value, "attemptCount", -1), bool(value, "stateBefore"),
                integer(value, "reactionCountBefore", 0), number(value, "pinnedAtBefore", 0L));
    }

    private Map<String, Object> toMap() {
        Map<String, Object> value = new HashMap<>();
        value.put("version", version);
        value.put("actionType", actionType);
        value.put("messageId", messageId);
        value.put("conversationId", conversationId);
        value.put("emoji", emoji);
        value.put("idempotencyKey", idempotencyKey);
        value.put("createdAt", createdAt);
        value.put("attemptCount", attemptCount);
        value.put("stateBefore", stateBefore);
        value.put("reactionCountBefore", reactionCountBefore);
        value.put("pinnedAtBefore", pinnedAtBefore);
        return value;
    }

    private static JsonAdapter<Map<String, Object>> adapter(Moshi moshi) {
        Type mapType = Types.newParameterizedType(Map.class, String.class, Object.class);
        return moshi.adapter(mapType);
    }

    private static String string(Map<String, Object> value, String key) throws IOException {
        Object item = value.get(key);
        if (!(item instanceof String text) || text.isBlank()) throw new IOException("invalid " + key);
        return text;
    }

    @Nullable
    private static String nullableString(Map<String, Object> value, String key) throws IOException {
        Object item = value.get(key);
        if (item == null) return null;
        if (!(item instanceof String text)) throw new IOException("invalid " + key);
        return text;
    }

    private static long number(Map<String, Object> value, String key, long fallback) throws IOException {
        Object item = value.get(key);
        if (item instanceof Number number) return number.longValue();
        if (item == null && fallback >= 0) return fallback;
        throw new IOException("invalid " + key);
    }

    private static int integer(Map<String, Object> value, String key, int fallback) throws IOException {
        long parsed = number(value, key, fallback);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) throw new IOException("invalid " + key);
        return (int) parsed;
    }

    private static boolean bool(Map<String, Object> value, String key) throws IOException {
        Object item = value.get(key);
        if (item instanceof Boolean parsed) return parsed;
        throw new IOException("invalid " + key);
    }
}
