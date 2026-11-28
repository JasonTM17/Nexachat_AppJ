package com.nexachat.feature.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class FeedRulesTest {

    @Test public void normalizeTrimsAndDropsBlankBodies() {
        assertEquals("hello", FeedRules.normalize("  hello  "));
        assertNull(FeedRules.normalize(null));
        assertNull(FeedRules.normalize(""));
        assertNull(FeedRules.normalize("   \n\t "));
    }

    @Test public void normalizeSearchQueryTrimsAndCapsAtMaxLength() {
        assertNull(FeedRules.normalizeSearchQuery("   "));
        assertEquals("xin chào", FeedRules.normalizeSearchQuery("  xin chào  "));
        assertEquals(FeedRules.MAX_SEARCH_LENGTH,
                FeedRules.normalizeSearchQuery("x".repeat(150)).length());
    }

    @Test public void lengthCountsTheTrimmedBody() {
        assertEquals(0, FeedRules.length(null));
        assertEquals(0, FeedRules.length("   "));
        assertEquals(5, FeedRules.length("  hello  "));
    }

    @Test public void aPostNeedsTextOrAnImage() {
        assertFalse(FeedRules.canSubmitPost(null, false));
        assertFalse(FeedRules.canSubmitPost("   ", false));
        assertTrue(FeedRules.canSubmitPost("hello", false));
        assertTrue(FeedRules.canSubmitPost(null, true));
        assertTrue(FeedRules.canSubmitPost("   ", true));
    }

    @Test public void aVideoPostRequiresANonBlankCaption() {
        assertFalse(FeedRules.canSubmitPost(null, false, true));
        assertFalse(FeedRules.canSubmitPost("   ", false, true));
        assertTrue(FeedRules.canSubmitPost("caption", false, true));
        assertFalse(FeedRules.canSubmitPost("a".repeat(FeedRules.MAX_POST_LENGTH + 1), false, true));
    }

    @Test public void editingAPostRequiresANonBlankCaptionWithinLimit() {
        assertFalse(FeedRules.canSubmitEditedPost(null));
        assertFalse(FeedRules.canSubmitEditedPost("   "));
        assertTrue(FeedRules.canSubmitEditedPost("updated"));
        assertFalse(FeedRules.canSubmitEditedPost("a".repeat(FeedRules.MAX_POST_LENGTH + 1)));
    }

    @Test public void aPostBodyMustFitTheServerLimit() {
        assertTrue(FeedRules.canSubmitPost("a".repeat(FeedRules.MAX_POST_LENGTH), false));
        assertFalse(FeedRules.canSubmitPost("a".repeat(FeedRules.MAX_POST_LENGTH + 1), false));
        assertFalse("an over-long body is refused even with an image",
                FeedRules.canSubmitPost("a".repeat(FeedRules.MAX_POST_LENGTH + 1), true));
    }

    @Test public void aCommentNeedsTextAndMustFitTheServerLimit() {
        assertFalse(FeedRules.canSubmitComment(null));
        assertFalse(FeedRules.canSubmitComment("  "));
        assertTrue(FeedRules.canSubmitComment("hi"));
        assertTrue(FeedRules.canSubmitComment("a".repeat(FeedRules.MAX_COMMENT_LENGTH)));
        assertFalse(FeedRules.canSubmitComment("a".repeat(FeedRules.MAX_COMMENT_LENGTH + 1)));
    }

    @Test public void aReactionPillIsInsetToReachTheTouchTargetWithoutGrowingTheLook() {
        assertEquals(8, FeedRules.reactionPillInset(48, 32));
        assertEquals(0, FeedRules.reactionPillInset(32, 32));
        assertEquals(0, FeedRules.reactionPillInset(24, 32));
        assertEquals(12, FeedRules.reactionPillInset(60, 36));
    }

    @Test public void rateLimitingIsReadFromTheApiFailure() {
        assertFalse(FeedRules.isRateLimited(null));
        assertFalse(FeedRules.isRateLimited(new IllegalStateException("boom")));
        assertTrue(FeedRules.isRateLimited(new FeedApiException(429, null)));
        assertTrue(FeedRules.isRateLimited(new FeedApiException(400, "RATE_LIMITED")));
        assertFalse(FeedRules.isRateLimited(new FeedApiException(404, "POST_NOT_FOUND")));
    }

    @Test public void videoErrorMatchersRecognizeServerCodes() {
        assertTrue(FeedRules.isSocialFeatureDisabled(
                new FeedApiException(403, "SOCIAL_FEATURE_DISABLED")));
        assertFalse(FeedRules.isSocialFeatureDisabled(new FeedApiException(403, "FORBIDDEN")));
        assertTrue(FeedRules.isFileTooLarge(new FeedApiException(413, "FILE_TOO_LARGE")));
        assertTrue(FeedRules.isMimeNotAllowed(new FeedApiException(415, "MIME_NOT_ALLOWED")));
        assertTrue(FeedRules.isMp4Mime("video/mp4"));
        assertTrue(FeedRules.isMp4Mime("VIDEO/MP4"));
        assertFalse(FeedRules.isMp4Mime("video/webm"));
        assertTrue(FeedRules.hasIsoBmffFtypBox(new byte[]{0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}));
        assertFalse(FeedRules.hasIsoBmffFtypBox(new byte[]{0, 0, 0, 1}));
    }

    @Test public void hasReactionReadsOnlyTheCallersOwnReaction() {
        List<FeedRepository.Reaction> reactions = List.of(
                new FeedRepository.Reaction("\uD83D\uDC4D", 4, false),
                new FeedRepository.Reaction("\u2764\uFE0F", 2, true));

        assertFalse(FeedRules.hasReaction(reactions, "\uD83D\uDC4D"));
        assertTrue(FeedRules.hasReaction(reactions, "\u2764\uFE0F"));
        assertFalse(FeedRules.hasReaction(reactions, "\uD83D\uDE02"));
        assertFalse(FeedRules.hasReaction(List.of(), "\uD83D\uDC4D"));
    }

    @Test public void togglingANewEmojiAppendsIt() {
        List<FeedRepository.Reaction> next = FeedRules.toggle(
                List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 4, false)),
                "\u2764\uFE0F", true);

        assertEquals(List.of(
                new FeedRepository.Reaction("\uD83D\uDC4D", 4, false),
                new FeedRepository.Reaction("\u2764\uFE0F", 1, true)), next);
    }

    @Test public void togglingAnExistingEmojiKeepsItsSlot() {
        List<FeedRepository.Reaction> next = FeedRules.toggle(
                List.of(
                        new FeedRepository.Reaction("\uD83D\uDC4D", 4, false),
                        new FeedRepository.Reaction("\u2764\uFE0F", 2, false)),
                "\u2764\uFE0F", true);

        assertEquals(List.of(
                new FeedRepository.Reaction("\uD83D\uDC4D", 4, false),
                new FeedRepository.Reaction("\u2764\uFE0F", 3, true)), next);
    }

    @Test public void removingTheLastReactionDropsTheEmoji() {
        List<FeedRepository.Reaction> next = FeedRules.toggle(
                List.of(
                        new FeedRepository.Reaction("\uD83D\uDC4D", 4, false),
                        new FeedRepository.Reaction("\u2764\uFE0F", 1, true)),
                "\u2764\uFE0F", false);

        assertEquals(List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 4, false)), next);
    }

    @Test public void removingAnAbsentEmojiChangesNothing() {
        List<FeedRepository.Reaction> current =
                List.of(new FeedRepository.Reaction("\uD83D\uDC4D", 4, false));

        assertEquals(current, FeedRules.toggle(current, "\uD83D\uDE02", false));
    }

    @Test public void theToggledListIsImmutable() {
        List<FeedRepository.Reaction> next = FeedRules.toggle(List.of(), "\uD83D\uDC4D", true);

        try {
            next.add(new FeedRepository.Reaction("\u2764\uFE0F", 1, true));
            throw new AssertionError("expected an immutable list");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, next.size());
        }
    }
}
