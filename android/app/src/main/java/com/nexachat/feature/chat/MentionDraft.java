package com.nexachat.feature.chat;

import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.MessageMention;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable composer mention ranges, kept in UTF-16 offsets to match EditText and the API. */
public final class MentionDraft {
    public static final int MAX_MENTIONS = 20;
    public static final String ALL_TOKEN = "@all";

    private final String content;
    private final List<MessageMention> mentions;
    private final int mentionAllOffset;
    private final int mentionAllLength;

    private MentionDraft(String content, List<MessageMention> mentions,
            int mentionAllOffset, int mentionAllLength) {
        this.content = content == null ? "" : content;
        this.mentions = sanitize(this.content, mentions);
        this.mentionAllOffset = isExactRange(this.content, mentionAllOffset,
                mentionAllLength, ALL_TOKEN) ? mentionAllOffset : -1;
        this.mentionAllLength = this.mentionAllOffset < 0 ? 0 : mentionAllLength;
    }

    public static MentionDraft empty(String content) {
        return new MentionDraft(content, List.of(), -1, 0);
    }

    public static MentionDraft restore(String content, List<MessageMention> mentions,
            int mentionAllOffset, int mentionAllLength) {
        return new MentionDraft(content, mentions, mentionAllOffset, mentionAllLength);
    }

    /** Reconstructs the local @all marker used by the composer when editing a stored message. */
    public static MentionDraft fromMessage(String content, List<MessageMention> mentions,
            boolean mentionAll) {
        int marker = mentionAll && content != null ? content.indexOf(ALL_TOKEN) : -1;
        return new MentionDraft(content, mentions, marker, marker < 0 ? 0 : ALL_TOKEN.length());
    }

    public String getContent() { return content; }
    public List<MessageMention> getMentions() { return mentions; }
    public boolean isMentionAll() {
        return isExactRange(content, mentionAllOffset, mentionAllLength, ALL_TOKEN);
    }
    public int getMentionAllOffset() { return isMentionAll() ? mentionAllOffset : -1; }
    public int getMentionAllLength() { return isMentionAll() ? mentionAllLength : 0; }

    /** Applies one TextWatcher replacement, shifting untouched ranges and dropping touched ones. */
    public MentionDraft afterEdit(int start, int before, int inserted, String nextContent) {
        int editEnd = start + before;
        int delta = inserted - before;
        List<MessageMention> shifted = new ArrayList<>();
        for (MessageMention mention : mentions) {
            int offset = mention.getOffset();
            int end = offset + mention.getLength();
            if (before == 0) {
                if (start > offset && start < end) continue;
                shifted.add(new MessageMention(mention.getUserId(),
                        start <= offset ? offset + inserted : offset, mention.getLength()));
            } else if (editEnd <= offset) {
                shifted.add(new MessageMention(mention.getUserId(), offset + delta,
                        mention.getLength()));
            } else if (start >= end) {
                shifted.add(mention);
            }
        }

        int nextAllOffset = mentionAllOffset;
        if (isMentionAll()) {
            int end = mentionAllOffset + mentionAllLength;
            if (before == 0) {
                if (start > mentionAllOffset && start < end) nextAllOffset = -1;
                else if (start <= mentionAllOffset) nextAllOffset += inserted;
            } else if (editEnd <= mentionAllOffset) {
                nextAllOffset += delta;
            } else if (start < end) {
                nextAllOffset = -1;
            }
        }
        return new MentionDraft(nextContent, shifted, nextAllOffset,
                nextAllOffset < 0 ? 0 : mentionAllLength);
    }

    /** Adds a person range after the trigger text has been replaced by the visible token. */
    public MentionDraft withMention(String nextContent, String userId, int offset, int length) {
        if (userId == null || userId.isBlank() || mentions.size() >= MAX_MENTIONS) return this;
        List<MessageMention> next = new ArrayList<>(mentions);
        next.add(new MessageMention(userId, offset, length));
        return new MentionDraft(nextContent, next, mentionAllOffset, mentionAllLength);
    }

    /** Adds an @all marker range after the trigger text has been replaced. */
    public MentionDraft withMentionAll(String nextContent, int offset) {
        return new MentionDraft(nextContent, mentions, offset, ALL_TOKEN.length());
    }

    public boolean containsUser(String userId) {
        for (MessageMention mention : mentions) {
            if (userId != null && userId.equals(mention.getUserId())) return true;
        }
        return false;
    }

    /** Trims exactly as String.trim() and moves metadata into the stored-text coordinate space. */
    public MentionDraft trimmed() {
        int start = 0;
        int end = content.length();
        while (start < end && content.charAt(start) <= ' ') start++;
        while (end > start && content.charAt(end - 1) <= ' ') end--;
        List<MessageMention> adjusted = new ArrayList<>();
        for (MessageMention mention : mentions) {
            int mentionEnd = mention.getOffset() + mention.getLength();
            if (mention.getOffset() >= start && mentionEnd <= end) {
                adjusted.add(new MessageMention(mention.getUserId(), mention.getOffset() - start,
                        mention.getLength()));
            }
        }
        int allOffset = isMentionAll() && mentionAllOffset >= start
                && mentionAllOffset + mentionAllLength <= end ? mentionAllOffset - start : -1;
        return new MentionDraft(content.substring(start, end), adjusted, allOffset,
                allOffset < 0 ? 0 : mentionAllLength);
    }

    @Nullable
    public static Query findActiveQuery(String text, int selectionStart) {
        if (text == null) return null;
        int caret = Math.max(0, Math.min(selectionStart, text.length()));
        int at = -1;
        for (int index = caret - 1; index >= 0; index--) {
            char value = text.charAt(index);
            if (value == '@') {
                at = index;
                break;
            }
            if (Character.isWhitespace(value)) break;
        }
        if (at < 0) return null;
        if (at > 0) {
            char previous = text.charAt(at - 1);
            if (Character.isLetterOrDigit(previous) || previous == '_' || previous == '@') return null;
        }
        return new Query(at, caret, text.substring(at + 1, caret));
    }

    public static List<MessageMention> sanitize(String text, List<MessageMention> candidates) {
        if (text == null || candidates == null || candidates.isEmpty()) return List.of();
        List<MessageMention> ordered = new ArrayList<>();
        for (MessageMention mention : candidates) if (mention != null) ordered.add(mention);
        ordered.sort(Comparator.comparingInt(MessageMention::getOffset)
                .thenComparingInt(MessageMention::getLength));
        List<MessageMention> valid = new ArrayList<>();
        Set<String> users = new HashSet<>();
        int previousEnd = -1;
        for (MessageMention mention : ordered) {
            if (mention == null || mention.getUserId() == null || mention.getUserId().isBlank()) continue;
            int start = mention.getOffset();
            int end = start + mention.getLength();
            if (!isValidRange(text, start, mention.getLength()) || start < previousEnd
                    || !users.add(mention.getUserId())) continue;
            valid.add(mention);
            previousEnd = end;
            if (valid.size() == MAX_MENTIONS) break;
        }
        return List.copyOf(valid);
    }

    public static boolean isValidRange(String text, int offset, int length) {
        if (text == null || offset < 0 || length <= 0 || offset > text.length() - length) return false;
        int end = offset + length;
        if (offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset))) return false;
        return end <= 0 || end >= text.length()
                || !Character.isHighSurrogate(text.charAt(end - 1))
                || !Character.isLowSurrogate(text.charAt(end));
    }

    private static boolean isExactRange(String text, int offset, int length, String expected) {
        return isValidRange(text, offset, length) && expected.length() == length
                && expected.equals(text.substring(offset, offset + length));
    }

    public static final class Query {
        private final int start;
        private final int end;
        private final String value;
        Query(int start, int end, String value) {
            this.start = start;
            this.end = end;
            this.value = value;
        }
        public int getStart() { return start; }
        public int getEnd() { return end; }
        public String getValue() { return value; }
    }
}
