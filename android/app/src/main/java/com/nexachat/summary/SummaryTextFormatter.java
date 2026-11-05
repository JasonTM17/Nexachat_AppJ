package com.nexachat.summary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Formats a summary for plain-text sharing in Vietnamese or English. */
public final class SummaryTextFormatter {
    private SummaryTextFormatter() { }

    public static String toShareText(SummaryResponse summary, Locale locale) {
        if (summary == null) {
            return "";
        }
        boolean english = isEnglish(locale, summary.getLanguage());
        StringBuilder output = new StringBuilder();
        String overview = clean(summary.getOverview());
        if (!overview.isEmpty()) {
            output.append(english ? "Conversation summary" : "Tóm tắt cuộc trò chuyện")
                    .append('\n')
                    .append(overview);
        }

        appendSection(output, english ? "Key points" : "Điểm chính", itemTexts(summary.getKeyPoints()));
        appendSection(output, english ? "Decisions" : "Quyết định", itemTexts(summary.getDecisions()));
        appendSection(output, english ? "Action items" : "Việc cần làm", actionTexts(summary.getActionItems()));
        appendSection(output, english ? "Open questions" : "Câu hỏi còn mở", itemTexts(summary.getQuestions()));
        appendSection(output, english ? "Topics" : "Chủ đề", summary.getTopics());
        return output.toString();
    }

    private static boolean isEnglish(Locale locale, SummaryLanguage language) {
        if (locale != null && !locale.getLanguage().isEmpty()) {
            return "en".equalsIgnoreCase(locale.getLanguage());
        }
        return language == SummaryLanguage.EN;
    }

    private static List<String> itemTexts(List<SummaryItem> items) {
        List<String> texts = new ArrayList<>();
        for (SummaryItem item : items) {
            if (item != null) {
                texts.add(item.getText());
            }
        }
        return texts;
    }

    private static List<String> actionTexts(List<SummaryActionItem> items) {
        List<String> texts = new ArrayList<>();
        for (SummaryActionItem item : items) {
            if (item != null) {
                texts.add(item.getText());
            }
        }
        return texts;
    }

    private static void appendSection(StringBuilder output, String heading, List<String> items) {
        List<String> cleanItems = new ArrayList<>();
        for (String item : items) {
            String value = clean(item);
            if (!value.isEmpty()) {
                cleanItems.add(value);
            }
        }
        if (cleanItems.isEmpty()) {
            return;
        }
        if (output.length() > 0) {
            output.append("\n\n");
        }
        output.append(heading);
        for (String item : cleanItems) {
            output.append("\n• ").append(item);
        }
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }
}
