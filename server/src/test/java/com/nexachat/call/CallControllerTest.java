package com.nexachat.call;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.ws.StompUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CallControllerTest {

    private static CallController controller() {
        return new CallController(mock(CallService.class), new ObjectMapper());
    }

    private static Message<byte[]> sendMessage(UUID conversationId, UUID callId) {
        String body = String.format("{\"type\":\"INVITE\",\"callId\":\"%s\",\"mediaType\":\"AUDIO\"}", callId);
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setDestination("/app/calls/" + conversationId + "/signal");
        accessor.setLeaveMutable(true);
        return MessageBuilder.withPayload(body.getBytes(StandardCharsets.UTF_8))
                .copyHeaders(accessor.getMessageHeaders())
                .build();
    }

    @Test
    void forwardsValidatedFrameUsingAuthenticatedStompPrincipal() {
        CallService service = mock(CallService.class);
        CallController controller = new CallController(service, new ObjectMapper());
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CallSignal signal = new CallSignal(CallSignal.Type.END, UUID.randomUUID(), null, null);

        controller.signal(conversationId, signal, new StompUserPrincipal(userId));

        verify(service).signal(conversationId, userId, signal);
    }

    @Test
    void rejectsMissingPrincipalAndMapsDomainErrorsToTheExistingErrorEnvelope() {
        CallController controller = controller();
        Message<byte[]> send = sendMessage(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> controller.signal(UUID.randomUUID(),
                new CallSignal(CallSignal.Type.INVITE, UUID.randomUUID(), null, null), null))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.NOT_MEMBER);

        assertThat(controller.onCallError(CallException.validation("invalid"), send).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(controller.onCallError(CallException.notFound(), send).code()).isEqualTo("CALL_NOT_FOUND");
        // A server bug must not masquerade as a client validation error.
        assertThat(controller.onUnexpected(new RuntimeException(), send).code())
                .isEqualTo("INTERNAL_ERROR");
        // Only payload conversion/validation failures stay VALIDATION_FAILED.
        assertThat(controller.onUnexpected(
                new org.springframework.messaging.converter.MessageConversionException("bad frame"), send)
                .code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void correlatesErrorEnvelopeWithCallAndConversation() {
        CallController controller = controller();
        UUID conversationId = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        Message<byte[]> send = sendMessage(conversationId, callId);

        var error = controller.onCallError(CallException.notMember(), send);

        assertThat(error.code()).isEqualTo("NOT_MEMBER");
        assertThat(error.callId()).isEqualTo(callId);
        assertThat(error.conversationId()).isEqualTo(conversationId);
    }

    @Test
    void toleratesUnparseableErrorCorrelation() {
        CallController controller = controller();
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setDestination("/app/calls/not-a-uuid/signal");
        accessor.setLeaveMutable(true);
        Message<byte[]> send = MessageBuilder.withPayload("not json".getBytes(StandardCharsets.UTF_8))
                .copyHeaders(accessor.getMessageHeaders())
                .build();

        var error = controller.onCallError(CallException.notFound(), send);

        assertThat(error.callId()).isNull();
        assertThat(error.conversationId()).isNull();
    }
}
