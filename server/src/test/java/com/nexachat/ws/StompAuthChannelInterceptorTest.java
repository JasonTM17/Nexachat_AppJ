package com.nexachat.ws;

import com.nexachat.auth.JwtService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.conversation.ConversationMemberRepository;
import io.jsonwebtoken.JwtException;
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
import org.springframework.messaging.support.MessageHeaderAccessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Denial contract: {@code preSend} returns the message to let it through,
 * {@code null} to drop it — CONNECT authN, SUBSCRIBE/SEND authZ (plan §11.3.2).
 */
@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    private JwtService jwtService;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private MessageChannel channel;

    private StompAuthChannelInterceptor interceptor;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        interceptor = new StompAuthChannelInterceptor(jwtService, memberRepository);
    }

    private Message<?> message(StompHeaderAccessor accessor) {
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> subscribeMessage(String destination, boolean authenticated) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId("sub-1");
        if (authenticated) {
            accessor.setUser(new StompUserPrincipal(userId));
        }
        return message(accessor);
    }

    private Message<?> sendMessage(String destination, boolean authenticated) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        if (authenticated) {
            accessor.setUser(new StompUserPrincipal(userId));
        }
        return message(accessor);
    }

    // --------------------------------------------------------------- CONNECT

    @Test
    void connect_validToken_setsUserPrincipal() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer good-token");
        Message<?> message = message(accessor);
        when(jwtService.parseAccessToken("good-token")).thenReturn(new JwtPrincipal(userId));

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isSameAs(message);
        StompHeaderAccessor resultAccessor =
                MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor.getUser()).isInstanceOf(StompUserPrincipal.class);
        assertThat(((StompUserPrincipal) resultAccessor.getUser()).userId()).isEqualTo(userId);
        assertThat(resultAccessor.getUser().getName()).isEqualTo(userId.toString());
    }

    @Test
    void connect_missingAuthorizationHeader_rejected() {
        Message<?> message = message(StompHeaderAccessor.create(StompCommand.CONNECT));

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void connect_malformedAuthorizationHeader_rejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Basic abc123");

        assertThat(interceptor.preSend(message(accessor), channel)).isNull();
    }

    @Test
    void connect_invalidToken_rejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer bad-token");
        when(jwtService.parseAccessToken("bad-token"))
                .thenThrow(new JwtException("signature invalid"));

        assertThat(interceptor.preSend(message(accessor), channel)).isNull();
    }

    // -------------------------------------------------------------- SUBSCRIBE

    @Test
    void subscribe_conversationTopic_member_allowed() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        Message<?> message = subscribeMessage("/topic/conversations." + conversationId, true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_conversationTopic_nonMember_rejected() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);
        Message<?> message = subscribeMessage("/topic/conversations." + conversationId, true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_conversationTopic_unauthenticated_rejected() {
        Message<?> message = subscribeMessage("/topic/conversations." + conversationId, false);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_userQueue_authenticated_allowed() {
        Message<?> message = subscribeMessage("/user/queue/inbox", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_userErrors_authenticated_allowed() {
        Message<?> message = subscribeMessage("/user/queue/errors", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_userQueue_unauthenticated_rejected() {
        Message<?> message = subscribeMessage("/user/queue/inbox", false);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_foreignTopic_rejected() {
        Message<?> message = subscribeMessage("/topic/other", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void subscribe_malformedConversationTopic_rejected() {
        Message<?> message = subscribeMessage("/topic/conversations.not-a-uuid", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    // ------------------------------------------------------------------- SEND

    @Test
    void send_conversationDestination_member_allowed() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        Message<?> message = sendMessage("/app/conversations/" + conversationId + "/messages", true);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void send_typingAndRead_member_allowed() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(true);
        assertThat(interceptor.preSend(
                sendMessage("/app/conversations/" + conversationId + "/typing", true), channel))
                .isNotNull();
        assertThat(interceptor.preSend(
                sendMessage("/app/conversations/" + conversationId + "/read", true), channel))
                .isNotNull();
    }

    @Test
    void send_conversationDestination_nonMember_rejected() {
        when(memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId))
                .thenReturn(false);
        Message<?> message = sendMessage("/app/conversations/" + conversationId + "/messages", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void send_unauthenticated_rejected() {
        Message<?> message = sendMessage("/app/conversations/" + conversationId + "/messages", false);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void send_unknownAppDestination_rejected() {
        Message<?> message = sendMessage("/app/admin/shutdown", true);

        assertThat(interceptor.preSend(message, channel)).isNull();
    }

    @Test
    void disconnect_passesThrough() {
        Message<?> message = message(StompHeaderAccessor.create(StompCommand.DISCONNECT));

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }
}
