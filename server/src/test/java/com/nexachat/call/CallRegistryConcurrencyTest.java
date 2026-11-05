package com.nexachat.call;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CallRegistryConcurrencyTest {

    @Test
    void twoHundredRacingInvitesAndTerminalFramesKeepOneReservationAndOneTerminalTransition() throws Exception {
        Instant now = Instant.parse("2026-11-05T08:00:00Z");
        CallRegistry registry = new CallRegistry(Clock.fixed(now, ZoneOffset.UTC), 45_000);
        UUID conversationId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        UUID calleeId = UUID.randomUUID();
        ExecutorService executor = Executors.newFixedThreadPool(200);
        CountDownLatch ready = new CountDownLatch(200);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> inviteResults = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                UUID callId = UUID.randomUUID();
                inviteResults.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Invite race gate timed out");
                    }
                    try {
                        CallRegistry.Invitation invitation = registry.invite(
                                callId, conversationId, callerId, calleeId, now);
                        return invitation.busy() ? null : callId;
                    } catch (CallException rejected) {
                        return null;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<UUID> winners = new ArrayList<>();
            for (Future<UUID> result : inviteResults) {
                UUID winner = result.get(10, TimeUnit.SECONDS);
                if (winner != null) {
                    winners.add(winner);
                }
            }
            assertThat(winners).hasSize(1);
            UUID activeCallId = winners.get(0);
            assertThat(registry.activeCallForUser(callerId).orElseThrow().callId()).isEqualTo(activeCallId);
            assertThat(registry.activeCallForUser(calleeId).orElseThrow().callId()).isEqualTo(activeCallId);

            registry.accept(activeCallId, conversationId, calleeId, now.plusSeconds(1));
            CountDownLatch finishReady = new CountDownLatch(200);
            CountDownLatch finishStart = new CountDownLatch(1);
            AtomicInteger terminalTransitions = new AtomicInteger();
            List<Future<?>> terminalResults = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                terminalResults.add(executor.submit(() -> {
                    finishReady.countDown();
                    if (!finishStart.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Terminal race gate timed out");
                    }
                    try {
                        CallRegistry.Transition transition = registry.end(
                                activeCallId, conversationId, callerId, now.plusSeconds(2));
                        if (transition.session().state() == CallState.ENDED) {
                            terminalTransitions.incrementAndGet();
                        }
                    } catch (CallException ignored) {
                        // All losing terminal frames are rejected after the first transition.
                    }
                    return null;
                }));
            }
            assertThat(finishReady.await(10, TimeUnit.SECONDS)).isTrue();
            finishStart.countDown();
            for (Future<?> result : terminalResults) {
                result.get(10, TimeUnit.SECONDS);
            }

            assertThat(terminalTransitions).hasValue(1);
            assertThat(registry.find(activeCallId).orElseThrow().state()).isEqualTo(CallState.ENDED);
            assertThat(registry.activeCallForUser(callerId)).isEmpty();
            assertThat(registry.activeCallForUser(calleeId)).isEmpty();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
