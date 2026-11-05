package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CueDetectorTest {
    @Test
    void recognizesVietnameseAndEnglishDecisionAndActionCues() {
        assertTrue(CueDetector.isDecision(message("Thống nhất chọn phương án B.")));
        assertTrue(CueDetector.isDecision(message("We agreed to keep the current plan.")));
        assertFalse(CueDetector.isDecision(message("The current plan is listed below.")));

        assertTrue(CueDetector.isAction(message("Nhớ gửi bản tổng hợp trước thứ 6.")));
        assertTrue(CueDetector.isAction(message("Morgan, please send the revised screen by Friday.")));
        assertFalse(CueDetector.isAction(message("The screen is ready.")));
    }

    @Test
    void marksQuestionsUnansweredOnlyWhenNoOtherSenderAnswersInNextFiveMessages() {
        SummaryMessage question = message("Ai sẽ kiểm tra nội dung?");
        List<SummaryMessage> noAnswer = List.of(
                question,
                new SummaryMessage("m2", 2, "u1", "A", "I will check the notes."),
                new SummaryMessage("m3", 3, "u2", "B", "The notes are short."),
                new SummaryMessage("m4", 4, "u1", "A", "We can review the draft."),
                new SummaryMessage("m5", 5, "u2", "B", "The draft is in the folder."),
                new SummaryMessage("m6", 6, "u1", "A", "We have time."),
                new SummaryMessage("m7", 7, "u2", "B", "Yes, I can check it."));
        assertTrue(CueDetector.isQuestion(question));
        assertTrue(CueDetector.isUnansweredQuestion(question, noAnswer));

        List<SummaryMessage> answered = List.of(question,
                new SummaryMessage("m2", 2, "u2", "B", "Sure, I will check it."));
        assertFalse(CueDetector.isUnansweredQuestion(question, answered));

        List<SummaryMessage> linkedReply = List.of(question,
                new SummaryMessage("m2", 2, "u2", "B", "I can take this.", null, null,
                        new SummarySignals(question.id(), 0, false)));
        assertFalse(CueDetector.isUnansweredQuestion(question, linkedReply));
        assertFalse(CueDetector.isUnansweredQuestion(message("status update"), answered));

        SummaryMessage ownershipQuestion = message("Who will review the privacy rule?");
        List<SummaryMessage> unrelatedNegative = List.of(ownershipQuestion,
                new SummaryMessage("m2", 2, "u2", "B", "The note says the group has no veto yet."));
        assertTrue(CueDetector.isUnansweredQuestion(ownershipQuestion, unrelatedNegative));
        List<SummaryMessage> directNegativeAnswer = List.of(ownershipQuestion,
                new SummaryMessage("m2", 2, "u2", "B", "No, we will keep the existing rule."));
        assertFalse(CueDetector.isUnansweredQuestion(ownershipQuestion, directNegativeAnswer));
    }

    @Test
    void extractsDueHintsAndDetectsMentionedOwners() {
        assertEquals("thứ 6", DueHintExtractor.extract("Nhớ gửi bản tổng hợp trước thứ 6."));
        assertEquals("tomorrow", DueHintExtractor.extract("Please review this tomorrow."));
        assertNull(DueHintExtractor.extract("No date was agreed."));

        SummaryMessage action = new SummaryMessage("a", 1, "u1", "Taylor", "Morgan, please send the screen.");
        List<SummaryMessage> messages = List.of(action,
                new SummaryMessage("b", 2, "u2", "Morgan Lee", "I will send it."));
        assertEquals("u2", CueDetector.ownerUserId(action, messages));
    }

    private static SummaryMessage message(String text) {
        return new SummaryMessage("m1", 1, "u1", "A", text);
    }
}
