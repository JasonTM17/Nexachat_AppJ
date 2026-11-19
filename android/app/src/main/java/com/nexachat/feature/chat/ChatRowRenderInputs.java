package com.nexachat.feature.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.data.ChatMessage;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Memo of the inputs that fully determine the chat message rows.
 *
 * <p>Repository streams re-emit the same snapshot on every tick (presence,
 * read receipts, expiry polls), and each emission used to re-run the full row
 * mapper — enough repeated main-thread work on large histories to trigger
 * input-dispatch ANRs. Identical inputs must not rebuild.
 */
public final class ChatRowRenderInputs {
    private boolean hasLast;
    @NonNull private List<ChatMessage> messages = List.of();
    private boolean group;
    private long peerReadSeq;
    private long peerDeliveredSeq;
    @Nullable private String pulsingMessageId;
    private int dividerIndex;
    private int dividerCount;
    @NonNull private Set<String> starredIds = Set.of();
    private long dayStartEpochMs = Long.MIN_VALUE;

    /**
     * Records the inputs and reports whether the rows must be rebuilt for them.
     * {@code nowEpochMs} is quantized to its local day: separator labels only
     * depend on which day "now" is in, so a rebuild per day boundary is enough.
     */
    public boolean requiresRebuild(
            @NonNull List<ChatMessage> messages,
            boolean group,
            long peerReadSeq,
            long peerDeliveredSeq,
            @Nullable String pulsingMessageId,
            int dividerIndex,
            int dividerCount,
            @NonNull Set<String> starredIds,
            long nowEpochMs
    ) {
        long dayStart = localDayStartEpochMs(nowEpochMs);
        boolean changed = !hasLast
                || !Objects.equals(this.messages, messages)
                || this.group != group
                || this.peerReadSeq != peerReadSeq
                || this.peerDeliveredSeq != peerDeliveredSeq
                || !Objects.equals(this.pulsingMessageId, pulsingMessageId)
                || this.dividerIndex != dividerIndex
                || this.dividerCount != dividerCount
                || !Objects.equals(this.starredIds, starredIds)
                || this.dayStartEpochMs != dayStart;
        if (changed) {
            this.hasLast = true;
            this.messages = messages;
            this.group = group;
            this.peerReadSeq = peerReadSeq;
            this.peerDeliveredSeq = peerDeliveredSeq;
            this.pulsingMessageId = pulsingMessageId;
            this.dividerIndex = dividerIndex;
            this.dividerCount = dividerCount;
            this.starredIds = starredIds;
            this.dayStartEpochMs = dayStart;
        }
        return changed;
    }

    /**
     * Forces the next {@code requiresRebuild} to pass: the adapter content was
     * replaced through another path (view recreation, search results).
     */
    public void invalidate() {
        hasLast = false;
    }

    private static long localDayStartEpochMs(long nowEpochMs) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(nowEpochMs);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        return day.getTimeInMillis();
    }
}
