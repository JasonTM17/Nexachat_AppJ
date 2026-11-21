package com.nexachat.rag;

import java.util.ArrayList;
import java.util.List;

/** Bounds and fingerprints optional follow-up context on an ask request. */
public final class AskPriorTurns {
    public static final int MAX = 3;
    public static final int MAX_ANSWER_CHARS = 2_000;

    private AskPriorTurns() {
    }

    public static List<AskPriorTurn> sanitize(List<AskPriorTurn> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<AskPriorTurn> clean = new ArrayList<>();
        for (AskPriorTurn turn : raw) {
            if (turn == null) {
                continue;
            }
            String question = turn.question() == null ? "" : turn.question().strip();
            String answer = turn.answer() == null ? "" : turn.answer().strip();
            if (question.isEmpty() || answer.isEmpty()) {
                continue;
            }
            if (question.length() > 500) {
                question = question.substring(0, 500);
            }
            if (answer.length() > MAX_ANSWER_CHARS) {
                answer = answer.substring(0, MAX_ANSWER_CHARS);
            }
            clean.add(new AskPriorTurn(question, answer));
            if (clean.size() == MAX) {
                break;
            }
        }
        return List.copyOf(clean);
    }

    public static String fingerprint(List<AskPriorTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (AskPriorTurn turn : turns) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(RagQuestionNormalizer.normalize(turn.question()))
                    .append('=')
                    .append(RagQuestionNormalizer.normalize(turn.answer()));
        }
        return builder.toString();
    }
}
