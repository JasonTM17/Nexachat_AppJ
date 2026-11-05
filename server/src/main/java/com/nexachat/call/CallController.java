package com.nexachat.call;

import com.nexachat.ws.StompUserPrincipal;
import com.nexachat.ws.dto.WsErrorResponse;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import jakarta.validation.Valid;

import java.security.Principal;
import java.util.UUID;

@Controller
public class CallController {

    private final CallService callService;

    public CallController(CallService callService) {
        this.callService = callService;
    }

    @MessageMapping("/calls/{conversationId}/signal")
    public void signal(@DestinationVariable("conversationId") UUID conversationId,
                       @Payload @Valid CallSignal signal,
                       Principal principal) {
        callService.signal(conversationId, requireUserId(principal), signal);
    }

    @MessageExceptionHandler(CallException.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public WsErrorResponse onCallError(CallException exception) {
        return new WsErrorResponse(exception.getCode().name(), null);
    }

    @MessageExceptionHandler(Exception.class)
    @SendToUser(value = "/queue/errors", broadcast = false)
    public WsErrorResponse onUnexpected() {
        return new WsErrorResponse("VALIDATION_FAILED", null);
    }

    private static UUID requireUserId(Principal principal) {
        if (principal instanceof StompUserPrincipal user) {
            return user.userId();
        }
        throw CallException.notMember();
    }
}
