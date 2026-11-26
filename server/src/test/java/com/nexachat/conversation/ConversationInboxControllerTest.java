package com.nexachat.conversation;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.conversation.dto.InboxEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationInboxControllerTest {

    @Mock
    private InboxService inboxService;

    private ConversationInboxController controller;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new ConversationInboxController(inboxService);
    }

    private static List<InboxEntry> entries() {
        return List.of(new InboxEntry(
                UUID.randomUUID(), "DIRECT", null, null, List.of(), null,
                0L, 0L, null, false, null, false, false, false, null));
    }

    @Test
    void noPaginationParamsKeepExistingListBodyAndNoCursorHeader() {
        List<InboxEntry> body = entries();
        when(inboxService.listInbox(userId, null)).thenReturn(body);

        ResponseEntity<List<InboxEntry>> response = controller.inbox(
                new JwtPrincipal(userId), null, null, null);

        assertThat(response.getBody()).isSameAs(body);
        assertThat(response.getHeaders().getFirst("X-Next-Cursor")).isNull();
        verify(inboxService).listInbox(userId, null);
        verify(inboxService, never()).listInbox(userId, null, null, null);
    }

    @Test
    void paginationReturnsSameListShapeAndCursorHeader() {
        List<InboxEntry> body = entries();
        when(inboxService.listInbox(userId, 20, "cursor-in", null))
                .thenReturn(new InboxService.InboxPage(body, "cursor-out"));

        ResponseEntity<List<InboxEntry>> response = controller.inbox(
                new JwtPrincipal(userId), 20, "cursor-in", null);

        assertThat(response.getBody()).isSameAs(body);
        assertThat(response.getHeaders().getFirst("X-Next-Cursor")).isEqualTo("cursor-out");
        verify(inboxService).listInbox(userId, 20, "cursor-in", null);
    }

    @Test
    void archivedFilterIsForwardedWhenNoPagingParamsArePresent() {
        List<InboxEntry> body = entries();
        when(inboxService.listInbox(userId, true)).thenReturn(body);

        ResponseEntity<List<InboxEntry>> response = controller.inbox(
                new JwtPrincipal(userId), null, null, true);

        assertThat(response.getBody()).isSameAs(body);
        verify(inboxService).listInbox(userId, true);
    }

    @Test
    void archivedFilterIsForwardedWithPagingParams() {
        List<InboxEntry> body = entries();
        when(inboxService.listInbox(userId, 20, null, false))
                .thenReturn(new InboxService.InboxPage(body, null));

        ResponseEntity<List<InboxEntry>> response = controller.inbox(
                new JwtPrincipal(userId), 20, null, false);

        assertThat(response.getBody()).isSameAs(body);
        assertThat(response.getHeaders().getFirst("X-Next-Cursor")).isNull();
        verify(inboxService).listInbox(userId, 20, null, false);
    }

    @Test
    void labelWithoutPaginationKeepsListShapeAndArchiveFilter() {
        UUID label = UUID.randomUUID();
        List<InboxEntry> body = entries();
        when(inboxService.listInbox(userId, true, label)).thenReturn(body);

        ResponseEntity<List<InboxEntry>> response = controller.inbox(
                new JwtPrincipal(userId), null, null, true, label);

        assertThat(response.getBody()).isSameAs(body);
        assertThat(response.getHeaders()).doesNotContainKey("X-Next-Cursor");
        verify(inboxService).listInbox(userId, true, label);
    }

    @Test
    void labelWithCursorOnlyStaysOnLabeledPagedPath() {
        UUID label = UUID.randomUUID();
        List<InboxEntry> body = entries();
        when(inboxService.listInbox(userId, null, "next-in", true, label))
                .thenReturn(new InboxService.InboxPage(body, "next-out"));

        ResponseEntity<List<InboxEntry>> response = controller.inbox(
                new JwtPrincipal(userId), null, "next-in", true, label);

        assertThat(response.getBody()).isSameAs(body);
        assertThat(response.getHeaders().getFirst("X-Next-Cursor")).isEqualTo("next-out");
        verify(inboxService).listInbox(userId, null, "next-in", true, label);
    }

    @Test
    void callerIsRequiredBeforeDispatchingInboxRead() {
        ApiException failure = catchThrowableOfType(() ->
                controller.inbox(null, 5, "cursor", true, UUID.randomUUID()), ApiException.class);

        assertThat(failure.getCode()).isEqualTo(ApiErrorCode.UNAUTHORIZED);
        verifyNoInteractions(inboxService);
    }
}
