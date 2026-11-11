package com.nexachat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.JwtService;
import com.nexachat.auth.RateLimitService;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.user.UserAuthValidity;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Duration;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompRevocationRaceTest {

    private static final UUID CONVERSATION_ID = UUID.fromString("afec692f-2a7a-4c23-aaf6-aab60c70629e");

    @Test
    void revocationWriteFenceWaitsForReadersAndRemainsHeldUntilTransactionCompletion() throws Exception {
        UUID userId = UUID.randomUUID();
        StompRevocationFence fence = new StompRevocationFence(mock(UserRepository.class));
        CountDownLatch readerAttempting = new CountDownLatch(1);
        CountDownLatch readerAcquired = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            readerAttempting.countDown();
            try (StompRevocationFence.ReadLease ignored = fence.acquireRead(userId)) {
                readerAcquired.countDown();
            } finally {
                finished.set(true);
            }
        }, "stomp-revocation-fence-test");
        reader.setDaemon(true);

        TransactionSynchronizationManager.initSynchronization();
        boolean[] completed = {false};
        try {
            fence.holdWriteUntilTransactionCompletion(userId);
            reader.start();
            assertThat(readerAttempting.await(2, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(2)).until(() -> reader.getState() == Thread.State.WAITING);
            assertThat(readerAcquired.getCount()).isEqualTo(1);

            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
            completed[0] = true;
            assertThat(readerAcquired.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                if (!completed[0]) {
                    TransactionSynchronizationManager.getSynchronizations().forEach(
                            synchronization -> synchronization.afterCompletion(
                                    org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
                }
                TransactionSynchronizationManager.clearSynchronization();
            }
            if (reader.isAlive()) {
                reader.join(TimeUnit.SECONDS.toMillis(2));
            }
        }
        assertThat(finished).isTrue();
    }

    @Test
    void revocationWriteFenceReleasesAfterTransactionRollback() throws Exception {
        UUID userId = UUID.randomUUID();
        StompRevocationFence fence = new StompRevocationFence(mock(UserRepository.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            fence.holdWriteUntilTransactionCompletion(userId);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    synchronization -> synchronization.afterCompletion(
                            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        CountDownLatch readAcquired = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try (StompRevocationFence.ReadLease ignored = fence.acquireRead(userId)) {
                readAcquired.countDown();
            }
        }, "stomp-rollback-fence-test");
        reader.setDaemon(true);
        reader.start();
        assertThat(readAcquired.await(2, TimeUnit.SECONDS)).isTrue();
        reader.join(TimeUnit.SECONDS.toMillis(2));
    }

    @Test
    void queuedSendIsRejectedWhenResetCommitsBeforeInboundHandlerStarts() {
        UUID userId = UUID.randomUUID();
        String sessionId = "queued-inbound";
        Instant issuedAt = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = Instant.now().plusSeconds(300);
        UserRepository users = mock(UserRepository.class);
        StompSessionRegistry registry = mock(StompSessionRegistry.class);
        when(users.findAuthValidityById(userId)).thenReturn(
                Optional.of(new UserAuthValidity(userId, null)),
                Optional.of(new UserAuthValidity(userId, issuedAt)));
        StompAuthChannelInterceptor interceptor = interceptor(users, registry);
        QueueExecutor executor = new QueueExecutor();
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(executor);
        inbound.addInterceptor(interceptor);
        AtomicBoolean invoked = new AtomicBoolean();
        inbound.subscribe(message -> invoked.set(true));

        assertThat(inbound.send(sendMessage(userId, sessionId, issuedAt, expiresAt))).isTrue();
        assertThat(executor.size()).isEqualTo(1);

        executor.runNext();

        assertThat(invoked).isFalse();
        verify(registry).closeHandshake(sessionId);
    }

    @Test
    void queuedTopicDeliveryIsDroppedWhenResetCommitsBeforeSocketWrite() {
        UUID userId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = Instant.now().plusSeconds(300);
        UserRepository users = mock(UserRepository.class);
        when(users.findAuthValidityById(userId)).thenReturn(
                Optional.of(new UserAuthValidity(userId, null)),
                Optional.of(new UserAuthValidity(userId, null)),
                Optional.of(new UserAuthValidity(userId, null)),
                Optional.of(new UserAuthValidity(userId, issuedAt)));
        StompAuthChannelInterceptor interceptor = interceptor(users, mock(StompSessionRegistry.class));

        QueueExecutor inboundExecutor = new QueueExecutor();
        QueueExecutor outboundExecutor = new QueueExecutor();
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel(inboundExecutor);
        ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel(outboundExecutor);
        ExecutorSubscribableChannel broker = new ExecutorSubscribableChannel(Runnable::run);
        inbound.addInterceptor(interceptor);
        broker.addInterceptor(interceptor);
        outbound.addInterceptor(interceptor);
        broker.subscribe(outbound::send);
        AtomicBoolean delivered = new AtomicBoolean();
        outbound.subscribe(message -> delivered.set(true));
        inbound.subscribe(message -> broker.send(MessageBuilder.withPayload("typing event").build()));

        assertThat(inbound.send(sendMessage(userId, "queued-outbound", issuedAt, expiresAt))).isTrue();
        inboundExecutor.runNext();
        assertThat(outboundExecutor.size()).isEqualTo(1);

        outboundExecutor.runNext();

        assertThat(delivered).isFalse();
    }

    @Test
    void concurrentSocketWritesSerializeBeforeTheDecoratorCanBufferARevokedFrame() throws Exception {
        UUID userId = UUID.randomUUID();
        String sessionId = "decorated-outbound";
        Instant issuedAt = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = Instant.now().plusSeconds(300);
        AtomicBoolean cutoffCommitted = new AtomicBoolean();
        UserRepository users = mock(UserRepository.class);
        when(users.findAuthValidityById(userId)).thenAnswer(ignored -> Optional.of(
                new UserAuthValidity(userId, cutoffCommitted.get() ? issuedAt : null)));
        StompSessionRegistry registry = mock(StompSessionRegistry.class);
        StompRevocationFence fence = new StompRevocationFence(users);
        StompAuthChannelInterceptor interceptor = interceptor(users, registry, fence);
        WebSocketSession socket = mock(WebSocketSession.class);
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        CopyOnWriteArrayList<String> sentPayloads = new CopyOnWriteArrayList<>();
        AtomicBoolean firstSocketCall = new AtomicBoolean(true);
        when(socket.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            TextMessage frame = invocation.getArgument(0);
            if (firstSocketCall.compareAndSet(true, false)) {
                firstWriteStarted.countDown();
                if (!releaseFirstWrite.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release test socket write");
                }
            }
            sentPayloads.add(frame.getPayload());
            return null;
        }).when(socket).sendMessage(any(WebSocketMessage.class));
        ConcurrentWebSocketSessionDecorator decoratedSocket =
                new ConcurrentWebSocketSessionDecorator(socket, 10_000, 64 * 1024);

        MessageChannel channel = mock(MessageChannel.class);
        SubProtocolWebSocketHandler outboundHandler = mock(SubProtocolWebSocketHandler.class);
        Message<?> first = outboundActorMessage(sessionId, userId, issuedAt, expiresAt, "first");
        Message<?> queued = outboundActorMessage(sessionId, userId, issuedAt, expiresAt, "queued");
        CountDownLatch secondAttempting = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        AtomicBoolean secondAccepted = new AtomicBoolean(true);
        AtomicBoolean secondReachedHandler = new AtomicBoolean();
        AtomicBoolean resetAttempting = new AtomicBoolean();
        CountDownLatch resetFinished = new CountDownLatch(1);

        Thread firstSender = new Thread(() -> {
            Message<?> accepted = interceptor.beforeHandle(first, channel, outboundHandler);
            if (accepted == null) {
                return;
            }
            try {
                decoratedSocket.sendMessage(new TextMessage("first"));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            } finally {
                interceptor.afterMessageHandled(first, channel, outboundHandler, null);
            }
        }, "stomp-outbound-first-write");
        firstSender.setDaemon(true);
        firstSender.start();
        assertThat(firstWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();

        Thread secondSender = new Thread(() -> {
            secondAttempting.countDown();
            try {
                Message<?> accepted = interceptor.beforeHandle(queued, channel, outboundHandler);
                secondAccepted.set(accepted != null);
                if (accepted != null) {
                    secondReachedHandler.set(true);
                    try {
                        decoratedSocket.sendMessage(new TextMessage("queued"));
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    } finally {
                        interceptor.afterMessageHandled(queued, channel, outboundHandler, null);
                    }
                }
            } finally {
                secondFinished.countDown();
            }
        }, "stomp-outbound-queued-write");
        secondSender.setDaemon(true);
        secondSender.start();
        assertThat(secondAttempting.await(2, TimeUnit.SECONDS)).isTrue();
        boolean queuedBehindSessionWrite = false;
        boolean secondReachedDuringFirstWrite = false;
        int bufferedDuringFirstWrite = -1;
        try {
            await().atMost(Duration.ofSeconds(2)).until(() -> (secondReachedHandler.get()
                    && decoratedSocket.getBufferSize() > 0)
                    || secondSender.getState() == Thread.State.WAITING);
            secondReachedDuringFirstWrite = secondReachedHandler.get();
            bufferedDuringFirstWrite = decoratedSocket.getBufferSize();
            queuedBehindSessionWrite = !secondReachedDuringFirstWrite
                    && secondSender.getState() == Thread.State.WAITING
                    && bufferedDuringFirstWrite == 0;
        } finally {
            if (!queuedBehindSessionWrite) {
                releaseFirstWrite.countDown();
            }
        }
        assertThat(queuedBehindSessionWrite)
                .withFailMessage("secondReachedHandler=%s, decoratedBufferSize=%s",
                        secondReachedDuringFirstWrite, bufferedDuringFirstWrite)
                .isTrue();

        Thread reset = new Thread(() -> {
            TransactionSynchronizationManager.initSynchronization();
            try {
                resetAttempting.set(true);
                fence.holdWriteUntilTransactionCompletion(userId);
                cutoffCommitted.set(true);
                TransactionSynchronizationManager.getSynchronizations().forEach(
                        synchronization -> synchronization.afterCompletion(
                                org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
            } finally {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.clearSynchronization();
                }
                resetFinished.countDown();
            }
        }, "stomp-reset-writer");
        reset.setDaemon(true);
        reset.start();
        await().atMost(Duration.ofSeconds(2)).until(resetAttempting::get);
        await().atMost(Duration.ofSeconds(2)).until(() -> reset.getState() == Thread.State.WAITING);

        releaseFirstWrite.countDown();
        assertThat(resetFinished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(secondFinished.await(2, TimeUnit.SECONDS)).isTrue();
        firstSender.join(TimeUnit.SECONDS.toMillis(2));
        secondSender.join(TimeUnit.SECONDS.toMillis(2));
        reset.join(TimeUnit.SECONDS.toMillis(2));

        assertThat(cutoffCommitted).isTrue();
        assertThat(secondAccepted).isFalse();
        assertThat(sentPayloads).containsExactly("first");
        assertThat(decoratedSocket.getBufferSize()).isZero();
    }

    @Test
    void rawProtocolErrorWriteCannotBufferAnActorFrameAcrossResetCommit() throws Exception {
        UUID userId = UUID.randomUUID();
        String sessionId = "decorated-protocol-error";
        Instant issuedAt = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = Instant.now().plusSeconds(300);
        AtomicBoolean cutoffCommitted = new AtomicBoolean();
        UserRepository users = mock(UserRepository.class);
        when(users.findAuthValidityById(userId)).thenAnswer(ignored -> Optional.of(
                new UserAuthValidity(userId, cutoffCommitted.get() ? issuedAt : null)));
        StompSessionRegistry registry = mock(StompSessionRegistry.class);
        StompRevocationFence fence = new StompRevocationFence(users);
        StompAuthChannelInterceptor interceptor = interceptor(users, registry, fence);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(sessionId);
        when(socket.isOpen()).thenReturn(true);
        CountDownLatch errorWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseErrorWrite = new CountDownLatch(1);
        CopyOnWriteArrayList<String> sentPayloads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            WebSocketMessage<?> frame = invocation.getArgument(0);
            if (frame instanceof TextMessage text && "ERROR".equals(text.getPayload())) {
                errorWriteStarted.countDown();
                if (!releaseErrorWrite.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("timed out waiting to release test error write");
                }
            }
            sentPayloads.add(((TextMessage) frame).getPayload());
            return null;
        }).when(socket).sendMessage(any(WebSocketMessage.class));
        ConcurrentWebSocketSessionDecorator decoratedSocket =
                new ConcurrentWebSocketSessionDecorator(socket, 10_000, 64 * 1024);
        WebSocketHandler rawProtocolHandler = new AbstractWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
                session.sendMessage(new TextMessage("ERROR"));
                session.close(CloseStatus.PROTOCOL_ERROR);
            }
        };
        WebSocketHandler fencedHandler = new StompSessionWriteLockDecoratorFactory(fence)
                .decorate(rawProtocolHandler);
        AtomicBoolean actorAccepted = new AtomicBoolean(true);
        CountDownLatch actorAttempting = new CountDownLatch(1);
        CountDownLatch actorFinished = new CountDownLatch(1);
        MessageChannel channel = mock(MessageChannel.class);
        SubProtocolWebSocketHandler outboundHandler = mock(SubProtocolWebSocketHandler.class);
        Message<?> actorFrame = outboundActorMessage(sessionId, userId, issuedAt, expiresAt, "actor-frame");

        Thread errorSender = new Thread(() -> {
            try {
                fencedHandler.handleMessage(decoratedSocket, new TextMessage("malformed STOMP"));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }, "stomp-protocol-error-write");
        errorSender.setDaemon(true);
        errorSender.start();
        assertThat(errorWriteStarted.await(2, TimeUnit.SECONDS)).isTrue();

        Thread actorSender = new Thread(() -> {
            actorAttempting.countDown();
            try {
                Message<?> accepted = interceptor.beforeHandle(actorFrame, channel, outboundHandler);
                actorAccepted.set(accepted != null);
                if (accepted != null) {
                    try {
                        decoratedSocket.sendMessage(new TextMessage("actor-frame"));
                    } catch (IOException exception) {
                        throw new AssertionError(exception);
                    } finally {
                        interceptor.afterMessageHandled(actorFrame, channel, outboundHandler, null);
                    }
                }
            } finally {
                actorFinished.countDown();
            }
        }, "stomp-actor-during-protocol-error");
        actorSender.setDaemon(true);
        actorSender.start();
        assertThat(actorAttempting.await(2, TimeUnit.SECONDS)).isTrue();
        Thread reset = null;
        try {
            await().atMost(Duration.ofSeconds(2)).until(() -> actorFinished.getCount() == 0
                    || actorSender.getState() == Thread.State.WAITING);
            boolean actorBlockedBeforeDecorator = actorFinished.getCount() == 1
                    && actorSender.getState() == Thread.State.WAITING
                    && decoratedSocket.getBufferSize() == 0;
            assertThat(actorBlockedBeforeDecorator)
                    .withFailMessage("actorFinished=%s, decoratedBufferSize=%s",
                            actorFinished.getCount() == 0, decoratedSocket.getBufferSize())
                    .isTrue();

            reset = new Thread(() -> {
                TransactionSynchronizationManager.initSynchronization();
                try {
                    fence.holdWriteUntilTransactionCompletion(userId);
                    cutoffCommitted.set(true);
                    TransactionSynchronizationManager.getSynchronizations().forEach(
                            synchronization -> synchronization.afterCompletion(
                                    org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
                } finally {
                    if (TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.clearSynchronization();
                    }
                }
            }, "stomp-reset-during-protocol-error");
            reset.setDaemon(true);
            reset.start();

            await().atMost(Duration.ofSeconds(2)).until(cutoffCommitted::get);
        } finally {
            releaseErrorWrite.countDown();
        }
        assertThat(actorFinished.await(2, TimeUnit.SECONDS)).isTrue();
        errorSender.join(TimeUnit.SECONDS.toMillis(2));
        actorSender.join(TimeUnit.SECONDS.toMillis(2));
        if (reset != null) {
            reset.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertThat(actorAccepted).isFalse();
        assertThat(sentPayloads).containsExactly("ERROR");
        assertThat(decoratedSocket.getBufferSize()).isZero();
    }

    @Test
    void outboundSessionLeaseIsReleasedAfterHandlerFailure() {
        UUID userId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = Instant.now().plusSeconds(300);
        UserRepository users = mock(UserRepository.class);
        when(users.findAuthValidityById(userId)).thenReturn(Optional.of(new UserAuthValidity(userId, null)));
        StompAuthChannelInterceptor interceptor = interceptor(users, mock(StompSessionRegistry.class));
        MessageChannel channel = mock(MessageChannel.class);
        SubProtocolWebSocketHandler outboundHandler = mock(SubProtocolWebSocketHandler.class);
        Message<?> message = outboundActorMessage("failure-session", userId, issuedAt, expiresAt, "payload");

        assertThat(interceptor.beforeHandle(message, channel, outboundHandler)).isSameAs(message);
        interceptor.afterMessageHandled(message, channel, outboundHandler, new IOException("socket failure"));

        assertThat(interceptor.beforeHandle(message, channel, outboundHandler)).isSameAs(message);
        interceptor.afterMessageHandled(message, channel, outboundHandler, null);
    }

    @Test
    void actorMetadataIsInternalWhenBrokerFramesAreTagged() {
        UUID userId = UUID.randomUUID();
        Instant issuedAt = Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiresAt = Instant.now().plusSeconds(300);
        UserRepository users = mock(UserRepository.class);
        when(users.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, null)));
        StompAuthChannelInterceptor interceptor = interceptor(users, mock(StompSessionRegistry.class));
        Message<?> send = sendMessage(userId, "tagging-session", issuedAt, expiresAt);
        MessageChannel channel = mock(MessageChannel.class);
        MessageHandler handler = mock(MessageHandler.class);

        assertThat(interceptor.beforeHandle(send, channel, handler)).isSameAs(send);
        Message<?> brokerFrame = interceptor.preSend(MessageBuilder.withPayload("typing event").build(), channel);
        interceptor.afterMessageHandled(send, channel, handler, null);

        assertThat(brokerFrame.getHeaders().get("nexachat.internal.revocation.actor"))
                .isEqualTo(userId.toString());
        assertThat(brokerFrame.getHeaders().get("nexachat.internal.revocation.issuedAt"))
                .isEqualTo(issuedAt.toString());
        assertThat(StompHeaderAccessor.wrap(brokerFrame)
                .getFirstNativeHeader("nexachat.internal.revocation.actor")).isNull();
    }

    private static StompAuthChannelInterceptor interceptor(UserRepository users,
                                                            StompSessionRegistry registry) {
        return interceptor(users, registry, new StompRevocationFence(users));
    }

    private static StompAuthChannelInterceptor interceptor(UserRepository users,
                                                            StompSessionRegistry registry,
                                                            StompRevocationFence fence) {
        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        when(members.existsByConversation_IdAndUser_Id(eq(CONVERSATION_ID), any(UUID.class))).thenReturn(true);
        return new StompAuthChannelInterceptor(
                mock(JwtService.class), users, members, new RateLimitService(),
                mock(SimpMessagingTemplate.class), new ObjectMapper(),
                30, 10, 60, 10, 120, 10, registry, fence);
    }

    private static Message<byte[]> sendMessage(UUID userId,
                                               String sessionId,
                                               Instant issuedAt,
                                               Instant expiresAt) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        accessor.setDestination("/app/conversations/" + CONVERSATION_ID + "/typing");
        accessor.setUser(new StompUserPrincipal(userId, issuedAt, expiresAt));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<String> outboundActorMessage(String sessionId,
                                                        UUID userId,
                                                        Instant issuedAt,
                                                        Instant expiresAt,
                                                        String payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        Message<String> message = MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
        return MessageBuilder.fromMessage(message)
                .setHeader("nexachat.internal.revocation.actor", userId.toString())
                .setHeader("nexachat.internal.revocation.issuedAt", issuedAt.toString())
                .setHeader("nexachat.internal.revocation.expiresAt", expiresAt.toString())
                .build();
    }

    private static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
