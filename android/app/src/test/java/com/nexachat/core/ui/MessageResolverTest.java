package com.nexachat.core.ui;

import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class MessageResolverTest {

    private ChatMessage createMessage(String id, Long deletedAt) {
        return new ChatMessage(
                id,
                "conv-1",
                1L,
                "user-1",
                "User",
                "TEXT",
                "Hello " + id,
                null,
                null,
                null,
                1000L,
                null,
                DeliveryStatus.SENT,
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                deletedAt
        );
    }

    @Test
    public void findById_returnsMatchingMessage() {
        ChatMessage msg1 = createMessage("msg-1", null);
        ChatMessage msg2 = createMessage("msg-2", null);
        List<ChatMessage> list = List.of(msg1, msg2);

        ChatMessage resolved = MessageResolver.findById(list, "msg-2");
        assertNotNull(resolved);
        assertEquals("msg-2", resolved.getId());
    }

    @Test
    public void findById_returnsNullWhenNotFoundOrNullInputs() {
        ChatMessage msg1 = createMessage("msg-1", null);
        List<ChatMessage> list = List.of(msg1);

        assertNull(MessageResolver.findById(list, "nonexistent"));
        assertNull(MessageResolver.findById(list, null));
        assertNull(MessageResolver.findById(list, "   "));
        assertNull(MessageResolver.findById(null, "msg-1"));
        assertNull(MessageResolver.findById(Collections.emptyList(), "msg-1"));
    }

    @Test
    public void resolveForEdit_returnsMessageWhenNotDeleted() {
        ChatMessage msg = createMessage("msg-1", null);
        List<ChatMessage> list = List.of(msg);

        ChatMessage resolved = MessageResolver.resolveForEdit(list, "msg-1");
        assertNotNull(resolved);
        assertEquals("msg-1", resolved.getId());
    }

    @Test
    public void resolveForEdit_returnsNullWhenDeleted() {
        ChatMessage deletedMsg = createMessage("msg-deleted", 1700000000L);
        List<ChatMessage> list = List.of(deletedMsg);

        assertNull("Deleted message must not resolve for edit", MessageResolver.resolveForEdit(list, "msg-deleted"));
    }

    @Test
    public void resolveForEdit_returnsNullWhenMissingAfterRotation() {
        ChatMessage msg1 = createMessage("msg-1", null);
        List<ChatMessage> list = List.of(msg1);

        // Edit target ID is for a message that disappeared from the list after rotation
        assertNull(MessageResolver.resolveForEdit(list, "msg-purged"));
    }

    @Test
    public void resolveForActionSheet_returnsNullWhenDeletedOrMissing() {
        ChatMessage normal = createMessage("msg-1", null);
        ChatMessage deleted = createMessage("msg-2", 1700000000L);
        List<ChatMessage> list = List.of(normal, deleted);

        assertNotNull(MessageResolver.resolveForActionSheet(list, "msg-1"));
        assertNull("Action sheet must not open/survive for deleted message", MessageResolver.resolveForActionSheet(list, "msg-2"));
        assertNull("Action sheet must not survive if message is missing", MessageResolver.resolveForActionSheet(list, "msg-unknown"));
    }
}
