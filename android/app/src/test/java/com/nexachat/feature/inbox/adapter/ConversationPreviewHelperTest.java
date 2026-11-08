package com.nexachat.feature.inbox.adapter;

import static org.junit.Assert.assertEquals;
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

    private static ConversationSummary conversation(
            String type, String title, String content, String sender, boolean deleted) {
        return new ConversationSummary(
                "conversation", type, title, content, sender, null, 1L, 0,
                null, null, null, 0L, null, null, deleted, null);
    }
}
