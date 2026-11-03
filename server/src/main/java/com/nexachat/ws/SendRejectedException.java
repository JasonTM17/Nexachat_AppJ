package com.nexachat.ws;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

import java.util.UUID;

/**
 * Transport-level wrapper: an {@link ApiException} raised by
 * {@code MessageService} inside a {@code @MessageMapping}, annotated with the
 * request's {@code clientMessageId} so the {@code /user/queue/errors} payload
 * can correlate the rejection with the optimistic SENDING entry.
 */
public class SendRejectedException extends RuntimeException {

    private final ApiErrorCode code;
    private final UUID clientMessageId;

    public SendRejectedException(ApiException cause, UUID clientMessageId) {
        super(cause.getMessage(), cause);
        this.code = cause.getCode();
        this.clientMessageId = clientMessageId;
    }

    public ApiErrorCode getCode() {
        return code;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }
}
