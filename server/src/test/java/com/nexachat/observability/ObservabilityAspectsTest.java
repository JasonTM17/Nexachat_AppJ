package com.nexachat.observability;

import com.nexachat.auth.RateLimitService;
import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallSession;
import com.nexachat.call.CallState;
import com.nexachat.message.MessageRepository;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.notification.PushResult;
import com.nexachat.rag.AskMode;
import com.nexachat.rag.AskResponse;
import com.nexachat.summary.PreparedSummaryProvider;
import com.nexachat.summary.SummaryGeneration;
import com.nexachat.summary.SummaryProvider;
import com.nexachat.summary.SummaryResponse;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservabilityAspectsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ObservabilityMetrics metrics = new ObservabilityMetrics(registry);

    @Test
    void messageSendRecordsCreatedAndReplay() {
        MessageMetricsAspect aspect = new MessageMetricsAspect(metrics);
        MessageResponse text = mock(MessageResponse.class);
        when(text.type()).thenReturn("TEXT");

        aspect.recordSend(new MessageService.SendResult(text, true));
        aspect.recordSend(new MessageService.SendResult(text, false));

        assertThat(registry.get(ObservabilityMetrics.MESSAGES_SENT)
                .tag("type", "TEXT").tag("outcome", "CREATED").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.MESSAGES_SENT)
                .tag("type", "TEXT").tag("outcome", "REPLAY").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.OUTBOX_REPLAYS).counter().count()).isEqualTo(1);
    }

    @Test
    void idempotencyReplayIsCountedOnlyWhenTheActionDidNotRun() throws Throwable {
        IdempotencyMetricsAspect aspect = new IdempotencyMetricsAspect(metrics);
        AtomicBoolean actionRan = new AtomicBoolean();

        Object[] replayArgs = args("key", () -> actionRan.set(true));
        ProceedingJoinPoint replay = mock(ProceedingJoinPoint.class);
        when(replay.getArgs()).thenReturn(replayArgs);
        when(replay.proceed(replayArgs)).thenReturn(null);
        aspect.recordReplay(replay);

        assertThat(actionRan).isFalse();
        assertThat(registry.get(ObservabilityMetrics.IDEMPOTENCY_REPLAYS).counter().count())
                .isEqualTo(1);

        Object[] firstAttemptArgs = args("key", () -> actionRan.set(true));
        ProceedingJoinPoint firstAttempt = mock(ProceedingJoinPoint.class);
        when(firstAttempt.getArgs()).thenReturn(firstAttemptArgs);
        when(firstAttempt.proceed(firstAttemptArgs)).thenAnswer(invocation -> {
            ((Runnable) firstAttemptArgs[4]).run();
            return null;
        });
        aspect.recordReplay(firstAttempt);

        assertThat(actionRan).isTrue();
        assertThat(registry.get(ObservabilityMetrics.IDEMPOTENCY_REPLAYS).counter().count())
                .isEqualTo(1);
    }

    @Test
    void idempotencyFailureIsNotCountedAsReplay() throws Throwable {
        IdempotencyMetricsAspect aspect = new IdempotencyMetricsAspect(metrics);
        Object[] args = args("key", () -> {
        });
        ProceedingJoinPoint failing = mock(ProceedingJoinPoint.class);
        when(failing.getArgs()).thenReturn(args);
        when(failing.proceed(args)).thenThrow(new IllegalStateException("db down"));

        try {
            aspect.recordReplay(failing);
        } catch (IllegalStateException ignored) {
            // expected
        }

        assertThat(registry.get(ObservabilityMetrics.IDEMPOTENCY_REPLAYS).counter().count())
                .isEqualTo(0);
    }

    @Test
    void pushResultsAreClassified() throws Throwable {
        FcmPushMetricsAspect aspect = new FcmPushMetricsAspect(metrics);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(CompletableFuture.completedFuture(
                PushResult.success("mock-id")));

        aspect.recordPushResult(joinPoint);

        assertThat(registry.get(ObservabilityMetrics.PUSH_SENDS)
                .tag("result", "SUCCESS").counter().count()).isEqualTo(1);
    }

    @Test
    void callTransitionsUseBoundedStateTags() {
        CallStateMetricsAspect aspect = new CallStateMetricsAspect(metrics);
        CallSession ringing = CallSession.ringing(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now());
        CallSession active = new CallSession(ringing.callId(), ringing.conversationId(),
                ringing.callerId(), ringing.calleeId(), CallState.ACTIVE, null,
                ringing.startedAt(), Instant.now(), null, null);
        CallRegistry.Transition transition =
                new CallRegistry.Transition(active, true, false, ringing);
        JoinPoint joinPoint = mock(JoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("accept");

        aspect.recordTransition(joinPoint, transition);

        assertThat(registry.get(ObservabilityMetrics.CALL_TRANSITIONS)
                .tag("action", "accept").tag("to", "ACTIVE").counter().count()).isEqualTo(1);
    }

    @Test
    void ragAndSummaryRequestsUseModeAndProviderTags() {
        AiMetricsAspect aspect = new AiMetricsAspect(metrics, "lexical");
        AskResponse ask = new AskResponse("answer", List.of(), AskMode.LLM,
                new AskResponse.Usage(1, 100, false));

        aspect.recordRagRequest(ask);

        assertThat(registry.get(ObservabilityMetrics.RAG_REQUESTS)
                .tag("mode", "LLM").tag("provider", "lexical").counter().count()).isEqualTo(1);

        SummaryResponse summary = mock(SummaryResponse.class);
        when(summary.generatedBy()).thenReturn("LLM");
        when(summary.cached()).thenReturn(false);
        when(summary.fallbackReason()).thenReturn("LLM_TIMEOUT");

        aspect.recordSummaryRequest(summary);

        assertThat(registry.get(ObservabilityMetrics.SUMMARY_REQUESTS)
                .tag("provider", "LLM").tag("mode", "GENERATE")
                .tag("fallback", "LLM_TIMEOUT").counter().count()).isEqualTo(1);
    }

    @Test
    void ragLlmLatencyRecordsFailureOnNullAnswer() throws Throwable {
        AiMetricsAspect aspect = new AiMetricsAspect(metrics, "lexical");
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(null);

        aspect.recordRagLlmLatency(joinPoint);

        assertThat(registry.get(ObservabilityMetrics.LLM_DURATION)
                .tag("source", "rag").tag("outcome", "FAILURE").timer().count()).isEqualTo(1);
    }

    @Test
    void summaryLlmLatencySkipsTheLocalProvider() throws Throwable {
        AiMetricsAspect aspect = new AiMetricsAspect(metrics, "lexical");
        SummaryProvider local = mock(SummaryProvider.class);
        when(local.id()).thenReturn("LOCAL");
        PreparedSummaryProvider prepared = new PreparedSummaryProvider(local, "LOCAL", null);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[] { prepared });
        when(joinPoint.proceed()).thenReturn(mock(SummaryGeneration.class));

        aspect.recordSummaryLlmLatency(joinPoint);

        assertThat(registry.find(ObservabilityMetrics.LLM_DURATION)
                .tag("source", "summary").timer()).isNull();
    }

    @Test
    void rateLimitRejectionsUseTheRulePrefixOnly() {
        RateLimitMetricsAspect aspect = new RateLimitMetricsAspect(metrics);
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[] { "message-send|user:" + UUID.randomUUID() });

        aspect.recordRejection(joinPoint, new RateLimitService.Consumption(false, 5));

        assertThat(registry.get(ObservabilityMetrics.RATE_LIMIT_REJECTIONS)
                .tag("reason", "message-send").counter().count()).isEqualTo(1);
    }

    @Test
    void rateLimitRejectionsCollapseIdentityAndUnknownPartsToBoundedTags() {
        RateLimitMetricsAspect aspect = new RateLimitMetricsAspect(metrics);
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();

        recordRejection(aspect, "call:invite:" + firstUser);
        recordRejection(aspect, "call:invite:" + secondUser);
        recordRejection(aspect, "stomp:messages:" + firstUser);
        recordRejection(aspect, "custom-rule|user:" + firstUser);

        assertThat(registry.get(ObservabilityMetrics.RATE_LIMIT_REJECTIONS)
                .tag("reason", "call:invite").counter().count()).isEqualTo(2);
        assertThat(registry.get(ObservabilityMetrics.RATE_LIMIT_REJECTIONS)
                .tag("reason", "stomp:messages").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.RATE_LIMIT_REJECTIONS)
                .tag("reason", "OTHER").counter().count()).isEqualTo(1);

        Set<String> reasons = registry.getMeters().stream()
                .filter(meter -> ObservabilityMetrics.RATE_LIMIT_REJECTIONS.equals(meter.getId().getName()))
                .map(meter -> meter.getId().getTag("reason"))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        assertThat(reasons).containsExactlyInAnyOrder("call:invite", "stomp:messages", "OTHER");
        assertThat(reasons).doesNotContain(firstUser.toString(), secondUser.toString());
    }

    private static void recordRejection(RateLimitMetricsAspect aspect, String key) {
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[] { key });
        aspect.recordRejection(joinPoint, new RateLimitService.Consumption(false, 5));
    }

    @Test
    void authFailuresUseThePreciseReason() {
        AuthFailureMetricsAspect aspect = new AuthFailureMetricsAspect(metrics);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        request.setAttribute("nexachat.authErrorCode", "TOKEN_EXPIRED");
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[] { request });

        aspect.recordAuthenticationFailure(joinPoint);

        assertThat(registry.get(ObservabilityMetrics.AUTH_FAILURES)
                .tag("reason", "TOKEN_EXPIRED").counter().count()).isEqualTo(1);
    }

    @Test
    void repositoryTimingRecordsSuccessAndFailure() throws Throwable {
        RepositoryTimingAspect aspect = new RepositoryTimingAspect(
                metrics, new ObservabilityProperties(1L));
        ProceedingJoinPoint success = mock(ProceedingJoinPoint.class);
        Signature successSignature = signature("MessageRepository", "findRowsAfter");
        when(success.getSignature()).thenReturn(successSignature);
        when(success.proceed()).thenReturn(null);

        aspect.recordRepositoryCall(success);

        assertThat(registry.get(ObservabilityMetrics.REPOSITORY_CALLS)
                .tag("method", "MessageRepository.findRowsAfter")
                .tag("outcome", "SUCCESS").timer().count()).isEqualTo(1);

        ProceedingJoinPoint failure = mock(ProceedingJoinPoint.class);
        Signature failureSignature = signature("MessageRepository", "save");
        when(failure.getSignature()).thenReturn(failureSignature);
        when(failure.proceed()).thenThrow(new IllegalStateException("db down"));

        try {
            aspect.recordRepositoryCall(failure);
        } catch (IllegalStateException ignored) {
            // expected
        }

        assertThat(registry.get(ObservabilityMetrics.REPOSITORY_CALLS)
                .tag("method", "MessageRepository.save")
                .tag("outcome", "FAILURE").timer().count()).isEqualTo(1);
    }

    @Test
    void systemMessagesAreCountedSeparately() {
        MessageMetricsAspect aspect = new MessageMetricsAspect(metrics);
        aspect.recordSystemMessage(mock(MessageResponse.class));

        assertThat(registry.get(ObservabilityMetrics.MESSAGES_SENT)
                .tag("type", "SYSTEM").tag("outcome", "CREATED").counter().count()).isEqualTo(1);
    }

    @Test
    void pushErrorAndUnregisteredAreClassified() throws Throwable {
        FcmPushMetricsAspect aspect = new FcmPushMetricsAspect(metrics);

        ProceedingJoinPoint failure = mock(ProceedingJoinPoint.class);
        when(failure.proceed()).thenReturn(CompletableFuture.completedFuture(
                PushResult.failure("INVALID", false)));
        aspect.recordPushResult(failure);

        ProceedingJoinPoint unregistered = mock(ProceedingJoinPoint.class);
        when(unregistered.proceed()).thenReturn(CompletableFuture.completedFuture(
                PushResult.unregistered("UNREGISTERED")));
        aspect.recordPushResult(unregistered);

        assertThat(registry.get(ObservabilityMetrics.PUSH_SENDS)
                .tag("result", "FAILURE").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.PUSH_SENDS)
                .tag("result", "UNREGISTERED").counter().count()).isEqualTo(1);
    }

    @Test
    void callTransitionOptionalIsRecorded() {
        CallStateMetricsAspect aspect = new CallStateMetricsAspect(metrics);
        CallSession ringing = CallSession.ringing(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now());
        CallSession ended = new CallSession(ringing.callId(), ringing.conversationId(),
                ringing.callerId(), ringing.calleeId(), CallState.ENDED,
                com.nexachat.call.CallEndReason.COMPLETED, ringing.startedAt(),
                Instant.now(), Instant.now(), 10L);
        CallRegistry.Transition transition =
                new CallRegistry.Transition(ended, true, false, ringing);
        JoinPoint joinPoint = mock(JoinPoint.class);
        Signature signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getName()).thenReturn("end");

        aspect.recordTransition(joinPoint, Optional.of(transition));

        assertThat(registry.get(ObservabilityMetrics.CALL_TRANSITIONS)
                .tag("action", "end").tag("to", "ENDED")
                .tag("reason", "COMPLETED").counter().count()).isEqualTo(1);
    }

    @Test
    void accessDeniedIsCountedAsForbidden() {
        AuthFailureMetricsAspect aspect = new AuthFailureMetricsAspect(metrics);
        aspect.recordAccessDenied(mock(JoinPoint.class));

        assertThat(registry.get(ObservabilityMetrics.AUTH_FAILURES)
                .tag("reason", "FORBIDDEN").counter().count()).isEqualTo(1);
    }

    @Test
    void allowedRateLimitIsNotCounted() {
        RateLimitMetricsAspect aspect = new RateLimitMetricsAspect(metrics);
        JoinPoint joinPoint = mock(JoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[] { "message-send|user:1" });

        aspect.recordRejection(joinPoint, new RateLimitService.Consumption(true, 0));

        assertThat(registry.find(ObservabilityMetrics.RATE_LIMIT_REJECTIONS).counter().count())
                .isEqualTo(0.0);
    }

    @Test
    void repositorySlowCallLogsMethodNameOnly() throws Throwable {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RepositoryTimingAspect.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            RepositoryTimingAspect aspect = new RepositoryTimingAspect(
                    metrics, new ObservabilityProperties(1L));
            ProceedingJoinPoint slow = mock(ProceedingJoinPoint.class);
            Signature slowSignature = signature("MessageRepository", "findRowsAfter");
            when(slow.getSignature()).thenReturn(slowSignature);
            // Deliberate delay: the fake call has to exceed the 1 ms slow-call
            // threshold, which is the behaviour under test.
            when(slow.proceed()).thenAnswer(invocation -> {
                Thread.sleep(5L);
                return null;
            });

            aspect.recordRepositoryCall(slow);

            assertThat(appender.list).isNotEmpty();
            String message = appender.list.get(0).getFormattedMessage();
            assertThat(message).contains("Slow repository call MessageRepository.findRowsAfter");
            assertThat(message).doesNotContain("secret-argument");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void stompListenerCountsEveryLifecycleEvent() {
        StompMetricsListener listener = new StompMetricsListener(metrics);

        listener.onConnect(mock(org.springframework.web.socket.messaging.SessionConnectEvent.class));
        listener.onSubscribe(mock(org.springframework.web.socket.messaging.SessionSubscribeEvent.class));
        listener.onUnsubscribe(mock(org.springframework.web.socket.messaging.SessionUnsubscribeEvent.class));
        listener.onDisconnect(mock(org.springframework.web.socket.messaging.SessionDisconnectEvent.class));

        assertThat(registry.get(ObservabilityMetrics.STOMP_EVENTS)
                .tag("event", "CONNECT").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.STOMP_EVENTS)
                .tag("event", "SUBSCRIBE").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.STOMP_EVENTS)
                .tag("event", "UNSUBSCRIBE").counter().count()).isEqualTo(1);
        assertThat(registry.get(ObservabilityMetrics.STOMP_EVENTS)
                .tag("event", "DISCONNECT").counter().count()).isEqualTo(1);
    }

    private static Object[] args(String key, Runnable action) {
        return new Object[] { UUID.randomUUID(), key, "POST /endpoint", "payload", action };
    }

    private static Signature signature(String declaringType, String method) {
        Signature signature = mock(Signature.class);
        when(signature.getDeclaringType()).thenReturn(MessageRepository.class);
        when(signature.getName()).thenReturn(method);
        return signature;
    }
}
