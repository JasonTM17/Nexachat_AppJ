package com.nexachat.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.MessageRepository;
import com.nexachat.user.UserBlockRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    private static final ObjectMapper FRAME_READER = new ObjectMapper();

    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final UserBlockRepository blockRepository;
    private final SimpUserRegistry userRegistry;
    private final StompSessionRegistry sessionRegistry;

    public StompConversationMembershipOutboundInterceptor(
            ConversationMemberRepository memberRepository,
            MessageRepository messageRepository,
            UserBlockRepository blockRepository,
            @Lazy SimpUserRegistry userRegistry,
            StompSessionRegistry sessionRegistry) {
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.blockRepository = blockRepository;
        this.userRegistry = userRegistry;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    @Nullable
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        // The broker sends CONNECTED before SessionConnectedEvent binds the
        // authenticated session. This empty handshake frame is safe to pass;
        // application payloads remain gated until binding is complete.
        SimpMessageType messageType = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
        if (StompHeaderAccessor.getCommand(message.getHeaders()) == StompCommand.CONNECTED
                || messageType == SimpMessageType.CONNECT_ACK
                || messageType == SimpMessageType.DISCONNECT_ACK
                || messageType == SimpMessageType.HEARTBEAT) {
            return message;
        }
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        if (sessionId != null && !sessionRegistry.isAuthenticatedSession(sessionId)) {
            return null;
        }
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        Matcher topic = destination == null ? null : CONVERSATION_TOPIC.matcher(destination);
        if (topic == null || !topic.matches()) {
            return isAuthenticatedOrInternalMessage(sessionId) ? message : null;
        }

        UUID conversationId = UUID.fromString(topic.group(1));
        UUID userId = userIdForSession(sessionId);
        if (userId == null || !memberRepository.existsByConversation_IdAndUser_Id(conversationId, userId)) {
            return null;
        }
        if (!admittedToFrame(conversationId, userId, message.getPayload())) {
            return null;
        }
        if (!visiblePastBlocks(userId, message.getPayload())) {
            return null;
        }
        Message<?> released = withoutUnadmittedQuote(conversationId, userId, message);
        // The membership lookup can block while revocation unbinds and closes
        // the socket, so validate the session again before releasing the frame.
        return isAuthenticatedOrInternalMessage(sessionId) ? released : null;
    }

    /**
     * A message frame may quote an older message. The quote preview is removed for a recipient who
     * was not a member when the quoted message was sent, matching what history shows them.
     */
    private Message<?> withoutUnadmittedQuote(UUID conversationId, UUID userId, Message<?> message) {
        if (!(message.getPayload() instanceof byte[] bytes)) {
            return message;
        }
        try {
            JsonNode root = FRAME_READER.readTree(bytes);
            String type = root.path("eventType").asText("");
            if (!"MESSAGE_CREATED".equals(type) && !"MESSAGE_UPDATED".equals(type)) {
                return message;
            }
            JsonNode payload = root.path("payload");
            JsonNode replyTo = payload.path("replyTo");
            if (!replyTo.isObject()) {
                return message;
            }
            Instant quotedAt = parseInstant(replyTo.path("createdAt"));
            if (quotedAt != null && memberRepository.existsAdmittedAt(conversationId, userId, quotedAt)) {
                return message;
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove("replyTo");
            return org.springframework.messaging.support.MessageBuilder
                    .createMessage(FRAME_READER.writeValueAsBytes(root), message.getHeaders());
        } catch (java.io.IOException | ClassCastException e) {
            // An unreadable frame was already admitted by creation time; drop it rather than
            // risk releasing a quote the recipient may not read.
            return null;
        }
    }

    /**
     * V38 block gate, per recipient. Two frame families:
     *
     * <ul>
     *   <li><b>Suppressed content:</b> any frame naming a peer-suppressed
     *       message reaches only that message's sender (the durable flag is
     *       probed by id, so edit/recall/pin/reaction frames about a hidden
     *       row are dropped with it).</li>
     *   <li><b>Activity signals:</b> {@code USER_TYPING}, {@code MESSAGE_READ}
     *       and {@code MESSAGE_DELIVERED} carry the acting member's
     *       {@code userId}; a block edge in either direction stops each side
     *       seeing the other's activity — including inside shared groups.
     *       The actor's own sessions always pass (a self-edge cannot exist).</li>
     * </ul>
     *
     * Runs AFTER the membership gate so only members pay the probe cost.
     */
    private boolean visiblePastBlocks(UUID recipientId, Object payload) {
        if (!(payload instanceof byte[] bytes)) {
            return true;
        }
        final JsonNode root;
        try {
            root = FRAME_READER.readTree(bytes);
        } catch (java.io.IOException e) {
            return true;   // unreadable frames defer to the admission gate
        }
        JsonNode payloadNode = root.path("payload");
        switch (root.path("eventType").asText("")) {
            case "USER_TYPING", "MESSAGE_READ", "MESSAGE_DELIVERED" -> {
                UUID actorId = parseUuid(payloadNode.path("userId"));
                if (actorId == null || actorId.equals(recipientId)) {
                    return true;
                }
                return !blockRepository.existsBetweenEitherDirection(actorId, recipientId);
            }
            case "MESSAGE_CREATED", "MESSAGE_UPDATED" -> {
                UUID messageId = parseUuid(payloadNode.path("id"));
                return messageId == null || reachesRecipient(messageId, recipientId);
            }
            case "MESSAGE_DELETED", "PIN_UPDATED", "REACTION_UPDATED" -> {
                UUID messageId = parseUuid(payloadNode.path("messageId"));
                return messageId == null || reachesRecipient(messageId, recipientId);
            }
            default -> {
                return true;
            }
        }
    }

    /** A suppressed row's frames reach only its sender; every other message reaches everyone. */
    private boolean reachesRecipient(UUID messageId, UUID recipientId) {
        return messageRepository.findPeerSuppressedSenderId(messageId)
                .map(senderId -> senderId.equals(recipientId))
                .orElse(true);
    }

    @Nullable
    private static UUID parseUuid(JsonNode node) {
        try {
            return node.isTextual() ? UUID.fromString(node.asText()) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * A per-message frame reaches only members who were in the conversation when the message was
     * created, so a member admitted later never receives an edit, recall, pin or reaction naming
     * content from their absence.
     */
    private boolean admittedToFrame(UUID conversationId, UUID userId, Object payload) {
        if (!(payload instanceof byte[] bytes)) {
            return true;
        }
        JsonNode created;
        try {
            JsonNode root = FRAME_READER.readTree(bytes);
            created = messageCreatedAtNode(root);
            if (created == null) {
                return true;
            }
        } catch (java.io.IOException e) {
            return true;
        }
        Instant createdAt = parseInstant(created);
        // A message frame without a readable creation time cannot be admitted: fail closed.
        return createdAt != null && memberRepository.existsAdmittedAt(conversationId, userId, createdAt);
    }

    /**
     * The field that scopes a per-message frame to the recipient's admission: message frames carry
     * the row's own {@code createdAt}; interaction and recall frames carry {@code messageCreatedAt}
     * for the same check. {@code null} when the event type does not name one message; a missing
     * field on a gated type fails closed in {@link #admittedToFrame}.
     */
    @Nullable
    private static JsonNode messageCreatedAtNode(JsonNode root) {
        JsonNode payload = root.path("payload");
        return switch (root.path("eventType").asText("")) {
            case "MESSAGE_CREATED", "MESSAGE_UPDATED" -> payload.path("createdAt");
            case "MESSAGE_DELETED", "PIN_UPDATED", "REACTION_UPDATED" ->
                    payload.path("messageCreatedAt");
            default -> null;
        };
    }

    @Nullable
    private static Instant parseInstant(JsonNode node) {
        try {
            if (node.isNumber()) {
                java.math.BigDecimal seconds = node.decimalValue();
                long whole = seconds.longValue();
                return Instant.ofEpochSecond(whole, seconds.subtract(java.math.BigDecimal.valueOf(whole))
                        .movePointRight(9).longValue());
            }
            return node.isTextual() ? Instant.parse(node.asText()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isAuthenticatedOrInternalMessage(@Nullable String sessionId) {
        return sessionId == null || sessionRegistry.isAuthenticatedSession(sessionId);
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
