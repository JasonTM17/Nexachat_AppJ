package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.dto.MessageMention;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class MentionDraftTest {
    @Test public void offsetsUseUtf16AfterEmojiAndVietnameseText() {
        String text = "😀 Chào @Đặng ";
        int offset = text.indexOf("@Đặng");
        MentionDraft draft = MentionDraft.empty(text).withMention(text, "u-1", offset, "@Đặng".length());
        assertEquals(8, offset);
        assertEquals(5, draft.getMentions().get(0).getLength());
        assertEquals("@Đặng", text.substring(draft.getMentions().get(0).getOffset(),
                draft.getMentions().get(0).getOffset() + draft.getMentions().get(0).getLength()));
    }

    @Test public void shiftOnEarlierEditAndDropTouchedRanges() {
        String text = "Hi @Lan, welcome";
        int offset = text.indexOf("@Lan");
        MentionDraft draft = MentionDraft.empty(text).withMention(text, "lan", offset, 4);
        MentionDraft shifted = draft.afterEdit(0, 0, 2, "😀" + text);
        assertEquals(offset + 2, shifted.getMentions().get(0).getOffset());

        MentionDraft touched = shifted.afterEdit(offset + 3, 1, 1,
                "😀Hi @Lyn, welcome");
        assertTrue("editing inside the token invalidates its structured range",
                touched.getMentions().isEmpty());
    }

    @Test public void sanitizeDropsOverlapDuplicatesAndSurrogateSplits() {
        String text = "😀 @Lan @Mai";
        List<MessageMention> input = List.of(
                new MessageMention("emoji", 1, 1),
                new MessageMention("lan", 3, 4),
                new MessageMention("overlap", 5, 4),
                new MessageMention("lan", 8, 4));
        List<MessageMention> result = MentionDraft.sanitize(text, input);
        assertEquals(1, result.size());
        assertEquals("lan", result.get(0).getUserId());
        assertFalse(MentionDraft.isValidRange(text, 1, 1));
        assertFalse(MentionDraft.isValidRange(text, 0, 1));
    }

    @Test public void shiftMentionAllAndInvalidateWhenItsTokenChanges() {
        String text = "@all ok";
        MentionDraft draft = MentionDraft.empty(text).withMentionAll(text, 0);
        MentionDraft shifted = draft.afterEdit(0, 0, 2, "😀" + text);
        assertEquals(2, shifted.getMentionAllOffset());
        MentionDraft changed = shifted.afterEdit(3, 1, 1, "😀@aol ok");
        assertFalse(changed.isMentionAll());
    }

    @Test public void trimShiftsStoredRangesIntoFinalTextCoordinates() {
        String text = "  hi @Lan  ";
        MentionDraft draft = MentionDraft.empty(text).withMention(text, "lan", 5, 4).trimmed();
        assertEquals("hi @Lan", draft.getContent());
        assertEquals(3, draft.getMentions().get(0).getOffset());
    }

    @Test public void activeQueryRejectsEmailAndFindsVietnameseSearchText() {
        assertNotNull(MentionDraft.findActiveQuery("@Đặng", 5));
        assertNull(MentionDraft.findActiveQuery("mail@domain", 11));
        assertNull(MentionDraft.findActiveQuery("hello @Lan x", 11));
    }

    @Test public void insertionStopsAtTwentyUniquePeople() {
        String text = "@u0 @u1 @u2 @u3 @u4 @u5 @u6 @u7 @u8 @u9 @u10 @u11 @u12 @u13 @u14 @u15 @u16 @u17 @u18 @u19 @u20";
        List<MessageMention> mentions = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i <= 20; i++) {
            String token = "@u" + i;
            int offset = text.indexOf(token, cursor);
            mentions.add(new MessageMention("u" + i, offset, token.length()));
            cursor = offset + token.length();
        }
        assertEquals(MentionDraft.MAX_MENTIONS, MentionDraft.sanitize(text, mentions).size());
        MentionDraft full = MentionDraft.restore(text, MentionDraft.sanitize(text, mentions), -1, 0);
        assertEquals(MentionDraft.MAX_MENTIONS, full.getMentions().size());
        assertTrue(full.containsUser("u19"));
    }
}
