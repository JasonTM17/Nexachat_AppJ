package com.nexachat.message;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.common.error.GlobalExceptionHandler;
import com.nexachat.message.dto.SyncRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SyncNullWatermarkTest {
    private final MessageService service = new MessageService(
            mock(MessageRepository.class), mock(MessageAttachmentRepository.class),
            mock(MessageReactionRepository.class), mock(ReactionEventRepository.class),
            mock(com.nexachat.conversation.ConversationRepository.class),
            mock(com.nexachat.conversation.ConversationMemberRepository.class),
            mock(PinnedMessageRepository.class), mock(com.nexachat.attachment.StorageService.class),
            mock(org.springframework.context.ApplicationEventPublisher.class), new SyncProperties());

    @ParameterizedTest
    @CsvSource({"/sync,false", "/sync,true", "/chat/sync,false", "/chat/sync,true"})
    void rejectsNullElementsOverHttp(String alias, boolean mixed) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new SyncController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter) {
                        return parameter.getParameterType() == JwtPrincipal.class;
                    }
                    public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                            org.springframework.web.method.support.ModelAndViewContainer container,
                            org.springframework.web.context.request.NativeWebRequest request,
                            org.springframework.web.bind.support.WebDataBinderFactory factory) {
                        return new JwtPrincipal(UUID.randomUUID());
                    }
                }).build();
        String entries = mixed ? "{\"conversationId\":\"" + UUID.randomUUID()
                + "\",\"lastSeq\":0},null" : "null";
        mvc.perform(post("/api/v1" + alias).contentType("application/json").accept("application/json")
                        .content("{\"watermarks\":[" + entries + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsNullElementsAtServiceBoundary() {
        var valid = new SyncRequest.ConversationWatermark(UUID.randomUUID(), 0L);
        for (var watermarks : java.util.List.of(Arrays.asList((SyncRequest.ConversationWatermark) null),
                Arrays.asList(valid, null))) {
            assertThatThrownBy(() -> service.syncDelta(UUID.randomUUID(), new SyncRequest(watermarks, 50, null)))
                    .isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo(ApiErrorCode.VALIDATION_FAILED);
        }
    }
}
