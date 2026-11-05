package com.nexachat.ws;

import com.nexachat.message.MessageService;
import com.nexachat.message.dto.SendMessageRequest;
import com.nexachat.ws.dto.WsErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeTypeUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class WsMessageControllerTest {

    @Test
    void malformedClientMessageIdMapsToValidationFailed() {
        byte[] payload = ("{\"clientMessageId\":\"not-a-uuid\","
                + "\"type\":\"TEXT\",\"content\":\"hello\"}")
                .getBytes(StandardCharsets.UTF_8);
        Message<byte[]> json = MessageBuilder.withPayload(payload)
                .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                .build();
        MessageConversionException conversion = assertThrows(MessageConversionException.class,
                () -> new MappingJackson2MessageConverter().fromMessage(json, SendMessageRequest.class));

        WsMessageController controller = new WsMessageController(
                mock(MessageService.class), mock(SimpMessagingTemplate.class));

        WsErrorResponse error = controller.onUnexpected(conversion);

        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(error.clientMessageId()).isNull();
    }
}
