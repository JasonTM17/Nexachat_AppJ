package com.nexachat.feature.inbox.adapter;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import com.nexachat.R;
import com.nexachat.data.ConversationSummary;
import com.nexachat.feature.inbox.InboxPreviewKind;
import org.junit.Test;

public final class ConversationPreviewHelperTest {

    @Test
    public void directTextPreviewPreservesMessageContent() {
        Context context = mock(Context.class);
        ConversationSummary conversation = conversation("DIRECT", null, "hello there", null, false);

        assertEquals("hello there", ConversationPreviewHelper.previewText(context, conversation, "me"));
        assertEquals(InboxPreviewKind.TEXT, ConversationPreviewHelper.previewKind(conversation));
    }

    @Test
    public void groupPreviewPrefixesSenderButDirectPreviewDoesNot() {
        Context context = mock(Context.class);
        ConversationSummary group = conversation("GROUP", "Team", "hello", "Mai", false);
        ConversationSummary direct = conversation("DIRECT", null, "hello", "Mai", false);

        assertEquals("Mai: hello", ConversationPreviewHelper.previewText(context, group, "me"));
        assertEquals("hello", ConversationPreviewHelper.previewText(context, direct, "me"));
    }

    @Test
    public void mediaMarkersUseLocalizedLabels() {
        Context context = mock(Context.class);
        when(context.getString(R.string.inbox_preview_photo)).thenReturn("Photo");
        when(context.getString(R.string.inbox_preview_voice)).thenReturn("Voice message");
        when(context.getString(R.string.inbox_preview_file)).thenReturn("File");

        assertEquals("Photo", ConversationPreviewHelper.previewText(
                context, conversation("DIRECT", null, "IMAGE|photo.jpg", null, false), "me"));
        assertEquals("Voice message", ConversationPreviewHelper.previewText(
                context, conversation("DIRECT", null, "VOICE|clip.m4a", null, false), "me"));
        assertEquals("File", ConversationPreviewHelper.previewText(
                context, conversation("DIRECT", null, "FILE|report.pdf", null, false), "me"));
    }

    @Test
    public void deletedMessageUsesDeletedLabelAndTextKind() {
        Context context = mock(Context.class);
        when(context.getString(R.string.message_deleted)).thenReturn("Message deleted");
        ConversationSummary deleted = conversation("DIRECT", null, "IMAGE|old.jpg", null, true);

        assertEquals("Message deleted", ConversationPreviewHelper.previewText(context, deleted, "me"));
        assertEquals(InboxPreviewKind.TEXT, ConversationPreviewHelper.previewKind(deleted));
    }

    @Test
    public void displayTitleUsesResolvedNameAndLocalizedFallback() {
        Context context = mock(Context.class);
        when(context.getString(R.string.chat_title)).thenReturn("Chat");

        assertEquals("Project room", ConversationPreviewHelper.displayTitle(
                context, conversation("GROUP", "Project room", null, null, false)));
        assertEquals("Chat", ConversationPreviewHelper.displayTitle(
                context, conversation("DIRECT", null, null, null, false)));
    }

    @Test
    public void systemLastMessageLocalizesWithoutGroupSenderPrefix() {
        Context context = mock(Context.class);
        when(context.getString(R.string.system_event_e2ee_enabled, "An"))
                .thenReturn("An turned on end-to-end encryption");
        ConversationSummary group = conversation(
                "GROUP", "Team", "An turned on end-to-end encryption", "An",
                false, "SYSTEM");

        assertEquals("An turned on end-to-end encryption",
                ConversationPreviewHelper.previewText(context, group, "me"));
    }

    @Test
    public void textMessageWithSystemLikeWordingStaysRawWithPrefix() {
        Context context = mock(Context.class);
        ConversationSummary group = conversation(
                "GROUP", "Team", "An added Binh", "An", false, "TEXT");

        assertEquals("An: An added Binh",
                ConversationPreviewHelper.previewText(context, group, "me"));
    }

    @Test
    public void systemTypedCallLogStillResolvesDirectionAndPrefix() {
        Context context = mock(Context.class);
        when(context.getString(eq(R.string.call_log_outgoing_completed), any()))
                .thenReturn("Outgoing call · 0:42");
        ConversationSummary group = conversation(
                "GROUP", "Team", "CALL|COMPLETED|42|me", "An", false, "SYSTEM");

        assertEquals("An: Outgoing call · 0:42",
                ConversationPreviewHelper.previewText(context, group, "me"));
    }

    @Test
    public void nullLastMessageTypeKeepsLegacyRawBehavior() {
        Context context = mock(Context.class);
        ConversationSummary group = conversation(
                "GROUP", "Team", "An added Binh", "An", false, null);

        assertEquals("An: An added Binh",
                ConversationPreviewHelper.previewText(context, group, "me"));
    }

    private static ConversationSummary conversation(
            String type, String title, String content, String sender, boolean deleted) {
        return conversation(type, title, content, sender, deleted, null);
    }

    private static ConversationSummary conversation(
            String type, String title, String content, String sender, boolean deleted,
            String lastMessageType) {
        return new ConversationSummary(
                "conversation", type, title, content, sender, null, 1L, 0, 0L,
                null, null, null, 0L, 0L, null, null, deleted, null,
                false, null, false, false, false, null,
                java.util.List.of(), false, false, lastMessageType);
    }
}
