package com.nexachat.ws;

import com.nexachat.auth.JwtService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.conversation.ConversationMemberRepository;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Two-layer STOMP security on the client-inbound channel (plan §11.3.2 —
 * authN at CONNECT, authZ at SUBSCRIBE and SEND, never trusting the
 * client-supplied destination).
 *
 * <ul>
 *   <li><b>CONNECT (authN)</b>: {@code Authorization: Bearer <at>} native
 *       header → {@link JwtService#parseAccessToken} →
 *       {@link StompUserPrincipal}. Missing/bad token → {@code null} (frame
 *       dropped, no CONNECTED reply — the socket simply never finishes the
 *       STOMP handshake). The token is carried in a STOMP header, never a URL
 *       query param (§11.1: query params leak into access logs).</li>
 *   <li><b>SUBSCRIBE (authZ)</b>: {@code /topic/conversations.{uuid}} is
 *       allowed only for conversation members;
 *       {@code /user/queue/...} is allowed for any authenticated session (the
 *       user-destination resolver maps it to THIS session's queue — it cannot
 *       name another user's). Every other destination is denied by default.</li>
 *   <li><b>SEND (authZ)</b>: {@code /app/conversations/{uuid}/...} re-checks
 *       membership before the frame can reach a {@code @MessageMapping} —
 *       belt-and-braces under the service-level check, and it stops
 *       non-members without invoking business logic.</li>
 * </ul>
 *
 * <p>Denial = returning {@code null}: the frame is silently dropped (clients
 * observe a SUBSCRIBE that never completes, a SEND that never broadcasts).
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /** Frozen topic contract: {@code /topic/conversations.{uuid}} (plan §11.2). */
    private static final Pattern TOPIC_CONVERSATION =
            Pattern.compile("^/topic/conversations\\.(" + UUID_PATTERN + ")$");

    /** Frozen app contract: {@code /app/conversations/{uuid}/{action}}. */
    private static final Pattern APP_CONVERSATION =
            Pattern.compile("^/app/conversations/(" + UUID_PATTERN + ")/(messages|typing|read)$");

    /** Any authenticated session may subscribe to its own user queue. */
    private static final Pattern USER_QUEUE = Pattern.compile("^/user/queue/.+$");

    private final JwtService jwtService;
    private final ConversationMemberRepository memberRepository;

    public StompAuthChannelInterceptor(JwtService jwtService,
                                       ConversationMemberRepository memberRepository) {
        this.jwtService = jwtService;
        this.memberRepository = memberRepository;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        return switch (accessor.getCommand()) {
            case CONNECT -> onConnect(accessor) ? message : null;
            case SUBSCRIBE -> onSubscribe(accessor) ? message : null;
            case SEND -> onSend(accessor) ? message : null;
            default -> message;
        };
    }

    // ------------------------------------------------------------ CONNECT authN

    private boolean onConnect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.warn("STOMP CONNECT rejected: missing Bearer Authorization header");
            return false;
        }
        final JwtPrincipal principal;
        try {
            principal = jwtService.parseAccessToken(header.substring(BEARER_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("STOMP CONNECT rejected: {}", e.getMessage());
            return false;
        }
        accessor.setUser(new StompUserPrincipal(principal.userId()));
        return true;
    }

    // ---------------------------------------------------------- SUBSCRIBE authZ

    private boolean onSubscribe(StompHeaderAccessor accessor) {
        UUID userId = sessionUser(accessor);
        if (userId == null) {
            return false;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return false;
        }
        Matcher topic = TOPIC_CONVERSATION.matcher(destination);
        if (topic.matches()) {
            UUID conversationId = UUID.fromString(topic.group(1));
            boolean member = memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId);
            if (!member) {
                log.warn("STOMP SUBSCRIBE denied: {} is not a member of {}", userId, conversationId);
            }
            return member;
        }
        if (USER_QUEUE.matcher(destination).matches()) {
            // /user/queue/* resolves to THIS session's queue only — safe to allow.
            return true;
        }
        log.warn("STOMP SUBSCRIBE denied: unauthorized destination {}", destination);
        return false;
    }

    // --------------------------------------------------------------- SEND authZ

    private boolean onSend(StompHeaderAccessor accessor) {
        UUID userId = sessionUser(accessor);
        if (userId == null) {
            return false;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return false;
        }
        Matcher app = APP_CONVERSATION.matcher(destination);
        if (app.matches()) {
            UUID conversationId = UUID.fromString(app.group(1));
            boolean member = memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId);
            if (!member) {
                log.warn("STOMP SEND denied: {} is not a member of {}", userId, conversationId);
            }
            return member;
        }
        // Deny-by-default: only the frozen conversation actions are routable.
        log.warn("STOMP SEND denied: unknown application destination {}", destination);
        return false;
    }

    @Nullable
    private static UUID sessionUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompUserPrincipal principal) {
            return principal.userId();
        }
        return null;
    }
}
