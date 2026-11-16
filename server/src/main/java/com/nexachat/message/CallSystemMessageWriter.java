package com.nexachat.message;

import com.nexachat.call.CallMediaType;
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
        // AUDIO keeps the original 4-part token so older clients render it
        // unchanged; VIDEO appends a fifth part those clients ignore.
        String content = "CALL|" + call.endReason().name() + "|" + call.durationSeconds()
                + "|" + call.callerId();
        if (call.mediaType() == CallMediaType.VIDEO) {
            content += "|" + call.mediaType().name();
        }
        messageService.postSystemMessage(call.callerId(), call.conversationId(), content);
    }
}
