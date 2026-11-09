package com.nexachat.ws;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.user.UserRepository;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reciprocity half of the "seen" privacy switch: a user who hides read
 * receipts also stops receiving other members' {@code MESSAGE_READ} frames.
 *
 * <p>The simple broker fans a topic frame out to every subscriber, so the
 * client outbound channel — which runs once per recipient session — is the
 * only place where a per-recipient receipt filter is possible without changing
 * the frozen topic contract. Only {@code MESSAGE_READ} envelopes are
 * inspected; every other frame passes untouched.
 */
@Component
public class StompSeenPrivacyOutboundInterceptor implements ChannelInterceptor {

    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern CONVERSATION_TOPIC =
            Pattern.compile("^/topic/conversations\\.(" + UUID_PATTERN + ")$");
    private static final String MESSAGE_READ = WsEventType.MESSAGE_READ.name();

    private final UserRepository userRepository;
    private final StompSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public StompSeenPrivacyOutboundInterceptor(UserRepository userRepository,
                                               StompSessionRegistry sessionRegistry,
                                               ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    @Nullable
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        Matcher topic = destination == null ? null : CONVERSATION_TOPIC.matcher(destination);
        if (topic == null || !topic.matches() || !isReadReceipt(message.getPayload())) {
            return message;
        }
        UUID userId = sessionRegistry.userIdForSession(
                SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
        if (userId == null) {
            // Unbound sessions are already dropped by the membership
            // interceptor; without a recipient there is no privacy decision.
            return message;
        }
        return userRepository.findShowSeenById(userId).orElse(true) ? message : null;
    }

    /**
     * Reads only the {@code eventType} field of the envelope, so a large
     * {@code MESSAGE_CREATED} payload is never materialised just to decide
     * that it is not a receipt.
     */
    private boolean isReadReceipt(Object payload) {
        if (payload instanceof EventEnvelope envelope) {
            return envelope.eventType() == WsEventType.MESSAGE_READ;
        }
        JsonParser parser = null;
        try {
            if (payload instanceof byte[] bytes) {
                parser = objectMapper.getFactory().createParser(bytes);
            } else if (payload instanceof String text) {
                parser = objectMapper.getFactory().createParser(text);
            } else {
                return false;
            }
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("eventType".equals(field)) {
                    return value == JsonToken.VALUE_STRING
                            && MESSAGE_READ.equals(parser.getText());
                }
                parser.skipChildren();
            }
        } catch (IOException ignored) {
            // Not a JSON envelope: nothing to filter.
        } finally {
            if (parser != null) {
                try {
                    parser.close();
                } catch (IOException ignored) {
                    // Nothing actionable.
                }
            }
        }
        return false;
    }
}
