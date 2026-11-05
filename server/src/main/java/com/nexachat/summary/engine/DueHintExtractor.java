package com.nexachat.summary.engine;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DueHintExtractor {
    private static final List<Pattern> HINTS = List.of(
            Pattern.compile("(?iu)\\b20\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b"),
            Pattern.compile("(?iu)\\b(?:today|tomorrow|tonight|this week|next week)\\b"),
            Pattern.compile("(?iu)\\b(?:by|before)\\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b"),
            Pattern.compile("(?iu)\\b(?:thứ\\s+[2-7]|trước\\s+ngày\\s+\\d{1,2})\\b"),
            Pattern.compile("(?iu)\\b(?:sáng|chiều|tối)\\s+mai\\b"),
            Pattern.compile("(?iu)\\b\\d{1,2}\\s*(?:giờ|h)(?:\\s+(?:sáng|chiều|tối))?\\s+mai\\b"),
            Pattern.compile("(?iu)\\b(?:ngày\\s+)?mai\\b"),
            Pattern.compile("(?iu)\\b(?:by|before)\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\b")
    );

    private DueHintExtractor() {
    }

    public static String extract(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String best = null;
        int bestStart = Integer.MAX_VALUE;
        int bestEnd = -1;
        for (Pattern pattern : HINTS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                if (best == null || matcher.start() < bestStart
                        || matcher.start() == bestStart && matcher.end() > bestEnd) {
                    best = matcher.group();
                    bestStart = matcher.start();
                    bestEnd = matcher.end();
                }
            }
        }
        return best == null ? null : best.strip();
    }
}
