package com.nexachat.conversation;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.MuteRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;

/**
 * The four mute/archive routes are thin: authenticated principal, path id and
 * body delegation, and an empty 204 — no response body to keep in sync with the
 * inbox flags.
 */
@ExtendWith(MockitoExtension.class)
class ConversationMuteArchiveControllerTest {

    @Mock
    private ConversationMuteArchiveService muteArchiveService;

    private ConversationMuteArchiveController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();
    private final Instant until = Instant.parse("2026-12-09T10:00:00Z");

    @BeforeEach
    void setUp() {
        controller = new ConversationMuteArchiveController(muteArchiveService);
    }

    @Test
    void mute_returns204AndDelegatesWithPrincipalAndPathId() {
        MuteRequest request = new MuteRequest(until, null);

        ResponseEntity<Void> response =
                controller.mute(new JwtPrincipal(userId), conversationId, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        verify(muteArchiveService).mute(userId, conversationId, request);
    }

    @Test
    void unmute_returns204AndDelegates() {
        ResponseEntity<Void> response =
                controller.unmute(new JwtPrincipal(userId), conversationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(muteArchiveService).unmute(userId, conversationId);
    }

    @Test
    void archive_returns204AndDelegates() {
        ResponseEntity<Void> response =
                controller.archive(new JwtPrincipal(userId), conversationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(muteArchiveService).archive(userId, conversationId);
    }

    @Test
    void unarchive_returns204AndDelegates() {
        ResponseEntity<Void> response =
                controller.unarchive(new JwtPrincipal(userId), conversationId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(muteArchiveService).unarchive(userId, conversationId);
    }

    @Test
    void missingPrincipal_isRejectedBeforeTheServiceRuns() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> controller.mute(null, conversationId, new MuteRequest(until, null)));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.UNAUTHORIZED);
        assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }
}
