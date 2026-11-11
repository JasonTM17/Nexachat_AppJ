package com.nexachat.call;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Single-node call state with atomic user reservations and call transitions. */
@Component
public class CallRegistry {

    private static final Duration TERMINAL_RETENTION = Duration.ofMinutes(5);

    private final Clock clock;
    private final long ringTimeoutMillis;
    private final Map<UUID, CallSession> calls = new HashMap<>();
    private final Map<UUID, UUID> activeCallByUser = new HashMap<>();
    private final Map<UUID, CallSignal.Sdp> offers = new HashMap<>();

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
        evictExpiredTerminals(now);
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
        evictExpiredTerminals(now);
        CallSession current = requireParticipant(callId, conversationId, actorId);
        if (!current.calleeId().equals(actorId) || current.state() != CallState.RINGING) {
            throw CallException.invalidState();
        }
        if (!now.isBefore(current.startedAt().plusMillis(ringTimeoutMillis))) {
            return finish(current, CallEndReason.MISSED, now, true);
        }
        CallSession accepted = CallStateMachine.accept(current, now);
        calls.put(callId, accepted);
        return new Transition(accepted, true, false, current);
    }

    public synchronized Transition reject(UUID callId, UUID conversationId, UUID actorId, Instant now) {
        evictExpiredTerminals(now);
        CallSession current = requireParticipant(callId, conversationId, actorId);
        if (!current.calleeId().equals(actorId) || current.state() != CallState.RINGING) {
            throw CallException.invalidState();
        }
        return finish(current, CallEndReason.REJECTED, now, false);
    }

    public synchronized Transition cancel(UUID callId, UUID conversationId, UUID actorId, Instant now) {
        evictExpiredTerminals(now);
        CallSession current = requireParticipant(callId, conversationId, actorId);
        if (!current.callerId().equals(actorId) || current.state() != CallState.RINGING) {
            throw CallException.invalidState();
        }
        return finish(current, CallEndReason.CANCELLED, now, false);
    }

    public synchronized Transition end(UUID callId, UUID conversationId, UUID actorId, Instant now) {
        evictExpiredTerminals(now);
        CallSession current = requireParticipant(callId, conversationId, actorId);
        if (current.state() != CallState.ACTIVE) {
            throw CallException.invalidState();
        }
        return finish(current, CallEndReason.COMPLETED, now, false);
    }

    public synchronized Optional<Transition> timeout(UUID callId, Instant now) {
        evictExpiredTerminals(now);
        CallSession current = calls.get(callId);
        if (current == null || current.state() != CallState.RINGING
                || now.isBefore(current.startedAt().plusMillis(ringTimeoutMillis))) {
            return Optional.empty();
        }
        return Optional.of(finish(current, CallEndReason.MISSED, now, true));
    }

    public synchronized Optional<Transition> failForUser(UUID userId, Instant now) {
        evictExpiredTerminals(now);
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
        evictExpiredTerminals(clock.instant());
        CallSession session = calls.get(callId);
        if (session == null || !session.conversationId().equals(conversationId)
                || session.peerOf(actorId) == null || session.state() == CallState.ENDED) {
            throw CallException.notFound();
        }
        return session;
    }

    public synchronized Optional<CallSession> find(UUID callId) {
        evictExpiredTerminals(clock.instant());
        return Optional.ofNullable(calls.get(callId));
    }

    public synchronized Optional<CallSession> activeCallForUser(UUID userId) {
        evictExpiredTerminals(clock.instant());
        UUID callId = activeCallByUser.get(userId);
        return callId == null ? Optional.empty() : Optional.ofNullable(calls.get(callId));
    }

    public synchronized List<CallSession> activeCallSessions() {
        evictExpiredTerminals(clock.instant());
        return calls.values().stream()
                .filter(session -> session.state() == CallState.ACTIVE)
                .toList();
    }

    public Instant now() {
        return clock.instant();
    }

    public long ringTimeoutMillis() {
        return ringTimeoutMillis;
    }

    public synchronized void rollback(Invitation invitation) {
        CallSession current = calls.get(invitation.session().callId());
        if (current != invitation.session()) {
            return;
        }
        calls.remove(current.callId());
        activeCallByUser.remove(current.callerId(), current.callId());
        activeCallByUser.remove(current.calleeId(), current.callId());
    }

    public synchronized void rollback(Transition transition) {
        CallSession current = calls.get(transition.session().callId());
        if (current != transition.session()) {
            return;
        }
        CallSession previous = transition.previous();
        calls.put(previous.callId(), previous);
        if (previous.state() == CallState.ENDED) {
            activeCallByUser.remove(previous.callerId(), previous.callId());
            activeCallByUser.remove(previous.calleeId(), previous.callId());
        } else {
            activeCallByUser.put(previous.callerId(), previous.callId());
            activeCallByUser.put(previous.calleeId(), previous.callId());
        }
    }

    public synchronized void saveOffer(UUID callId, CallSignal.Sdp sdp) {
        if (sdp != null) {
            offers.put(callId, sdp);
        }
    }

    public synchronized Optional<CallSignal.Sdp> findOffer(UUID callId) {
        evictExpiredTerminals(clock.instant());
        return Optional.ofNullable(offers.get(callId));
    }

    private void evictExpiredTerminals(Instant now) {
        calls.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().state() == CallState.ENDED
                    && !now.isBefore(entry.getValue().endedAt().plus(TERMINAL_RETENTION));
            if (expired) {
                offers.remove(entry.getKey());
            }
            return expired;
        });
    }

    private Transition finish(CallSession current, CallEndReason reason, Instant now, boolean timedOut) {
        CallSession ended = CallStateMachine.end(current, reason, now);
        calls.put(ended.callId(), ended);
        activeCallByUser.remove(ended.callerId(), ended.callId());
        activeCallByUser.remove(ended.calleeId(), ended.callId());
        return new Transition(ended, true, timedOut, current);
    }

    public record Invitation(CallSession session, boolean busy) {
    }

    public record Transition(CallSession session, boolean changed, boolean timedOut, CallSession previous) {
    }
}
