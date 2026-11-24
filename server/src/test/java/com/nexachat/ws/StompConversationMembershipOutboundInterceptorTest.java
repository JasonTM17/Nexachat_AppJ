package com.nexachat.ws;

import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.user.UserAuthValidity;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StompConversationMembershipOutboundInterceptorTest {

    private static final String SESSION_ID = "pending-session";

    private final ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
    private final StompSessionRegistry sessionRegistry = mock(StompSessionRegistry.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final com.nexachat.message.MessageRepository messageRepository =
            mock(com.nexachat.message.MessageRepository.class);
    private final com.nexachat.user.UserBlockRepository blockRepository =
            mock(com.nexachat.user.UserBlockRepository.class);
    private final StompConversationMembershipOutboundInterceptor interceptor =
            new StompConversationMembershipOutboundInterceptor(
                    memberRepository, messageRepository, blockRepository,
                    sessionRegistry);

    @Test
    void connectedResponsePassesBeforeSessionBindingCompletes() {
        when(sessionRegistry.isAuthenticatedSession(SESSION_ID)).thenReturn(false);
        Message<byte[]> connected = message(StompCommand.CONNECTED);

        assertThat(interceptor.preSend(connected, channel)).isSameAs(connected);

        verify(sessionRegistry, never()).isAuthenticatedSession(SESSION_ID);
        verifyNoInteractions(memberRepository);
    }

    @Test
    void connectAckAndHeartbeatPassWithoutAnAuthenticatedSession() {
        Message<byte[]> connectAck = message(StompCommand.CONNECTED);
        Message<byte[]> heartbeat = heartbeat();

        assertThat(interceptor.preSend(connectAck, channel)).isSameAs(connectAck);
        assertThat(interceptor.preSend(heartbeat, channel)).isSameAs(heartbeat);

        verify(sessionRegistry, never()).isAuthenticatedSession(SESSION_ID);
        verifyNoInteractions(memberRepository);
    }

    @Test
    void unboundApplicationMessageIsDropped() {
        when(sessionRegistry.isAuthenticatedSession(SESSION_ID)).thenReturn(false);
        Message<byte[]> event = message(StompCommand.MESSAGE);

        assertThat(interceptor.preSend(event, channel)).isNull();

        verify(sessionRegistry).isAuthenticatedSession(SESSION_ID);
        verifyNoInteractions(memberRepository);
    }

    @Test
    void revocationDuringConversationMembershipCheckDropsTheInFlightFrame() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        when(users.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, null)));
        StompSessionRegistry registry = new StompSessionRegistry(users);

        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(SESSION_ID);
        when(socket.isOpen()).thenReturn(true);
        doThrow(new IOException("simulated close failure"))
                .when(socket).close(CloseStatus.POLICY_VIOLATION);
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator)
                registry.decorate(mock(WebSocketHandler.class));
        handler.afterConnectionEstablished(socket);

        StompUserPrincipal principal = new StompUserPrincipal(
                userId, Instant.now().minusSeconds(30), Instant.now().plusSeconds(300));
        registry.onSessionConnected(connectedEvent(principal));
        assertThat(registry.isAuthenticatedSession(SESSION_ID)).isTrue();

        CountDownLatch membershipLookupStarted = new CountDownLatch(1);
        CountDownLatch resumeMembershipLookup = new CountDownLatch(1);
        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        when(members.existsByConversation_IdAndUser_Id(conversationId, userId)).thenAnswer(invocation -> {
            membershipLookupStarted.countDown();
            if (!resumeMembershipLookup.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to resume the outbound membership check");
            }
            return true;
        });
        StompConversationMembershipOutboundInterceptor outbound =
                new StompConversationMembershipOutboundInterceptor(members,
                        mock(com.nexachat.message.MessageRepository.class),
                        mock(com.nexachat.user.UserBlockRepository.class),
                        registry);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Message<?>> delivery = executor.submit(
                    () -> outbound.preSend(conversationMessage(conversationId), channel));
            assertThat(membershipLookupStarted.await(5, TimeUnit.SECONDS)).isTrue();

            registry.onAccessTokensRevoked(new AccessTokensRevokedEvent(userId));
            assertThat(registry.isAuthenticatedSession(SESSION_ID)).isFalse();

            // The mocked socket remains open after close failure, so the outbound gate must reject the frame.
            resumeMembershipLookup.countDown();
            assertThat(delivery.get(5, TimeUnit.SECONDS))
                    .as("a frame authorized before revocation must be dropped after the delayed membership check")
                    .isNull();
        } finally {
            resumeMembershipLookup.countDown();
            executor.shutdownNow();
            registry.shutdown();
        }
    }

    @Test
    void revocationBeforeTransportWriteDropsAnAlreadyAuthorizedFrame() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UserRepository users = mock(UserRepository.class);
        when(users.findAuthValidityById(userId))
                .thenReturn(Optional.of(new UserAuthValidity(userId, null)));
        StompSessionRegistry registry = new StompSessionRegistry(users);

        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(SESSION_ID);
        when(socket.isOpen()).thenReturn(true);
        doThrow(new IOException("simulated close failure"))
                .when(socket).close(CloseStatus.POLICY_VIOLATION);
        AtomicReference<WebSocketSession> registeredSession = new AtomicReference<>();
        WebSocketHandler delegate = mock(WebSocketHandler.class);
        doAnswer(invocation -> {
            registeredSession.set(invocation.getArgument(0));
            return null;
        }).when(delegate).afterConnectionEstablished(any(WebSocketSession.class));
        WebSocketHandlerDecorator handler = (WebSocketHandlerDecorator) registry.decorate(delegate);
        handler.afterConnectionEstablished(socket);

        StompUserPrincipal principal = new StompUserPrincipal(
                userId, Instant.now().minusSeconds(30), Instant.now().plusSeconds(300));
        registry.onSessionConnected(connectedEvent(principal));

        ConversationMemberRepository members = mock(ConversationMemberRepository.class);
        when(members.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        StompConversationMembershipOutboundInterceptor outbound =
                new StompConversationMembershipOutboundInterceptor(members,
                        mock(com.nexachat.message.MessageRepository.class),
                        mock(com.nexachat.user.UserBlockRepository.class),
                        registry);

        Message<byte[]> authorized = conversationMessage(conversationId);
        assertThat(outbound.preSend(authorized, channel)).isSameAs(authorized);

        TextMessage sentBeforeRevocation = new TextMessage("MESSAGE\ndestination:/topic/conversations." + conversationId
                + "\n\nactive payload\u0000");
        registeredSession.get().sendMessage(sentBeforeRevocation);
        verify(socket).sendMessage(sentBeforeRevocation);
        clearInvocations(socket);

        registry.onAccessTokensRevoked(new AccessTokensRevokedEvent(userId));

        TextMessage queuedFrame = new TextMessage("MESSAGE\ndestination:/topic/conversations." + conversationId
                + "\n\nrevoked payload\u0000");
        registeredSession.get().sendMessage(queuedFrame);

        verify(socket, never()).sendMessage(queuedFrame);
        registry.shutdown();
    }

    @Test
    void messageFrameCreatedBeforeTheRecipientJoinedIsDropped() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(userId);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        when(memberRepository.existsAdmittedAt(conversationId, userId, createdAt)).thenReturn(false);

        assertThat(interceptor.preSend(frame(conversationId, "MESSAGE_UPDATED", "\"2026-01-01T10:00:00Z\""), channel))
                .isNull();
        assertThat(interceptor.preSend(frame(conversationId, "MESSAGE_CREATED", "1767261600.000000000"), channel))
                .isNull();
    }

    @Test
    void messageFrameAfterAdmissionAndNonMessageFramesPass() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(userId);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        when(memberRepository.existsAdmittedAt(
                conversationId, userId, Instant.parse("2026-01-01T10:00:00Z"))).thenReturn(true);

        Message<byte[]> admitted = frame(conversationId, "MESSAGE_CREATED", "\"2026-01-01T10:00:00Z\"");
        assertThat(interceptor.preSend(admitted, channel)).isSameAs(admitted);
        Message<byte[]> receipt = frame(conversationId, "MESSAGE_READ", "null");
        assertThat(interceptor.preSend(receipt, channel)).isSameAs(receipt);
    }

    @Test
    void messageFrameWithoutAReadableCreationTimeFailsClosed() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(userId);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);

        assertThat(interceptor.preSend(frame(conversationId, "MESSAGE_CREATED", "null"), channel)).isNull();
        assertThat(interceptor.preSend(frame(conversationId, "MESSAGE_UPDATED", "\"not-a-time\""), channel)).isNull();
    }

    @Test
    void interactionFramesForAMessageFromBeforeTheRecipientJoinedAreDropped() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(userId);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        Instant createdAt = Instant.parse("2026-01-01T10:00:00Z");
        when(memberRepository.existsAdmittedAt(conversationId, userId, createdAt)).thenReturn(false);

        assertThat(interceptor.preSend(
                interactionFrame(conversationId, "PIN_UPDATED", "\"2026-01-01T10:00:00Z\""), channel))
                .isNull();
        assertThat(interceptor.preSend(
                interactionFrame(conversationId, "REACTION_UPDATED", "\"2026-01-01T10:00:00Z\""), channel))
                .isNull();
        assertThat(interceptor.preSend(
                interactionFrame(conversationId, "MESSAGE_DELETED", "\"2026-01-01T10:00:00Z\""), channel))
                .isNull();
    }

    @Test
    void interactionFramesFailClosedWithoutACreationTimeAndPassOnceAdmitted() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(userId);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);

        assertThat(interceptor.preSend(
                interactionFrame(conversationId, "PIN_UPDATED", null), channel)).isNull();
        assertThat(interceptor.preSend(
                interactionFrame(conversationId, "REACTION_UPDATED", "\"not-a-time\""), channel))
                .isNull();

        when(memberRepository.existsAdmittedAt(
                conversationId, userId, Instant.parse("2026-01-01T10:00:00Z"))).thenReturn(true);
        Message<byte[]> admitted = interactionFrame(
                conversationId, "MESSAGE_DELETED", "\"2026-01-01T10:00:00Z\"");
        assertThat(interceptor.preSend(admitted, channel)).isSameAs(admitted);
    }

    @Test
    void quotePreviewOfAMessageFromBeforeTheRecipientJoinedIsRemovedFromTheFrame() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(userId);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        Instant sentAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant quotedAt = Instant.parse("2026-01-01T09:00:00Z");
        when(memberRepository.existsAdmittedAt(conversationId, userId, sentAt)).thenReturn(true);
        when(memberRepository.existsAdmittedAt(conversationId, userId, quotedAt)).thenReturn(false);

        Message<byte[]> withQuote = replyFrame(conversationId, "2026-01-01T10:00:00Z", "2026-01-01T09:00:00Z");
        Message<?> released = interceptor.preSend(withQuote, channel);

        assertThat(released).isNotNull();
        var payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree((byte[]) released.getPayload()).path("payload");
        assertThat(payload.has("replyTo")).isFalse();
        assertThat(payload.path("content").asText()).isEqualTo("reply");
        assertThat(payload.path("replyToId").asText()).isNotEmpty();
    }

    @Test
    void quotePreviewTheRecipientMayReadIsKept() {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(userId);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)).thenReturn(true);
        when(memberRepository.existsAdmittedAt(org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any())).thenReturn(true);

        Message<byte[]> withQuote = replyFrame(conversationId, "2026-01-01T10:00:00Z", "2026-01-01T09:00:00Z");
        assertThat(interceptor.preSend(withQuote, channel)).isSameAs(withQuote);
    }

    @Test
    void quoteStripFailureReleasesTheOriginalFrameInsteadOfDroppingIt() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message<byte[]> frame = replyFrame(conversationId, "2026-01-01T10:00:00Z", "2026-01-01T09:00:00Z");
        // The strip's cast assumes the payload node is an ObjectNode: a node
        // that passes the replyTo checks but is not an ObjectNode detonates
        // the strip after the frame already passed admission, block and
        // membership checks — the released frame must survive the failure.
        com.fasterxml.jackson.databind.node.ObjectNode quoted =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        quoted.put("id", "quoted-id");
        quoted.put("createdAt", "2026-01-01T09:00:00Z");
        com.fasterxml.jackson.databind.JsonNode payload =
                mock(com.fasterxml.jackson.databind.JsonNode.class);
        when(payload.path("replyTo")).thenReturn(quoted);
        com.fasterxml.jackson.databind.JsonNode root =
                mock(com.fasterxml.jackson.databind.JsonNode.class);
        when(root.path("eventType"))
                .thenReturn(com.fasterxml.jackson.databind.node.TextNode.valueOf("MESSAGE_CREATED"));
        when(root.path("payload")).thenReturn(payload);

        java.lang.reflect.Method strip = StompConversationMembershipOutboundInterceptor.class
                .getDeclaredMethod("withoutUnadmittedQuote",
                        UUID.class, UUID.class, Message.class, com.fasterxml.jackson.databind.JsonNode.class);
        strip.setAccessible(true);
        Message<?> released = (Message<?>) strip.invoke(interceptor, conversationId, userId, frame, root);

        assertThat(released)
                .as("a frame that already passed admission must not vanish on a strip failure")
                .isSameAs(frame);
    }

    // ------------------------------------------------------------- V38 blocks

    @Test
    void quotePreviewOfAPeerSuppressedMessageIsRemovedForEveryoneButItsSender() throws Exception {
        UUID viewer = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(viewer);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, viewer)).thenReturn(true);
        when(memberRepository.existsAdmittedAt(org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.eq(viewer), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        UUID quotedId = UUID.randomUUID();
        when(messageRepository.findPeerSuppressedSenderId(quotedId))
                .thenReturn(java.util.Optional.of(UUID.randomUUID()));

        Message<?> released = interceptor.preSend(
                replyFrame(conversationId, "2026-01-01T10:00:00Z", "2026-01-01T09:00:00Z", quotedId),
                channel);

        assertThat(released).isNotNull();
        var payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree((byte[]) released.getPayload()).path("payload");
        assertThat(payload.has("replyTo"))
                .as("a hidden row's content must not leak through a later reply's preview")
                .isFalse();

        when(messageRepository.findPeerSuppressedSenderId(quotedId))
                .thenReturn(java.util.Optional.of(viewer));
        Message<byte[]> ownQuote = replyFrame(
                conversationId, "2026-01-01T10:00:00Z", "2026-01-01T09:00:00Z", quotedId);
        assertThat(interceptor.preSend(ownQuote, channel)).isSameAs(ownQuote);
    }

    @Test
    void suppressedMessageFrameReachesOnlyItsSender() {
        UUID viewer = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(viewer);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, viewer)).thenReturn(true);
        when(memberRepository.existsAdmittedAt(
                conversationId, viewer, Instant.parse("2026-01-01T10:00:00Z"))).thenReturn(true);
        UUID suppressedId = UUID.randomUUID();
        UUID otherSender = UUID.randomUUID();
        when(messageRepository.findPeerSuppressedSenderId(suppressedId))
                .thenReturn(java.util.Optional.of(otherSender));

        assertThat(interceptor.preSend(
                messageFrame(conversationId, "MESSAGE_CREATED", suppressedId), channel))
                .as("a peer-suppressed row never reaches anyone but its sender")
                .isNull();

        when(messageRepository.findPeerSuppressedSenderId(suppressedId))
                .thenReturn(java.util.Optional.of(viewer));
        Message<byte[]> ownRow = messageFrame(conversationId, "MESSAGE_CREATED", suppressedId);
        assertThat(interceptor.preSend(ownRow, channel))
                .as("the sender's own sessions keep seeing their suppressed row")
                .isSameAs(ownRow);
    }

    @Test
    void suppressedRowInteractionFramesAreDroppedForNonSenders() {
        UUID viewer = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(viewer);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, viewer)).thenReturn(true);
        when(memberRepository.existsAdmittedAt(
                conversationId, viewer, Instant.parse("2026-01-01T10:00:00Z"))).thenReturn(true);
        UUID suppressedId = UUID.randomUUID();
        when(messageRepository.findPeerSuppressedSenderId(suppressedId))
                .thenReturn(java.util.Optional.of(UUID.randomUUID()));

        assertThat(interceptor.preSend(
                interactionFrame(conversationId, "PIN_UPDATED", suppressedId,
                        "\"2026-01-01T10:00:00Z\""), channel))
                .as("a pin frame naming a hidden row is dropped with it")
                .isNull();
        assertThat(interceptor.preSend(
                interactionFrame(conversationId, "REACTION_UPDATED", suppressedId,
                        "\"2026-01-01T10:00:00Z\""), channel))
                .isNull();
    }

    @Test
    void activityFramesStopAcrossABlockEdgeInEitherDirection() {
        UUID viewer = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        bindSessionTo(viewer);
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, viewer)).thenReturn(true);
        when(blockRepository.existsBetweenEitherDirection(actor, viewer)).thenReturn(true);

        assertThat(interceptor.preSend(
                activityFrame(conversationId, "USER_TYPING", actor), channel))
                .as("typing between blocked users is dropped")
                .isNull();
        assertThat(interceptor.preSend(
                activityFrame(conversationId, "MESSAGE_READ", actor), channel))
                .as("read receipts between blocked users are dropped")
                .isNull();
        assertThat(interceptor.preSend(
                activityFrame(conversationId, "MESSAGE_DELIVERED", actor), channel))
                .isNull();
        // The actor's own sessions still receive their own frames.
        assertThat(interceptor.preSend(
                activityFrame(conversationId, "USER_TYPING", viewer), channel))
                .isNotNull();
        // An unrelated actor's activity still flows.
        assertThat(interceptor.preSend(
                activityFrame(conversationId, "USER_TYPING", UUID.randomUUID()), channel))
                .isNotNull();
    }

    private static Message<byte[]> messageFrame(UUID conversationId, String eventType,
                                                UUID messageId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination("/topic/conversations." + conversationId);
        String json = "{\"eventType\":\"" + eventType + "\",\"conversationId\":\"" + conversationId
                + "\",\"payload\":{\"id\":\"" + messageId
                + "\",\"createdAt\":\"2026-01-01T10:00:00Z\"}}";
        return MessageBuilder.createMessage(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private static Message<byte[]> activityFrame(UUID conversationId, String eventType, UUID actorId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination("/topic/conversations." + conversationId);
        String json = "{\"eventType\":\"" + eventType + "\",\"conversationId\":\"" + conversationId
                + "\",\"payload\":{\"userId\":\"" + actorId + "\"}}";
        return MessageBuilder.createMessage(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private static Message<byte[]> interactionFrame(UUID conversationId, String eventType,
                                                    UUID messageId, String messageCreatedAtJson) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination("/topic/conversations." + conversationId);
        String json = "{\"eventType\":\"" + eventType + "\",\"conversationId\":\"" + conversationId
                + "\",\"payload\":{\"messageId\":\"" + messageId
                + "\",\"messageCreatedAt\":" + messageCreatedAtJson + "}}";
        return MessageBuilder.createMessage(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private static Message<byte[]> replyFrame(UUID conversationId, String createdAt, String quotedAt) {
        return replyFrame(conversationId, createdAt, quotedAt, UUID.randomUUID());
    }

    private static Message<byte[]> replyFrame(UUID conversationId, String createdAt, String quotedAt,
                                              UUID quotedId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination("/topic/conversations." + conversationId);
        String json = "{\"eventType\":\"MESSAGE_CREATED\",\"conversationId\":\"" + conversationId
                + "\",\"payload\":{\"content\":\"reply\",\"createdAt\":\"" + createdAt
                + "\",\"replyToId\":\"" + quotedId + "\",\"replyTo\":{\"id\":\"" + quotedId
                + "\",\"content\":\"secret from the absence\",\"deleted\":false,\"createdAt\":\""
                + quotedAt + "\"}}}";
        return MessageBuilder.createMessage(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private void bindSessionTo(UUID userId) {
        when(sessionRegistry.isAuthenticatedSession(SESSION_ID)).thenReturn(true);
        when(sessionRegistry.userIdForSession(SESSION_ID)).thenReturn(userId);
    }

    private static Message<byte[]> interactionFrame(UUID conversationId, String eventType,
                                                    String messageCreatedAtJson) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination("/topic/conversations." + conversationId);
        String json = "{\"eventType\":\"" + eventType + "\",\"conversationId\":\"" + conversationId
                + "\",\"payload\":{\"messageId\":\"" + UUID.randomUUID()
                + "\",\"messageCreatedAt\":" + (messageCreatedAtJson == null
                        ? "null" : messageCreatedAtJson) + "}}";
        return MessageBuilder.createMessage(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private static Message<byte[]> frame(UUID conversationId, String eventType, String createdAtJson) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination("/topic/conversations." + conversationId);
        String json = "{\"eventType\":\"" + eventType + "\",\"conversationId\":\"" + conversationId
                + "\",\"payload\":{\"createdAt\":" + createdAtJson + "}}";
        return MessageBuilder.createMessage(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private static SessionConnectedEvent connectedEvent(StompUserPrincipal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECTED);
        accessor.setSessionId(SESSION_ID);
        accessor.setUser(principal);
        Message<byte[]> connectedMessage = MessageBuilder.createMessage(
                new byte[0], accessor.getMessageHeaders());
        return new SessionConnectedEvent(StompConversationMembershipOutboundInterceptorTest.class,
                connectedMessage, principal);
    }

    private static Message<byte[]> conversationMessage(UUID conversationId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setSessionId(SESSION_ID);
        accessor.setDestination("/topic/conversations." + conversationId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> message(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> heartbeat() {
        SimpMessageHeaderAccessor accessor =
                SimpMessageHeaderAccessor.create(SimpMessageType.HEARTBEAT);
        accessor.setSessionId(SESSION_ID);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
