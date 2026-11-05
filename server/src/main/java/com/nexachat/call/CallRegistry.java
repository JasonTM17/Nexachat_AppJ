package com.nexachat.call;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Single-node call state with atomic user reservations and call transitions. */
@Component
public class CallRegistry {

    private final Clock clock;
    private final long ringTimeoutMillis;
    private final Map<UUID, CallSession> calls = new HashMap<>();
    private final Map<UUID, UUID> activeCallByUser = new HashMap<>();

    @Autowired
    public CallRegistry(@Value("${nexachat.calls.ring-timeout-ms:45000}") long ringTimeoutMillis) {
        this(Clock.systemUTC(), ringTimeoutMillis);
    }

    CallRegistry(Clock clock, long ringTimeoutMillis) {
        if (ringTimeoutMillis < 1) {
            throw new IllegalArgumentException("Ring timeout must be positive");
        }
        this.clock = clock;
        this.ringTimeoutMillis = ringTimeoutMillis;
    }

    public synchronized Invitation invite(UUID callId, UUID conversationId, UUID callerId,
                                          UUID calleeId, Instant now) {
        if (calls.containsKey(callId)) {
            throw CallException.invalidState();
        }
        if (activeCallByUser.containsKey(callerId)) {
            throw CallException.invalidState();
        }
        CallSession ringing = CallSession.ringing(callId, conversationId, callerId, calleeId, now);
        if (activeCallByUser.containsKey(calleeId)) {
            CallSession busy = CallStateMachine.end(ringing, CallEndReason.BUSY, now);
            calls.put(callId, busy);
            return new Invitation(busy, true);
        }
        calls.put(callId, ringing);
        activeCallByUser.put(callerId, callId);
        activeCallByUser.put(calleeId, callId);
        return new Invitation(ringing, false);
    }

    public synchronized Transition accept(UUID callId, UUID conversationId, UUID actorId, Instant now) {
        CallSession current = requireParticipant(callId, conversationId, actorId);
        if (!current.calleeId().equals(actorId) || current.state() != CallState.RINGING) {
            throw CallException.invalidState();
        }
        if (!now.isBefore(current.startedAt().plusMillis(ringTimeoutMillis))) {
            return finish(current, CallEndReason.MISSED, now, true);
        }
        CallSession accepted = CallStateMachine.accept(current, now);
        calls.put(callId, accepted);
        return new Transition(accepted, true, false);
    }

    public synchronized Transition reject(UUID callId, UUID conversationId, UUID actorId, Instant now) {
        CallSession current = requireParticipant(callId, conversationId, actorId);
        if (!current.calleeId().equals(actorId) || current.state() != CallState.RINGING) {
            throw CallException.invalidState();
        }
        return finish(current, CallEndReason.REJECTED, now, false);
    }

    public synchronized Transition cancel(UUID callId, UUID conversationId, UUID actorId, Instant now) {
        CallSession current = requireParticipant(callId, conversationId, actorId);
        if (!current.callerId().equals(actorId) || current.state() != CallState.RINGING) {
            throw CallException.invalidState();
        }
        return finish(current, CallEndReason.CANCELLED, now, false);
    }

    public synchronized Transition end(UUID callId, UUID conversationId, UUID actorId, Instant now) {
        CallSession current = requireParticipant(callId, conversationId, actorId);
        if (current.state() != CallState.ACTIVE) {
            throw CallException.invalidState();
        }
        return finish(current, CallEndReason.COMPLETED, now, false);
    }

    public synchronized Optional<Transition> timeout(UUID callId, Instant now) {
        CallSession current = calls.get(callId);
        if (current == null || current.state() != CallState.RINGING
                || now.isBefore(current.startedAt().plusMillis(ringTimeoutMillis))) {
            return Optional.empty();
        }
        return Optional.of(finish(current, CallEndReason.MISSED, now, true));
    }

    public synchronized Optional<Transition> failForUser(UUID userId, Instant now) {
        UUID callId = activeCallByUser.get(userId);
        if (callId == null) {
            return Optional.empty();
        }
        CallSession current = calls.get(callId);
        if (current == null || current.state() == CallState.ENDED) {
            activeCallByUser.remove(userId, callId);
            return Optional.empty();
        }
        return Optional.of(finish(current, CallEndReason.FAILED, now, false));
    }

    public synchronized UUID mediaPeer(UUID callId, UUID conversationId, UUID actorId) {
        CallSession session = requireParticipant(callId, conversationId, actorId);
        if (session.state() != CallState.ACTIVE) {
            throw CallException.invalidState();
        }
        return session.peerOf(actorId);
    }

    public synchronized CallSession requireParticipant(UUID callId, UUID conversationId, UUID actorId) {
        CallSession session = calls.get(callId);
        if (session == null || !session.conversationId().equals(conversationId)
                || session.peerOf(actorId) == null || session.state() == CallState.ENDED) {
            throw CallException.notFound();
        }
        return session;
    }

    public synchronized Optional<CallSession> find(UUID callId) {
        return Optional.ofNullable(calls.get(callId));
    }

    public synchronized Optional<CallSession> activeCallForUser(UUID userId) {
        UUID callId = activeCallByUser.get(userId);
        return callId == null ? Optional.empty() : Optional.ofNullable(calls.get(callId));
    }

    public Instant now() {
        return clock.instant();
    }

    public long ringTimeoutMillis() {
        return ringTimeoutMillis;
    }

    private Transition finish(CallSession current, CallEndReason reason, Instant now, boolean timedOut) {
        CallSession ended = CallStateMachine.end(current, reason, now);
        calls.put(ended.callId(), ended);
        activeCallByUser.remove(ended.callerId(), ended.callId());
        activeCallByUser.remove(ended.calleeId(), ended.callId());
        return new Transition(ended, true, timedOut);
    }

    public record Invitation(CallSession session, boolean busy) {
    }

    public record Transition(CallSession session, boolean changed, boolean timedOut) {
    }
}
