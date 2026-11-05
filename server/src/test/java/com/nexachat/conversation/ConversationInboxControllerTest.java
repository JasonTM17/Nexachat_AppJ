package com.nexachat.conversation;

import com.nexachat.common.dto.JwtPrincipal;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void noPaginationParamsKeepExistingListBodyAndNoCursorHeader() {
        List<InboxEntry> entries = List.of(new InboxEntry(
                UUID.randomUUID(), "DIRECT", null, null, List.of(), null, 0L, 0L));
        when(inboxService.listInbox(userId)).thenReturn(entries);

        ResponseEntity<List<InboxEntry>> response = controller.inbox(
                new JwtPrincipal(userId), null, null);

        assertThat(response.getBody()).isSameAs(entries);
        assertThat(response.getHeaders().getFirst("X-Next-Cursor")).isNull();
        verify(inboxService).listInbox(userId);
        verify(inboxService, never()).listInbox(userId, null, null);
    }

    @Test
    void paginationReturnsSameListShapeAndCursorHeader() {
        List<InboxEntry> entries = List.of(new InboxEntry(
                UUID.randomUUID(), "DIRECT", null, null, List.of(), null, 0L, 0L));
        when(inboxService.listInbox(userId, 20, "cursor-in"))
                .thenReturn(new InboxService.InboxPage(entries, "cursor-out"));

        ResponseEntity<List<InboxEntry>> response = controller.inbox(
                new JwtPrincipal(userId), 20, "cursor-in");

        assertThat(response.getBody()).isSameAs(entries);
        assertThat(response.getHeaders().getFirst("X-Next-Cursor")).isEqualTo("cursor-out");
        verify(inboxService).listInbox(userId, 20, "cursor-in");
    }
}
