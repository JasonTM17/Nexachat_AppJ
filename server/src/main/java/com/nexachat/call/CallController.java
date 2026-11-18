package com.nexachat.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.ws.StompUserPrincipal;
import com.nexachat.ws.dto.WsErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import jakarta.validation.Valid;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;

@Controller
public class CallController {

    private static final Logger log = LoggerFactory.getLogger(CallController.class);

    private final CallService callService;
    private final ObjectMapper objectMapper;

    public CallController(CallService callService, ObjectMapper objectMapper) {
        this.callService = callService;
        this.objectMapper = objectMapper;
    }

    @MessageMapping("/calls/{conversationId}/signal")
    public void signal(@DestinationVariable("conversationId") UUID conversationId,
                       @Payload @Valid CallSignal signal,
                       Principal principal) {
        callService.signal(conversationId, requireUserId(principal), signal);
    }

    @MessageExceptionHandler(CallException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public WsErrorResponse onCallError(CallException exception, Message<?> message) {
        // Correlate the rejection with the call so the client can end the
        // in-progress call instead of silently ringing on.
        return new WsErrorResponse(exception.getCode().name(), null,
                extractCallId(message), extractConversationId(message));
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public WsErrorResponse onUnexpected(Exception exception, Message<?> message) {
        log.warn("Unexpected error while handling a call signal ({})", exception.getClass().getSimpleName());
        return new WsErrorResponse("VALIDATION_FAILED", null,
                extractCallId(message), extractConversationId(message));
    }

    private UUID extractCallId(Message<?> message) {
        if (message == null) {
            return null;
        }
        Object payload = message.getPayload();
        String text = payload instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : payload instanceof String string ? string : null;
        if (text == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(text).path("callId");
            return node.isTextual() ? UUID.fromString(node.asText()) : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static UUID extractConversationId(Message<?> message) {
        if (message == null) {
            return null;
        }
        String destination = SimpMessageHeaderAccessor.wrap(message).getDestination();
        if (destination == null) {
            return null;
        }
        // "/app/calls/{conversationId}/signal"
        String[] parts = destination.split("/");
        if (parts.length < 4 || !"calls".equals(parts[2])) {
            return null;
        }
        try {
            return UUID.fromString(parts[3]);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static UUID requireUserId(Principal principal) {
        if (principal instanceof StompUserPrincipal user) {
            return user.userId();
        }
        throw CallException.notMember();
    }
}
