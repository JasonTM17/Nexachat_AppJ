package com.nexachat.summary;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryComplexityAssessorTest {

    private final SummaryComplexityAssessor assessor = new SummaryComplexityAssessor(12, 1500, 4);

    private static SummaryWindow window(int n, int len, int senders) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < senders; i++) ids.add(UUID.randomUUID());
        List<SummaryMessage> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            msgs.add(new SummaryMessage(UUID.randomUUID(), i + 1, ids.get(i % senders), "u",
                    "x".repeat(len), Instant.now(), null));
        }
        return new SummaryWindow(UUID.randomUUID(), 1, n, msgs);
    }

    @Test
    void shortWindowIsRoutine() {
        assertThat(assessor.isComplex(window(5, 20, 2), false)).isFalse();
    }

    @Test
    void escalatesOnSizeCharsParticipantsOrRefresh() {
        assertThat(assessor.isComplex(window(12, 5, 2), false)).isTrue();
        assertThat(assessor.isComplex(window(5, 400, 2), false)).isTrue();
        assertThat(assessor.isComplex(window(5, 5, 4), false)).isTrue();
        assertThat(assessor.isComplex(window(5, 5, 2), true)).isTrue();
    }
}
