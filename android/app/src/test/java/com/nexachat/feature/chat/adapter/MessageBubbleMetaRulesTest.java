package com.nexachat.feature.chat.adapter;

import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class MessageBubbleMetaRulesTest {

    @Test
    public void callLogSystemRowKeepsTimestampWithoutReceiptMeta() {
        ChatMessage callLog = new ChatMessage(
                "call-1", "conv-1", 1L, "u1", "Alice",
                MessageDto.TYPE_TEXT, "CALL|missed", null, null, null,
                1_700_000_123_000L, null, DeliveryStatus.SENT, false,
                Collections.emptyList(), Collections.emptyList(), null, null);

        assertEquals(
                MessageBubbleMetaRules.MetaRowPolicy.TIMESTAMP_WITHOUT_RECEIPTS,
                MessageBubbleMetaRules.policyFor(callLog));
        assertNotEquals(MessageBubbleMetaRules.MetaRowPolicy.HIDDEN,
                MessageBubbleMetaRules.policyFor(callLog));
    }
}
