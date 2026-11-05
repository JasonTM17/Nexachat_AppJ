package com.nexachat.summary.engine;

import java.time.Instant;

public record SummaryMessage(
        String id,
        long seq,
        String senderId,
        String senderName,
        String text,
        Instant createdAt,
        Instant editedAt,
        SummarySignals signals
) {
    public SummaryMessage {
        signals = signals == null ? SummarySignals.none() : signals;
    }

    public SummaryMessage(String id, long seq, String senderId, String senderName, String text) {
        this(id, seq, senderId, senderName, text, null, null, SummarySignals.none());
    }
}
