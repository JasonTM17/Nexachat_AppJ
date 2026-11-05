package com.nexachat.call;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "call_records")
public class CallRecord {

    @Id
    @Column(name = "call_id", nullable = false, updatable = false)
    private UUID callId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "caller_id", nullable = false, updatable = false)
    private UUID callerId;

    @Column(name = "callee_id", nullable = false, updatable = false)
    private UUID calleeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 8)
    private CallState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 12)
    private CallEndReason endReason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    protected CallRecord() {
    }

    public static CallRecord from(CallSession session) {
        CallRecord record = new CallRecord();
        record.callId = session.callId();
        record.conversationId = session.conversationId();
        record.callerId = session.callerId();
        record.calleeId = session.calleeId();
        record.update(session);
        return record;
    }

    public void update(CallSession session) {
        state = session.state();
        endReason = session.endReason();
        answeredAt = session.answeredAt();
        endedAt = session.endedAt();
        durationSeconds = session.durationSeconds();
        if (startedAt == null) {
            startedAt = session.startedAt();
        }
    }

    public UUID getCallId() {
        return callId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getCallerId() {
        return callerId;
    }

    public UUID getCalleeId() {
        return calleeId;
    }

    public CallState getState() {
        return state;
    }

    public CallEndReason getEndReason() {
        return endReason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }
}
