package com.nexachat.ws;

import com.nexachat.conversation.ConversationMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StompConversationMembershipOutboundInterceptorTest {

    private static final String SESSION_ID = "pending-session";

    private final ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
    private final SimpUserRegistry userRegistry = mock(SimpUserRegistry.class);
    private final StompSessionRegistry sessionRegistry = mock(StompSessionRegistry.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private final StompConversationMembershipOutboundInterceptor interceptor =
            new StompConversationMembershipOutboundInterceptor(
                    memberRepository, userRegistry, sessionRegistry);

    @Test
    void connectedResponsePassesBeforeSessionBindingCompletes() {
        when(sessionRegistry.isAuthenticatedSession(SESSION_ID)).thenReturn(false);
        Message<byte[]> connected = message(StompCommand.CONNECTED);

        assertThat(interceptor.preSend(connected, channel)).isSameAs(connected);

        verify(sessionRegistry, never()).isAuthenticatedSession(SESSION_ID);
        verifyNoInteractions(memberRepository, userRegistry);
    }

    @Test
    void unboundApplicationMessageIsDropped() {
        when(sessionRegistry.isAuthenticatedSession(SESSION_ID)).thenReturn(false);
        Message<byte[]> event = message(StompCommand.MESSAGE);

        assertThat(interceptor.preSend(event, channel)).isNull();

        verify(sessionRegistry).isAuthenticatedSession(SESSION_ID);
        verifyNoInteractions(memberRepository, userRegistry);
    }

    private static Message<byte[]> message(StompCommand command) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(SESSION_ID);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
