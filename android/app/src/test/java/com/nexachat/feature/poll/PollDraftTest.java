package com.nexachat.feature.poll;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.Test;

public final class PollDraftTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    @Test
    public void acceptsAValidPollAndTrimsThePayload() {
        PollDraft draft = new PollDraft(
                "  Đi đâu?  ",
                List.of("  Biển ", "Núi  "),
                true,
                false,
                NOW.plusSeconds(3600));

        assertEquals(PollValidation.OK, draft.validate(NOW));
        PollDraft normalized = draft.normalized();
        assertEquals("Đi đâu?", normalized.getQuestion());
        assertEquals(List.of("Biển", "Núi"), normalized.getOptions());
    }

    @Test
    public void enforcesQuestionBounds() {
        assertEquals(PollValidation.QUESTION_REQUIRED,
                draft("   ", List.of("A", "B")).validate(NOW));
        assertEquals(PollValidation.QUESTION_TOO_LONG,
                draft("a".repeat(201), List.of("A", "B")).validate(NOW));
    }

    @Test
    public void enforcesOptionCountAndLabelBounds() {
        assertEquals(PollValidation.TOO_FEW_OPTIONS,
                draft("Q", List.of("A")).validate(NOW));
        assertEquals(PollValidation.TOO_MANY_OPTIONS,
                draft("Q", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"))
                        .validate(NOW));
        assertEquals(PollValidation.OPTION_REQUIRED,
                draft("Q", List.of("A", "  ")).validate(NOW));
        assertEquals(PollValidation.OPTION_TOO_LONG,
                draft("Q", List.of("A", "b".repeat(101))).validate(NOW));
        assertEquals(PollValidation.DUPLICATE_OPTION,
                draft("Q", List.of("A", " A ")).validate(NOW));
    }

    @Test
    public void enforcesTheExpiryWindow() {
        assertEquals(PollValidation.EXPIRY_PAST,
                draftWithExpiry(NOW).validate(NOW));
        assertEquals(PollValidation.EXPIRY_TOO_FAR,
                draftWithExpiry(NOW.plusSeconds(31L * 24 * 3600)).validate(NOW));
        assertEquals(PollValidation.OK,
                draftWithExpiry(NOW.plusSeconds(30L * 24 * 3600)).validate(NOW));
    }

    @Test
    public void singleChoiceFlagDoesNotAffectValidation() {
        PollDraft single = new PollDraft("Q", List.of("A", "B"), false, true, null);
        PollDraft multiple = new PollDraft("Q", List.of("A", "B"), true, false, null);
        assertEquals(PollValidation.OK, single.validate(NOW));
        assertEquals(PollValidation.OK, multiple.validate(NOW));
    }

    private static PollDraft draft(String question, List<String> options) {
        return new PollDraft(question, options, false, false, null);
    }

    private static PollDraft draftWithExpiry(Instant closesAt) {
        return new PollDraft("Q", List.of("A", "B"), false, false, closesAt);
    }
}
