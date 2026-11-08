package com.nexachat.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.auth.RateLimitService;
import com.nexachat.auth.JwtService;
import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.user.UserRepository;
import com.nexachat.ws.dto.WsErrorResponse;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 *       {@code /user/queue/{inbox,errors,presence,calls}} is allowed for an
 *       authenticated session and resolves to that principal's own queue.
 *       Every other destination is denied by default.</li>
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
            Pattern.compile("^/app/conversations/(" + UUID_PATTERN + ")/(messages|typing|read|delivered)$");

    /** Chat ack contract: {@code /app/chat.delivery.ack} and {@code /app/chat.read.ack}. */
    private static final Pattern APP_CHAT_ACK =
            Pattern.compile("^/app/chat\\.(delivery|read)\\.ack$");

    /** Call signaling is conversation-scoped and remains member-checked here. */
    private static final Pattern APP_CALL =
            Pattern.compile("^/app/calls/(" + UUID_PATTERN + ")/signal$");

    /** Authenticated sessions may subscribe only to the supported own queues. */
    private static final Pattern USER_QUEUE = Pattern.compile("^/user/queue/(inbox|errors|presence|calls)$");

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ConversationMemberRepository memberRepository;
    private final RateLimitService rateLimitService;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final long messagesPerWindow;
    private final long messageWindowSeconds;
    private final long typingPerWindow;
    private final long typingWindowSeconds;
    private final long acksPerWindow;
    private final long ackWindowSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public StompAuthChannelInterceptor(JwtService jwtService,
                                       UserRepository userRepository,
                                       ConversationMemberRepository memberRepository,
                                       RateLimitService rateLimitService,
                                       @Lazy
                                       SimpMessagingTemplate messaging,
                                       ObjectMapper objectMapper,
                                       @Value("${nexachat.rate-limit.stomp.messages-per-window:30}")
                                       long messagesPerWindow,
                                       @Value("${nexachat.rate-limit.stomp.window-seconds:10}")
                                       long messageWindowSeconds,
                                       @Value("${nexachat.rate-limit.stomp.typing-per-window:60}")
                                       long typingPerWindow,
                                       @Value("${nexachat.rate-limit.stomp.typing-window-seconds:10}")
                                       long typingWindowSeconds,
                                       @Value("${nexachat.rate-limit.stomp.acks-per-window:120}")
                                       long acksPerWindow,
                                       @Value("${nexachat.rate-limit.stomp.ack-window-seconds:10}")
                                       long ackWindowSeconds) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.rateLimitService = rateLimitService;
        this.messaging = messaging;
        this.objectMapper = objectMapper;
        this.messagesPerWindow = messagesPerWindow;
        this.messageWindowSeconds = messageWindowSeconds;
        this.typingPerWindow = typingPerWindow;
        this.typingWindowSeconds = typingWindowSeconds;
        this.acksPerWindow = acksPerWindow;
        this.ackWindowSeconds = ackWindowSeconds;
    }

    public StompAuthChannelInterceptor(JwtService jwtService,
                                       UserRepository userRepository,
                                       ConversationMemberRepository memberRepository,
                                       RateLimitService rateLimitService,
                                       @Lazy
                                       SimpMessagingTemplate messaging,
                                       ObjectMapper objectMapper,
                                       long messagesPerWindow,
                                       long messageWindowSeconds,
                                       long typingPerWindow,
                                       long typingWindowSeconds) {
        this(jwtService, userRepository, memberRepository, rateLimitService, messaging, objectMapper,
                messagesPerWindow, messageWindowSeconds, typingPerWindow, typingWindowSeconds, 120L, 10L);
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
            case SEND -> onSend(accessor, message) ? message : null;
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
            log.warn("STOMP CONNECT rejected: invalid access token");
            return false;
        }
        if (!userRepository.existsById(principal.userId())) {
            log.warn("STOMP CONNECT rejected: authenticated account no longer exists");
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
                log.warn("STOMP SUBSCRIBE denied: caller is not a conversation member");
            }
            return member;
        }
        if (USER_QUEUE.matcher(destination).matches()) {
            // The allowlist exposes only the authenticated user's supported queues.
            return true;
        }
        log.warn("STOMP SUBSCRIBE denied: unsupported destination");
        return false;
    }

    // --------------------------------------------------------------- SEND authZ

    private boolean onSend(StompHeaderAccessor accessor, Message<?> message) {
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
                log.warn("STOMP SEND denied: caller is not a conversation member");
                return false;
            }
            if ("messages".equals(app.group(2))) {
                return consumeSendAllowance(userId, "messages", messagesPerWindow,
                        messageWindowSeconds, clientMessageId(message.getPayload()));
            }
            if ("typing".equals(app.group(2))) {
                return consumeSendAllowance(userId, "typing", typingPerWindow,
                        typingWindowSeconds, null);
            }
            if ("delivered".equals(app.group(2)) || "read".equals(app.group(2))) {
                return consumeSendAllowance(userId, "acks", acksPerWindow,
                        ackWindowSeconds, null);
            }
            return true;
        }
        Matcher chatAck = APP_CHAT_ACK.matcher(destination);
        if (chatAck.matches()) {
            return consumeSendAllowance(userId, "acks", acksPerWindow,
                    ackWindowSeconds, null);
        }
        Matcher call = APP_CALL.matcher(destination);
        if (call.matches()) {
            UUID conversationId = UUID.fromString(call.group(1));
            boolean member = memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId);
            if (!member) {
                messaging.convertAndSendToUser(userId.toString(), "/queue/errors",
                        new WsErrorResponse(ApiErrorCode.NOT_MEMBER.getCode(), null));
                log.warn("STOMP call SEND denied: caller is not a conversation member");
            }
            return member;
        }
        // Deny-by-default: only the frozen conversation actions are routable.
        log.warn("STOMP SEND denied: unsupported application destination");
        return false;
    }

    private boolean consumeSendAllowance(UUID userId, String action, long capacity,
                                         long windowSeconds, @Nullable UUID clientMessageId) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                "stomp:" + action + ":" + userId,
                capacity,
                Duration.ofSeconds(windowSeconds));
        if (consumption.allowed()) {
            return true;
        }
        messaging.convertAndSendToUser(userId.toString(), "/queue/errors",
                new WsErrorResponse(ApiErrorCode.RATE_LIMITED.getCode(), clientMessageId));
        log.warn("STOMP {} SEND rate-limited", action);
        return false;
    }

    @Nullable
    private static UUID sessionUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompUserPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    @Nullable
    private UUID clientMessageId(Object payload) {
        try {
            JsonNode body;
            if (payload instanceof byte[] bytes) {
                body = objectMapper.readTree(bytes);
            } else if (payload instanceof String text) {
                body = objectMapper.readTree(text);
            } else {
                return null;
            }
            String id = body.path("clientMessageId").asText(null);
            return id == null ? null : UUID.fromString(id);
        } catch (java.io.IOException | IllegalArgumentException ex) {
            return null;
        }
    }
}
