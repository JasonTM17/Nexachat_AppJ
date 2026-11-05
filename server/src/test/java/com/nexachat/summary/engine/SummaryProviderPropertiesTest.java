package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryProviderPropertiesTest {
    @Test
    void outputIsDeterministicCitedWithinTheWindowAndExcludesEmptyMessages() {
        LocalExtractiveSummaryProvider provider = new LocalExtractiveSummaryProvider();
        List<SummaryMessage> messages = new ArrayList<>(List.of(
                message("m1", 1, "Mọi người thống nhất chốt màu xanh cho màn hình."),
                message("m2", 2, "Nhớ gửi ảnh trước thứ 6."),
                message("m3", 3, "Ai sẽ kiểm tra phần tương phản?"),
                message("m4", 4, "Bản nháp có hai màn danh sách nhóm và cửa sổ chat.")));
        messages.add(new SummaryMessage("empty", 5, "u1", "A", " "));
        messages.add(new SummaryMessage("", 6, "u2", "B", "This must not receive a citation."));
        messages.add(null);
        SummaryWindow window = new SummaryWindow(messages);

        LocalSummary first = provider.summarize(window, SummaryLanguage.AUTO);
        LocalSummary second = provider.summarize(window, SummaryLanguage.AUTO);
        assertEquals(first, second);
        assertTrue(first.overview().codePointCount(0, first.overview().length()) <= OverviewComposer.MAX_LENGTH);

        Set<String> inputIds = Set.of("m1", "m2", "m3", "m4", "empty");
        List<LocalSummary.Item> items = new ArrayList<>();
        items.addAll(first.keyPoints());
        items.addAll(first.decisions());
        items.addAll(first.questions());
        for (LocalSummary.Item item : items) {
            assertFalse(item.text().isBlank());
            assertFalse(item.messageIds().isEmpty());
            assertTrue(inputIds.containsAll(item.messageIds()));
            assertFalse(item.messageIds().contains("empty"));
        }
        for (LocalSummary.ActionItem item : first.actionItems()) {
            assertFalse(item.text().isBlank());
            assertFalse(item.messageIds().isEmpty());
            assertTrue(inputIds.containsAll(item.messageIds()));
            assertFalse(item.messageIds().contains("empty"));
        }
    }

    @Test
    void equalScoreMessagesHaveTheSameOutputWhenInputOrderChanges() {
        LocalExtractiveSummaryProvider provider = new LocalExtractiveSummaryProvider();
        List<SummaryMessage> messages = List.of(
                messageWithSender("z", 10, "u1", "We agreed to keep the existing color palette."),
                messageWithSender("a", 10, "u1", "We agreed to keep the existing color palette."),
                messageWithSender("b", 10, "u2", "We agreed to keep the existing color palette."));
        List<SummaryMessage> reversed = new ArrayList<>(messages);
        Collections.reverse(reversed);

        assertEquals(provider.summarize(new SummaryWindow(messages), SummaryLanguage.EN),
                provider.summarize(new SummaryWindow(reversed), SummaryLanguage.EN));
    }

    @Test
    void oneProviderInstanceCanServeConcurrentCallsDeterministically() throws ExecutionException, InterruptedException {
        LocalExtractiveSummaryProvider provider = new LocalExtractiveSummaryProvider();
        SummaryWindow window = new SummaryWindow(List.of(
                message("m1", 1, "We agreed to keep the current tab order."),
                message("m2", 2, "Please send the revised screen tomorrow."),
                message("m3", 3, "Who will review the contrast?")));
        LocalSummary expected = provider.summarize(window, SummaryLanguage.EN);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<LocalSummary>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(executor.submit(() -> provider.summarize(window, SummaryLanguage.EN)));
            }
            for (Future<LocalSummary> result : results) {
                assertEquals(expected, result.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void handlesEmptyAndNullWindowsAsEmptyImmutableResults() {
        LocalExtractiveSummaryProvider provider = new LocalExtractiveSummaryProvider();
        LocalSummary empty = provider.summarize(new SummaryWindow(null), SummaryLanguage.AUTO);
        assertTrue(empty.overview().isEmpty());
        assertTrue(empty.keyPoints().isEmpty());
        assertTrue(empty.decisions().isEmpty());
        assertTrue(empty.actionItems().isEmpty());
        assertTrue(empty.questions().isEmpty());
        assertTrue(empty.topics().isEmpty());
        assertTrue(empty.participants().isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> empty.keyPoints().add(new LocalSummary.Item("bad", List.of("missing"))));
    }

    private static SummaryMessage message(String id, long seq, String text) {
        return messageWithSender(id, seq, "u1", text);
    }

    private static SummaryMessage messageWithSender(String id, long seq, String sender, String text) {
        return new SummaryMessage(id, seq, sender, sender, text);
    }
}
