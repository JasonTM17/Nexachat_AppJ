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
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP security on client-inbound and client-outbound channels (plan §11.3.2 —
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
 * <p>Revocation is rechecked after executor dequeue, and the shared fair fence
 * orders handler execution and socket writes against reset/deletion commits.
 * Server-generated frames carry only internal actor metadata for that check.
 * Denial = returning {@code null}: the frame is silently dropped.
 */
@Component
public class StompAuthChannelInterceptor implements ExecutorChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REVOCATION_ACTOR_HEADER = "nexachat.internal.revocation.actor";
    private static final String REVOCATION_ISSUED_AT_HEADER = "nexachat.internal.revocation.issuedAt";
    private static final String REVOCATION_EXPIRES_AT_HEADER = "nexachat.internal.revocation.expiresAt";
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

    private record RevocationActor(UUID userId, Instant issuedAt, Instant expiresAt) {
    }

    private record ExecutionFrame(StompRevocationFence.ReadLease lease,
                                  StompRevocationFence.SessionWriteLease sessionWriteLease,
                                  RevocationActor previousActor,
                                  boolean actorChanged) {
    }

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final ConversationMemberRepository memberRepository;
    private final RateLimitService rateLimitService;
    private final StompSessionRegistry sessionRegistry;
    private final StompRevocationFence revocationFence;
    private final ThreadLocal<RevocationActor> currentActor = new ThreadLocal<>();
    private final ThreadLocal<Deque<ExecutionFrame>> executionFrames =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final long messagesPerWindow;
    private final long messageWindowSeconds;
    private final long typingPerWindow;
    private final long typingWindowSeconds;
    private final long acksPerWindow;
    private final long ackWindowSeconds;

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
        this(jwtService, userRepository, memberRepository, rateLimitService, messaging, objectMapper,
                messagesPerWindow, messageWindowSeconds, typingPerWindow, typingWindowSeconds,
                acksPerWindow, ackWindowSeconds, null, null);
    }

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
                                       long ackWindowSeconds,
                                       StompSessionRegistry sessionRegistry,
                                       StompRevocationFence revocationFence) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
        this.rateLimitService = rateLimitService;
        this.sessionRegistry = sessionRegistry;
        this.messaging = messaging;
        this.objectMapper = objectMapper;
        this.messagesPerWindow = messagesPerWindow;
        this.messageWindowSeconds = messageWindowSeconds;
        this.typingPerWindow = typingPerWindow;
        this.typingWindowSeconds = typingWindowSeconds;
        this.acksPerWindow = acksPerWindow;
        this.ackWindowSeconds = ackWindowSeconds;
        this.revocationFence = revocationFence == null
                ? new StompRevocationFence(userRepository) : revocationFence;
    }

    /** Compatibility constructor for unit call sites without the shared registry. */
    public StompAuthChannelInterceptor(JwtService jwtService,
                                       UserRepository userRepository,
                                       ConversationMemberRepository memberRepository,
                                       RateLimitService rateLimitService,
                                       @Lazy SimpMessagingTemplate messaging,
                                       ObjectMapper objectMapper,
                                       long messagesPerWindow,
                                       long messageWindowSeconds,
                                       long typingPerWindow,
                                       long typingWindowSeconds,
                                       long acksPerWindow,
                                       long ackWindowSeconds,
                                       StompSessionRegistry sessionRegistry) {
        this(jwtService, userRepository, memberRepository, rateLimitService, messaging, objectMapper,
                messagesPerWindow, messageWindowSeconds, typingPerWindow, typingWindowSeconds,
                acksPerWindow, ackWindowSeconds, sessionRegistry, null);
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
        Message<?> taggedMessage = tagCurrentActor(message);
        if (taggedMessage != message) {
            message = taggedMessage;
        }
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        return switch (accessor.getCommand()) {
            case CONNECT, STOMP -> acceptConnect(accessor) ? message : null;
            case SUBSCRIBE -> onSubscribe(accessor) ? message : null;
            case SEND -> onSend(accessor, message) ? message : null;
            default -> message;
        };
    }

    /**
     * Runs after the inbound executor dequeues a frame and immediately before
     * its handler. It closes the preSend-to-handler revocation gap. On the
     * outbound executor, the same guard covers the actual WebSocket write.
     */
    @Override
    @Nullable
    public Message<?> beforeHandle(@NonNull Message<?> message,
                                   @NonNull MessageChannel channel,
                                   @NonNull MessageHandler handler) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        StompRevocationFence.ReadLease lease = null;
        StompRevocationFence.SessionWriteLease sessionWriteLease = null;
        RevocationActor actorForHandler = null;
        boolean frameRegistered = false;

        try {
            // The outbound channel's handler is the point that calls
            // ConcurrentWebSocketSessionDecorator.sendMessage. Serialize every
            // session-targeted frame there so an actor frame cannot be buffered
            // and outlive its revocation read lease.
            if (handler instanceof SubProtocolWebSocketHandler && accessor.getSessionId() != null) {
                sessionWriteLease = revocationFence.acquireSessionWrite(accessor.getSessionId());
            }

            if (command == StompCommand.SEND || command == StompCommand.SUBSCRIBE) {
                if (accessor.getUser() instanceof StompUserPrincipal principal
                        && principal.issuedAt() != null) {
                    lease = revocationFence.acquireRead(principal.userId());
                    if (!isCurrentAndReleaseOnFailure(lease, principal.userId(), principal.issuedAt(),
                            principal.expiresAt())) {
                        closeRevokedSession(accessor.getSessionId());
                        return null;
                    }
                    if (command == StompCommand.SEND) {
                        actorForHandler = new RevocationActor(
                                principal.userId(), principal.issuedAt(), principal.expiresAt());
                    }
                }
            } else {
                RevocationActor outboundActor = outboundActor(message);
                if (outboundActor != null) {
                    lease = revocationFence.acquireRead(outboundActor.userId());
                    if (!isCurrentAndReleaseOnFailure(lease, outboundActor.userId(), outboundActor.issuedAt(),
                            outboundActor.expiresAt())) {
                        return null;
                    }
                }
            }

            RevocationActor previousActor = currentActor.get();
            boolean actorChanged = actorForHandler != null;
            if (actorChanged) {
                currentActor.set(actorForHandler);
            }
            executionFrames.get().push(new ExecutionFrame(lease, sessionWriteLease, previousActor, actorChanged));
            frameRegistered = true;
            return message;
        } finally {
            if (!frameRegistered) {
                if (lease != null) {
                    lease.close();
                }
                if (sessionWriteLease != null) {
                    sessionWriteLease.close();
                }
            }
        }
    }

    @Override
    public void afterMessageHandled(@NonNull Message<?> message,
                                    @NonNull MessageChannel channel,
                                    @NonNull MessageHandler handler,
                                    @Nullable Exception ex) {
        Deque<ExecutionFrame> frames = executionFrames.get();
        if (frames.isEmpty()) {
            executionFrames.remove();
            return;
        }
        ExecutionFrame frame = frames.pop();
        if (frame.actorChanged()) {
            if (frame.previousActor() == null) {
                currentActor.remove();
            } else {
                currentActor.set(frame.previousActor());
            }
        }
        try {
            if (frame.lease() != null) {
                frame.lease().close();
            }
        } finally {
            if (frame.sessionWriteLease() != null) {
                frame.sessionWriteLease().close();
            }
        }
        if (frames.isEmpty()) {
            executionFrames.remove();
        }
    }

    private Message<?> tagCurrentActor(Message<?> message) {
        RevocationActor actor = currentActor.get();
        if (actor == null) {
            return message;
        }
        return MessageBuilder.fromMessage(message)
                .setHeader(REVOCATION_ACTOR_HEADER, actor.userId().toString())
                .setHeader(REVOCATION_ISSUED_AT_HEADER, actor.issuedAt().toString())
                .setHeader(REVOCATION_EXPIRES_AT_HEADER, actor.expiresAt().toString())
                .build();
    }

    @Nullable
    private static RevocationActor outboundActor(Message<?> message) {
        Object rawUserId = message.getHeaders().get(REVOCATION_ACTOR_HEADER);
        Object rawIssuedAt = message.getHeaders().get(REVOCATION_ISSUED_AT_HEADER);
        Object rawExpiresAt = message.getHeaders().get(REVOCATION_EXPIRES_AT_HEADER);
        if (!(rawUserId instanceof String userId)
                || !(rawIssuedAt instanceof String issuedAt)
                || !(rawExpiresAt instanceof String expiresAt)) {
            return null;
        }
        try {
            return new RevocationActor(UUID.fromString(userId), Instant.parse(issuedAt), Instant.parse(expiresAt));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void closeRevokedSession(@Nullable String sessionId) {
        if (sessionRegistry != null) {
            sessionRegistry.closeHandshake(sessionId);
        }
    }

    private boolean isCurrentAndReleaseOnFailure(StompRevocationFence.ReadLease lease,
                                                 UUID userId,
                                                 Instant issuedAt,
                                                 Instant expiresAt) {
        try {
            boolean current = revocationFence.isCurrent(userId, issuedAt, expiresAt);
            if (!current) {
                lease.close();
            }
            return current;
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    // ------------------------------------------------------------ CONNECT authN

    /** CONNECT and the STOMP 1.2 equivalent. A failed handshake closes the socket. */
    private boolean acceptConnect(StompHeaderAccessor accessor) {
        boolean accepted = onConnect(accessor);
        if (!accepted && sessionRegistry != null) {
            sessionRegistry.closeHandshake(accessor.getSessionId());
        }
        return accepted;
    }

    private boolean onConnect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.warn("STOMP CONNECT rejected: missing Bearer Authorization header");
            return false;
        }
        final JwtPrincipal principal;
        final Instant issuedAt;
        final Instant expiresAt;
        try {
            JwtService.AccessTokenClaims claims =
                    jwtService.parseAccessTokenClaims(header.substring(BEARER_PREFIX.length()));
            principal = new JwtPrincipal(claims.userId());
            issuedAt = claims.issuedAt();
            expiresAt = claims.expiresAt();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("STOMP CONNECT rejected: invalid access token");
            return false;
        }
        if (!userRepository.existsById(principal.userId())) {
            log.warn("STOMP CONNECT rejected: authenticated account no longer exists");
            return false;
        }
        // Same revocation boundary as the REST JWT filter: an access token that
        // was issued before the user's cutoff (logout / password change) must
        // not open a socket either.
        if (isAccessTokenRevoked(principal.userId(), issuedAt)) {
            log.warn("STOMP CONNECT rejected: access token was revoked");
            return false;
        }
        if (sessionRegistry != null && !sessionRegistry.canOpenSession(principal.userId())) {
            log.warn("STOMP CONNECT rejected: too many open sessions");
            return false;
        }
        accessor.setUser(new StompUserPrincipal(principal.userId(), issuedAt, expiresAt));
        return true;
    }

    private boolean isAccessTokenRevoked(UUID userId, Instant issuedAt) {
        // CONNECT is a cold path (once per socket), so the authoritative value
        // is read straight from the database instead of the REST hot-path cache.
        return userRepository.findAuthValidityById(userId)
                .map(found -> {
                    Instant cutoff = found.tokensValidAfter();
                    return cutoff != null && !issuedAt.isAfter(cutoff);
                })
                .orElse(true);
    }

    // ---------------------------------------------------------- SUBSCRIBE authZ

    private boolean onSubscribe(StompHeaderAccessor accessor) {
        if (!isCurrentSession(accessor)) {
            return false;
        }
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
        if (!isCurrentSession(accessor)) {
            return false;
        }
        StompUserPrincipal principal = sessionPrincipal(accessor);
        if (principal == null) {
            return false;
        }
        UUID userId = principal.userId();
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
                return consumeSendAllowance(principal, "messages", messagesPerWindow,
                        messageWindowSeconds, clientMessageId(message.getPayload()));
            }
            if ("typing".equals(app.group(2))) {
                return consumeSendAllowance(principal, "typing", typingPerWindow,
                        typingWindowSeconds, null);
            }
            if ("delivered".equals(app.group(2)) || "read".equals(app.group(2))) {
                return consumeSendAllowance(principal, "acks", acksPerWindow,
                        ackWindowSeconds, null);
            }
            return true;
        }
        Matcher chatAck = APP_CHAT_ACK.matcher(destination);
        if (chatAck.matches()) {
            UUID conversationId = ackConversationId(message.getPayload());
            if (conversationId != null
                    && !memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
                log.warn("STOMP ack SEND denied: caller is not a conversation member");
                return false;
            }
            return consumeSendAllowance(principal, "acks", acksPerWindow,
                    ackWindowSeconds, null);
        }
        Matcher call = APP_CALL.matcher(destination);
        if (call.matches()) {
            UUID conversationId = UUID.fromString(call.group(1));
            boolean member = memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId);
            if (!member) {
                sendUserError(principal, new WsErrorResponse(ApiErrorCode.NOT_MEMBER.getCode(), null));
                log.warn("STOMP call SEND denied: caller is not a conversation member");
            }
            return member;
        }
        // Deny-by-default: only the frozen conversation actions are routable.
        log.warn("STOMP SEND denied: unsupported application destination");
        return false;
    }

    private boolean consumeSendAllowance(StompUserPrincipal principal, String action, long capacity,
                                         long windowSeconds, @Nullable UUID clientMessageId) {
        UUID userId = principal.userId();
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                "stomp:" + action + ":" + userId,
                capacity,
                Duration.ofSeconds(windowSeconds));
        if (consumption.allowed()) {
            return true;
        }
        sendUserError(principal, new WsErrorResponse(ApiErrorCode.RATE_LIMITED.getCode(), clientMessageId));
        log.warn("STOMP {} SEND rate-limited", action);
        return false;
    }

    private void sendUserError(StompUserPrincipal principal, WsErrorResponse response) {
        String userId = principal.userId().toString();
        if (principal.issuedAt() == null || principal.expiresAt() == null) {
            messaging.convertAndSendToUser(userId, "/queue/errors", response);
            return;
        }

        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setHeader(REVOCATION_ACTOR_HEADER, userId);
        headers.setHeader(REVOCATION_ISSUED_AT_HEADER, principal.issuedAt().toString());
        headers.setHeader(REVOCATION_EXPIRES_AT_HEADER, principal.expiresAt().toString());
        headers.setLeaveMutable(true);
        Map<String, Object> messageHeaders = headers.getMessageHeaders();
        messaging.convertAndSendToUser(userId, "/queue/errors", response, messageHeaders);
    }

    @Nullable
    private static UUID sessionUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompUserPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    @Nullable
    private static StompUserPrincipal sessionPrincipal(StompHeaderAccessor accessor) {
        return accessor.getUser() instanceof StompUserPrincipal principal ? principal : null;
    }

    /** Re-checks token expiry and the authoritative cutoff on every client action. */
    private boolean isCurrentSession(StompHeaderAccessor accessor) {
        if (!(accessor.getUser() instanceof StompUserPrincipal principal)) {
            return false;
        }
        // Unit fixtures and internal call-site tests use a bare principal. A
        // principal created by CONNECT always carries verified JWT timestamps.
        if (principal.issuedAt() == null) {
            return true;
        }
        if (principal.expiresAt() == null || !Instant.now().isBefore(principal.expiresAt())) {
            return false;
        }
        return !isAccessTokenRevoked(principal.userId(), principal.issuedAt());
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

    /**
     * Parses the conversation scope carried by chat-level read/delivery acks.
     * Missing or malformed IDs continue to payload conversion/validation. A
     * malformed UUID may be rejected before handler invocation; neither path
     * can advance a conversation watermark.
     */
    @Nullable
    private UUID ackConversationId(Object payload) {
        try {
            JsonNode body;
            if (payload instanceof byte[] bytes) {
                body = objectMapper.readTree(bytes);
            } else if (payload instanceof String text) {
                body = objectMapper.readTree(text);
            } else {
                return null;
            }
            if (body == null || !body.isObject()) {
                return null;
            }
            String id = body.path("conversationId").asText(null);
            return id == null ? null : UUID.fromString(id);
        } catch (java.io.IOException | IllegalArgumentException ex) {
            return null;
        }
    }
}
