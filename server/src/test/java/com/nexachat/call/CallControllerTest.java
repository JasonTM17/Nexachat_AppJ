package com.nexachat.call;

import com.nexachat.ws.StompUserPrincipal;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CallControllerTest {

    @Test
    void forwardsValidatedFrameUsingAuthenticatedStompPrincipal() {
        CallService service = mock(CallService.class);
        CallController controller = new CallController(service);
        UUID conversationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        CallSignal signal = new CallSignal(CallSignal.Type.END, UUID.randomUUID(), null, null);

        controller.signal(conversationId, signal, new StompUserPrincipal(userId));

        verify(service).signal(conversationId, userId, signal);
    }

    @Test
    void rejectsMissingPrincipalAndMapsDomainErrorsToTheExistingErrorEnvelope() {
        CallController controller = new CallController(mock(CallService.class));
        assertThatThrownBy(() -> controller.signal(UUID.randomUUID(),
                new CallSignal(CallSignal.Type.INVITE, UUID.randomUUID(), null, null), null))
                .isInstanceOf(CallException.class)
                .extracting("code").isEqualTo(CallException.Code.NOT_MEMBER);

        assertThat(controller.onCallError(CallException.validation("invalid" )).code())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(controller.onCallError(CallException.notFound()).code()).isEqualTo("CALL_NOT_FOUND");
        assertThat(controller.onUnexpected().code()).isEqualTo("VALIDATION_FAILED");
    }
}
