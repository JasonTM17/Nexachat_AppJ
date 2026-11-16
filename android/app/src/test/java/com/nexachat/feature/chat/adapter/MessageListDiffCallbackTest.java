package com.nexachat.feature.chat.adapter;

import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.MessageMention;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.List;
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

    @Test
    public void identicalMessagesHaveSameContents() {
        assertContentsSame(spec().build(), spec().build());
    }

    @Test
    public void deliveredReceiptFlipDetected() {
        ChatMessage msg = spec().build();
        MessageListItem single = MessageListItem.message(
                msg, MessageGroupPosition.SINGLE, false, false, false, false);
        MessageListItem doubleTick = MessageListItem.message(
                msg, MessageGroupPosition.SINGLE, false, false, true, false);
        assertTrue(callback.areItemsTheSame(single, doubleTick));
        assertFalse(callback.areContentsTheSame(single, doubleTick));
    }

    @Test
    public void replyToEncryptedFlipDetected() {
        Spec before = spec();
        Spec after = spec();
        after.replyToEncrypted = true;
        assertContentsDiffer(before.build(), after.build());
    }

    @Test
    public void mentionsFlipDetected() {
        Spec before = spec();
        Spec after = spec();
        after.mentions = List.of(new MessageMention("u2", 0, 4));
        assertContentsDiffer(before.build(), after.build());
    }

    @Test
    public void mentionAllFlipDetected() {
        Spec before = spec();
        Spec after = spec();
        after.mentionAll = true;
        assertContentsDiffer(before.build(), after.build());
    }

    @Test
    public void decryptFailedFlipDetected() {
        Spec before = spec();
        Spec after = spec();
        after.decryptFailed = true;
        assertContentsDiffer(before.build(), after.build());
    }

    @Test
    public void pausedRefusalFlipDetected() {
        Spec before = spec();
        Spec after = spec();
        after.pausedRefusal = true;
        assertContentsDiffer(before.build(), after.build());
    }

    @Test
    public void sameCountAttachmentContentFlipDetected() {
        Spec before = spec();
        Spec after = spec();
        before.attachments = List.of(new AttachmentDto("att-1", "a.png", "image/png"));
        after.attachments = List.of(new AttachmentDto("att-1", "b.png", "image/png"));
        assertContentsDiffer(before.build(), after.build());
    }

    @Test
    public void senderIdFlipDetected() {
        Spec before = spec();
        Spec after = spec();
        after.senderId = "u2";
        assertContentsDiffer(before.build(), after.build());
    }

    @Test
    public void clientMessageIdFlipDetected() {
        Spec before = spec();
        Spec after = spec();
        after.clientMessageId = "client-2";
        assertContentsDiffer(before.build(), after.build());
    }

    private void assertContentsSame(ChatMessage before, ChatMessage after) {
        MessageListItem left = messageItem(before);
        MessageListItem right = messageItem(after);
        assertTrue(callback.areItemsTheSame(left, right));
        assertTrue(callback.areContentsTheSame(left, right));
    }

    private void assertContentsDiffer(ChatMessage before, ChatMessage after) {
        MessageListItem left = messageItem(before);
        MessageListItem right = messageItem(after);
        assertTrue(callback.areItemsTheSame(left, right));
        assertFalse(callback.areContentsTheSame(left, right));
    }

    private static MessageListItem messageItem(ChatMessage message) {
        return MessageListItem.message(
                message, MessageGroupPosition.SINGLE, false, false, false);
    }

    private static Spec spec() {
        return new Spec();
    }

    private static ChatMessage baseMessage(String content) {
        Spec spec = spec();
        spec.content = content;
        return spec.build();
    }

    private static final class Spec {
        String id = "m1";
        String conversationId = "c1";
        long seq = 2L;
        String senderId = "u1";
        String senderDisplayName = "User";
        String type = MessageDto.TYPE_TEXT;
        String content = "same";
        long createdAtEpochMs = 1_000L;
        String clientMessageId = "client-1";
        DeliveryStatus status = DeliveryStatus.SENT;
        boolean mine = true;
        List<AttachmentDto> attachments = List.of();
        List<MessageMention> mentions = List.of();
        boolean mentionAll;
        boolean replyToEncrypted;
        boolean decryptFailed;
        boolean pausedRefusal;

        ChatMessage build() {
            return new ChatMessage(
                    id,
                    conversationId,
                    seq,
                    senderId,
                    senderDisplayName,
                    type,
                    content,
                    null,
                    null,
                    null,
                    createdAtEpochMs,
                    clientMessageId,
                    status,
                    mine,
                    attachments,
                    List.of(),
                    null,
                    null,
                    mentions,
                    mentionAll,
                    false,
                    decryptFailed,
                    replyToEncrypted,
                    pausedRefusal
            );
        }
    }
}
