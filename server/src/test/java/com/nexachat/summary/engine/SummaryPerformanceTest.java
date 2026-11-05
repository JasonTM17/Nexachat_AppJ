package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryPerformanceTest {
    private static final int MESSAGE_COUNT = 500;
    private static final int WARMUP_RUNS = 8;
    private static final int MEASURED_RUNS = 40;
    private static final long GENEROUS_CI_LIMIT_MILLIS = 1_500;

    @Test
    void summarizesFiveHundredMessagesAndReportsMeasuredP95() {
        LocalExtractiveSummaryProvider provider = new LocalExtractiveSummaryProvider();
        SummaryWindow window = syntheticWindow();
        for (int i = 0; i < WARMUP_RUNS; i++) {
            provider.summarize(window, SummaryLanguage.AUTO);
        }

        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long start = System.nanoTime();
            provider.summarize(window, SummaryLanguage.AUTO);
            durations.add(System.nanoTime() - start);
        }
        durations.sort(Comparator.naturalOrder());
        long p95Millis = durations.get((int) Math.ceil(MEASURED_RUNS * 0.95) - 1) / 1_000_000;
        double meanMillis = durations.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;
        System.out.printf("messages=%d samples=%d p95=%dms mean=%.2fms target=300ms%n",
                MESSAGE_COUNT, MEASURED_RUNS, p95Millis, meanMillis);
        assertTrue(p95Millis < GENEROUS_CI_LIMIT_MILLIS,
                "500-message p95 exceeded the generous CI guardrail");
    }

    private static SummaryWindow syntheticWindow() {
        List<SummaryMessage> messages = new ArrayList<>(MESSAGE_COUNT);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            String text = switch (i % 7) {
                case 0 -> "We agreed to keep the current release plan and review the chat flow.";
                case 1 -> "Please send the revised screen by Friday so the group can review it.";
                case 2 -> "Who will check the reconnect behavior before the next demo?";
                case 3 -> "The draft includes the inbox, chat window, and message search.";
                case 4 -> "Nhóm thống nhất giữ luồng chat và kiểm tra bản nháp vào ngày mai.";
                case 5 -> "Nhớ gửi ảnh màn hình trước thứ 6 để cả nhóm xem lại.";
                default -> "The current design keeps the message list readable on small screens.";
            };
            messages.add(new SummaryMessage("synthetic-" + i, i + 1, "sender-" + (i % 8),
                    "Member " + (i % 8), text));
        }
        return new SummaryWindow(messages);
    }
}
