package com.nexachat.conversation;

import com.nexachat.conversation.dto.ConversationResponse;
import com.nexachat.conversation.dto.InboxEntry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public conversation payloads expose the caller's own per-conversation
 * AI opt-out state on both the conversation response and the inbox entry.
 */
class AiOptOutContractTest {

    @Test
    void conversationDetailAndInboxExposeTheCallersAiOptOutFlag() {
        assertThat(Arrays.stream(ConversationResponse.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("aiOptOut");
        assertThat(Arrays.stream(InboxEntry.class.getRecordComponents())
                .map(component -> component.getName()))
                .contains("aiOptOut");
    }
}
