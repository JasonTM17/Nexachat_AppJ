package com.nexachat.ws;

import com.nexachat.conversation.ConversationMemberRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Re-checks membership for every conversation-topic delivery. The simple
 * broker shares a topic subscription across sessions and cannot filter its
 * recipients when membership changes.
 */
@Component
public class StompConversationMembershipOutboundInterceptor implements ChannelInterceptor {

    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern CONVERSATION_TOPIC =
            Pattern.compile("^/topic/conversations\\.(" + UUID_PATTERN + ")$");

    private final ConversationMemberRepository memberRepository;
    private final SimpUserRegistry userRegistry;
    private final StompSessionRegistry sessionRegistry;

    public StompConversationMembershipOutboundInterceptor(
            ConversationMemberRepository memberRepository,
            @Lazy SimpUserRegistry userRegistry,
            StompSessionRegistry sessionRegistry) {
        this.memberRepository = memberRepository;
        this.userRegistry = userRegistry;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    @Nullable
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (sessionId != null && !sessionRegistry.isAuthenticatedSession(sessionId)) {
            return null;
        }
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        Matcher topic = destination == null ? null : CONVERSATION_TOPIC.matcher(destination);
        if (topic == null || !topic.matches()) {
            return message;
        }

        UUID conversationId = UUID.fromString(topic.group(1));
        UUID userId = userIdForSession(sessionId);
        return userId != null
                && memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)
                ? message
                : null;
    }

    @Nullable
    private UUID userIdForSession(@Nullable String sessionId) {
        if (sessionId == null) {
            return null;
        }
        for (SimpUser user : userRegistry.getUsers()) {
            for (SimpSession session : user.getSessions()) {
                if (sessionId.equals(session.getId())) {
                    try {
                        return UUID.fromString(user.getName());
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
