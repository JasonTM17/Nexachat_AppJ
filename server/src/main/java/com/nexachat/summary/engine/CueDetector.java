package com.nexachat.summary.engine;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class CueDetector {
    private static final Pattern DECISION = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:chot|quyet\\s+dinh|thong\\s+nhat|ok\\s+vay|dong\\s+y|se\\s+lam"
                    + "|agreed|decided|let['’]?s\\s+go\\s+with|we\\s+will|we['’]ll)(?![\\p{L}\\p{N}])");
    private static final Pattern ACTION = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:can|nho|phai|giup|lam\\s+giup|han|deadline|truoc(?:\\s+ngay)?"
                    + "|please|need(?:\\s+to)?|todo|to-do|must|should|remember\\s+to|by\\s+(?:monday|tuesday"
                    + "|wednesday|thursday|friday|saturday|sunday)|before\\s+(?:monday|tuesday|wednesday"
                    + "|thursday|friday|saturday|sunday))(?![\\p{L}\\p{N}])");
    private static final Pattern INTERROGATIVE = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:khong|chua|sao|bao\\s+gio|ai|dau|may\\s+gio|gi|tai\\s+sao"
                    + "|what|when|where|which|who|whom|why|how|can\\s+you|do\\s+we|does\\s+it)(?![\\p{L}\\p{N}])");
    private static final Pattern ANSWER = Pattern.compile(
            "(?iu)(?:^|[.!?;,]\\s*)(?:yes|no|yeah|sure|ok|okay|done|confirmed|i\\s+can|we\\s+can"
                    + "|i\\s+will|we\\s+will|we['’]ll|that\\s+works|duoc(?:\\s+roi)?|roi|co|xong|dung|da|se\\s+duoc)"
                    + "(?![\\p{L}\\p{N}])");

    private CueDetector() {
    }

    public static boolean isDecision(SummaryMessage message) {
        return message != null && matches(DECISION, message.text());
    }

    public static boolean isAction(SummaryMessage message) {
        return message != null && matches(ACTION, message.text());
    }

    public static boolean isQuestion(SummaryMessage message) {
        if (message == null || message.text() == null) {
            return false;
        }
        return message.text().strip().endsWith("?") || matches(INTERROGATIVE, message.text());
    }

    public static boolean isUnansweredQuestion(SummaryMessage question, List<SummaryMessage> orderedMessages) {
        if (question == null || !isQuestion(question)) {
            return false;
        }
        int questionIndex = -1;
        for (int i = 0; i < orderedMessages.size(); i++) {
            if (Objects.equals(orderedMessages.get(i).id(), question.id())) {
                questionIndex = i;
                break;
            }
        }
        if (questionIndex < 0) {
            return false;
        }

        int end = Math.min(orderedMessages.size(), questionIndex + 6);
        for (int i = questionIndex + 1; i < end; i++) {
            SummaryMessage later = orderedMessages.get(i);
            if (later == null || Objects.equals(later.senderId(), question.senderId())) {
                continue;
            }
            boolean linkedReply = later.signals().replyToMessageId() != null
                    && later.signals().replyToMessageId().equals(question.id());
            if (linkedReply || matches(ANSWER, later.text())) {
                return false;
            }
        }
        return true;
    }

    public static String ownerUserId(SummaryMessage action, List<SummaryMessage> messages) {
        if (action == null || action.text() == null) {
            return null;
        }
        String foldedText = Tokenizer.foldForMatching(action.text());
        return messages.stream()
                .filter(Objects::nonNull)
                .filter(message -> message.senderId() != null && !message.senderId().isBlank())
                .filter(message -> message.senderName() != null && !message.senderName().isBlank())
                .filter(message -> nameMentioned(foldedText, message.senderName()))
                .filter(message -> !message.senderId().equals(action.senderId()))
                .sorted(Comparator.comparingInt((SummaryMessage message) -> message.senderName().length()).reversed()
                        .thenComparing(SummaryMessage::senderId))
                .map(SummaryMessage::senderId)
                .findFirst()
                .orElse(null);
    }

    private static boolean nameMentioned(String foldedText, String name) {
        String foldedName = Tokenizer.foldForMatching(name).strip();
        if (foldedName.isEmpty()) {
            return false;
        }
        for (String namePart : foldedName.split("\\s+")) {
            if (namePart.length() < 2) {
                continue;
            }
            Pattern namePattern = Pattern.compile(
                    "(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(namePart) + "(?![\\p{L}\\p{N}])");
            if (namePattern.matcher(foldedText).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Pattern pattern, String text) {
        return text != null && pattern.matcher(Tokenizer.foldForMatching(text)).find();
    }
}
