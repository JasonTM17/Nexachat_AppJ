package com.nexachat.summary.engine;

import java.util.List;

public final class OverviewComposer {
    public static final int MAX_LENGTH = 400;

    private OverviewComposer() {
    }

    private static final String TERMINAL_PUNCTUATION = ".!?…。！？";

    static String compose(List<SummaryCandidate> keyPoints) {
        String overview = keyPoints.stream()
                .limit(3)
                .map(candidate -> Tokenizer.firstSentence(candidate.message().text()))
                .filter(sentence -> !sentence.isBlank())
                .map(OverviewComposer::withTerminalPunctuation)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        if (overview.length() <= MAX_LENGTH) {
            return overview;
        }
        int end = MAX_LENGTH - 1;
        if (end > 0 && Character.isHighSurrogate(overview.charAt(end - 1))) {
            end--;
        }
        return overview.substring(0, end).stripTrailing() + "…";
    }

    /**
     * Messages without terminal punctuation would otherwise run into the next
     * joined sentence ("...thu Sau nay Hop du an..."), so a period is appended.
     */
    private static String withTerminalPunctuation(String sentence) {
        return TERMINAL_PUNCTUATION.indexOf(sentence.charAt(sentence.length() - 1)) < 0
                ? sentence + "."
                : sentence;
    }
}
