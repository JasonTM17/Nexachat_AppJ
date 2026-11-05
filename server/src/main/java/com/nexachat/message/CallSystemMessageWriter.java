package com.nexachat.message;

import com.nexachat.call.CallSession;
import org.springframework.stereotype.Component;

/** Writes the stable call-log token through the existing SYSTEM message path. */
@Component
public class CallSystemMessageWriter {

    private final MessageService messageService;

    public CallSystemMessageWriter(MessageService messageService) {
        this.messageService = messageService;
    }

    public void write(CallSession call) {
        String content = "CALL|" + call.endReason().name() + "|" + call.durationSeconds();
        messageService.postSystemMessage(call.callerId(), call.conversationId(), content);
    }
}
