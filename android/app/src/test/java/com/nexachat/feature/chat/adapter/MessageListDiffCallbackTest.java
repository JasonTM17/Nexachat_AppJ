package com.nexachat.feature.chat.adapter;

import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MessageListDiffCallbackTest {

    private final MessageListDiffCallback callback = new MessageListDiffCallback();

    @Test
    public void dateSeparatorsMatchByStableId() {
        MessageListItem left = MessageListItem.dateSeparator("Today", 100L);
        MessageListItem right = MessageListItem.dateSeparator("Today", 100L);
        assertTrue(callback.areItemsTheSame(left, right));
        assertTrue(callback.areContentsTheSame(left, right));
    }

    @Test
    public void messageContentChangeDetected() {
        ChatMessage before = baseMessage("body-a");
        ChatMessage after = baseMessage("body-b");
        MessageListItem left = MessageListItem.message(
                before, MessageGroupPosition.SINGLE, false, false, false);
        MessageListItem right = MessageListItem.message(
                after, MessageGroupPosition.SINGLE, false, false, false);
        assertTrue(callback.areItemsTheSame(left, right));
        assertFalse(callback.areContentsTheSame(left, right));
    }

    @Test
    public void pulsingFlagChangeDetected() {
        ChatMessage msg = baseMessage("same");
        MessageListItem calm = MessageListItem.message(
                msg, MessageGroupPosition.SINGLE, false, false, false);
        MessageListItem pulse = MessageListItem.message(
                msg, MessageGroupPosition.SINGLE, false, false, true);
        assertFalse(callback.areContentsTheSame(calm, pulse));
    }

    private static ChatMessage baseMessage(String content) {
        return new ChatMessage(
                "m1",
                "c1",
                2L,
                "u1",
                "User",
                MessageDto.TYPE_TEXT,
                content,
                null,
                null,
                null,
                1L,
                null,
                DeliveryStatus.SENT,
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null
        );
    }
}
