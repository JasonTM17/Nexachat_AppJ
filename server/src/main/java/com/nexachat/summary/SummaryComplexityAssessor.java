package com.nexachat.summary;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;

/** Decides whether a window is hard enough to justify escalating to the cloud LLM. */
@Component
public class SummaryComplexityAssessor {

    private final int minMessages;
    private final int minChars;
    private final int minParticipants;

    public SummaryComplexityAssessor(
            @Value("${nexachat.summary.complexity.min-messages:12}") int minMessages,
            @Value("${nexachat.summary.complexity.min-chars:1500}") int minChars,
            @Value("${nexachat.summary.complexity.min-participants:4}") int minParticipants) {
        this.minMessages = minMessages;
        this.minChars = minChars;
        this.minParticipants = minParticipants;
    }

    public boolean isComplex(SummaryWindow window, boolean refresh) {
        if (refresh || window.messageCount() >= minMessages) {
            return true;
        }
        int chars = 0;
        var senders = new HashSet<java.util.UUID>();
        for (SummaryMessage m : window.messages()) {
            chars += m.text() == null ? 0 : m.text().length();
            senders.add(m.senderId());
        }
        return chars >= minChars || senders.size() >= minParticipants;
    }
}
