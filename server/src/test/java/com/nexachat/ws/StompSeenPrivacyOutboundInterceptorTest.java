package com.nexachat.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reciprocity half of the seen switch: only conversation-topic
 * {@code MESSAGE_READ} frames are inspected, and only the recipient's own
 * setting can drop one.
 */
@ExtendWith(MockitoExtension.class)
class StompSeenPrivacyOutboundInterceptorTest {

    private static final String SESSION_ID = "seen-session";

    @Mock
    private UserRepository userRepository;
    @Mock
    private StompSessionRegistry sessionRegistry;
    @Mock
    private MessageChannel channel;

    private StompSeenPrivacyOutboundInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        interceptor = new StompSeenPrivacyOutboundInterceptor(
                userRepository, sessionRegistry, new ObjectMapper());
    }

    @Test
    void messageReadIsDroppedForARecipientWhoHidesSeen() {
        when(sessionRegistry.userIdForSession(SESSION_ID)).thenReturn(userId);
        when(userRepository.findShowSeenById(userId)).thenReturn(Optional.of(false));
        Message<byte[]> frame = topicFrame("MESSAGE_READ");

        assertThat(interceptor.preSend(frame, channel)).isNull();
    }

    @Test
    void messageReadPassesForARecipientWhoSharesSeen() {
        when(sessionRegistry.userIdForSession(SESSION_ID)).thenReturn(userId);
        when(userRepository.findShowSeenById(userId)).thenReturn(Optional.of(true));
        Message<byte[]> frame = topicFrame("MESSAGE_READ");

        assertThat(interceptor.preSend(frame, channel)).isSameAs(frame);
    }

    @Test
    void missingSettingDefaultsToSharingSeen() {
        when(sessionRegistry.userIdForSession(SESSION_ID)).thenReturn(userId);
        when(userRepository.findShowSeenById(userId)).thenReturn(Optional.empty());
        Message<byte[]> frame = topicFrame("MESSAGE_READ");

        assertThat(interceptor.preSend(frame, channel)).isSameAs(frame);
    }

    @Test
    void otherEventTypesPassWithoutReadingTheSetting() {
        Message<byte[]> frame = topicFrame("MESSAGE_CREATED");

        assertThat(interceptor.preSend(frame, channel)).isSameAs(frame);
        verifyNoInteractions(userRepository, sessionRegistry);
    }

    @Test
    void nonTopicDestinationsPassWithoutReadingTheSetting() {
        Message<byte[]> frame = frame("/user/queue/inbox", "MESSAGE_READ");

        assertThat(interceptor.preSend(frame, channel)).isSameAs(frame);
        verifyNoInteractions(userRepository, sessionRegistry);
    }

    @Test
    void malformedPayloadPasses() {
        Message<byte[]> frame = frame("/topic/conversations." + conversationId, "not-json");

        assertThat(interceptor.preSend(frame, channel)).isSameAs(frame);
        verifyNoInteractions(userRepository, sessionRegistry);
    }

    @Test
    void unboundRecipientPassesAndMembershipRemainsTheGate() {
        when(sessionRegistry.userIdForSession(SESSION_ID)).thenReturn(null);
        Message<byte[]> frame = topicFrame("MESSAGE_READ");

        assertThat(interceptor.preSend(frame, channel)).isSameAs(frame);
        verifyNoInteractions(userRepository);
    }

    @Test
    void envelopePayloadIsAlsoRecognised() {
        when(sessionRegistry.userIdForSession(SESSION_ID)).thenReturn(userId);
        when(userRepository.findShowSeenById(userId)).thenReturn(Optional.of(false));
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setDestination("/topic/conversations." + conversationId);
        accessor.setSessionId(SESSION_ID);
        accessor.setLeaveMutable(true);
        EventEnvelope envelope = EventEnvelope.of(WsEventType.MESSAGE_READ, conversationId,
                java.util.Map.of("userId", userId.toString(), "upToSeq", 1));
        Message<EventEnvelope> frame = MessageBuilder.createMessage(envelope,
                accessor.getMessageHeaders());

        assertThat(interceptor.preSend(frame, channel)).isNull();
    }

    private Message<byte[]> topicFrame(String eventType) {
        return frame("/topic/conversations." + conversationId, eventType);
    }

    private Message<byte[]> frame(String destination, String eventType) {
        if (!"MESSAGE_READ".equals(eventType) && !"MESSAGE_CREATED".equals(eventType)) {
            return frame(destination, eventType.getBytes(StandardCharsets.UTF_8));
        }
        String json = "{\"eventType\":\"" + eventType + "\",\"conversationId\":\""
                + conversationId + "\",\"serverTs\":\"2026-11-09T10:00:00Z\",\"payload\":"
                + "{\"userId\":\"" + userId + "\",\"upToSeq\":1}}";
        return frame(destination, json.getBytes(StandardCharsets.UTF_8));
    }

    private Message<byte[]> frame(String destination, byte[] payload) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
        accessor.setDestination(destination);
        accessor.setSessionId(SESSION_ID);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
    }
}
