package com.nexachat.ws;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.message.MessageService;
import com.nexachat.message.dto.ReadRequest;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.ws.dto.TypingSignal;
import com.nexachat.ws.dto.WsErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP application endpoints (frozen contract, plan §11.2):
 *
 * <ul>
 *   <li>{@code /app/conversations/{id}/messages} →
 *       {@link MessageService#send} — broadcast happens AFTER COMMIT from the
 *       service's published event; nothing is echoed here.</li>
 *   <li>{@code /app/conversations/{id}/typing} →
 *       {@code USER_TYPING} on the conversation topic. The sender is not
 *       excluded server-side: the simple broker has no per-frame subscriber
 *       filtering, so the payload carries {@code userId} and each client
 *       drops its own signal (documented contract detail).</li>
 *   <li>{@code /app/conversations/{id}/read} →
 *       {@link MessageService#markRead} — the same watermark logic as the
 *       REST endpoint, broadcast as {@code MESSAGE_READ}.</li>
 * </ul>
 *
 * <p>Error path (spec choice): service {@link ApiException}s are rethrown as
 * {@link SendRejectedException} carrying the request's
 * {@code clientMessageId}; {@code @MessageExceptionHandler} +
 * {@code @SendToUser("/queue/errors", broadcast=false)} routes
 * {@code {code, clientMessageId}} back to the originating session only.
 * Membership was already enforced by {@link StompAuthChannelInterceptor}, so
 * service-level rejections reaching here are payload races, not authZ gaps.
 */
@Controller
public class WsMessageController {

    private static final Logger log = LoggerFactory.getLogger(WsMessageController.class);

    private final MessageService messageService;
    private final SimpMessagingTemplate messaging;

    public WsMessageController(MessageService messageService, SimpMessagingTemplate messaging) {
        this.messageService = messageService;
        this.messaging = messaging;
    }

    @MessageMapping("/conversations/{id}/messages")
    public void send(@DestinationVariable("id") UUID conversationId,
                     @Payload SendMessageRequest request,
                     Principal principal) {
        try {
            messageService.send(requireUserId(principal), conversationId, request);
        } catch (ApiException e) {
            throw new SendRejectedException(e, request == null ? null : request.clientMessageId());
        }
    }

    @MessageMapping("/conversations/{id}/typing")
    public void typing(@DestinationVariable("id") UUID conversationId, Principal principal) {
        UUID userId = requireUserId(principal);
        messaging.convertAndSend(
                "/topic/conversations." + conversationId,
                EventEnvelope.of(WsEventType.USER_TYPING, conversationId, new TypingSignal(userId)));
    }

    @MessageMapping("/conversations/{id}/read")
    public void read(@DestinationVariable("id") UUID conversationId,
                     @Payload ReadRequest request,
                     Principal principal) {
        try {
            messageService.markRead(requireUserId(principal), conversationId, request);
        } catch (ApiException e) {
            throw new SendRejectedException(e, null);
        }
    }

    @MessageExceptionHandler(SendRejectedException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public WsErrorResponse onSendRejected(SendRejectedException ex) {
        return new WsErrorResponse(ex.getCode().getCode(), ex.getClientMessageId());
    }

    @MessageExceptionHandler(ApiException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public WsErrorResponse onApiError(ApiException ex) {
        return new WsErrorResponse(ex.getCode().getCode(), null);
    }

    /**
     * Payload-conversion and other messaging failures: the clientMessageId is
     * unknowable (the body never parsed), so only the code is reported.
     */
    @MessageExceptionHandler(Exception.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public WsErrorResponse onUnexpected(Exception ex) {
        ApiErrorCode code = isPayloadValidationFailure(ex)
                ? ApiErrorCode.VALIDATION_FAILED
                : ApiErrorCode.INTERNAL_ERROR;
        if (code == ApiErrorCode.VALIDATION_FAILED) {
            log.warn("Rejected invalid STOMP payload");
        } else if (log.isErrorEnabled()) {
            log.error("Unhandled STOMP handler exception ({})", ex.getClass().getSimpleName());
        }
        return new WsErrorResponse(code.getCode(), null);
    }

    private static boolean isPayloadValidationFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof MessageConversionException
                    || cause instanceof MethodArgumentNotValidException
                    || cause instanceof ConstraintViolationException
                    || cause instanceof ConversionException) {
                return true;
            }
        }
        return false;
    }

    private static UUID requireUserId(Principal principal) {
        if (principal instanceof StompUserPrincipal user) {
            return user.userId();
        }
        throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
    }
}
