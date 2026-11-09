package com.nexachat.rag;

import java.util.List;

/**
 * Ask result. {@code contextOmitted} is a neutral caller-facing flag: some
 * eligible messages were left out of the retrieval context because a member
 * opted out of AI processing. It never identifies which member opted out.
 */
public record AskResponse(
        String answer,
        List<RagCitation> citations,
        AskMode mode,
        boolean contextOmitted,
        Usage usage) {

    public AskResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Compatibility constructor for callers that do not report context omission. */
    public AskResponse(String answer, List<RagCitation> citations, AskMode mode, Usage usage) {
        this(answer, citations, mode, false, usage);
    }

    public static AskResponse notFound(boolean contextOmitted) {
        return new AskResponse(null, List.of(), AskMode.NOT_FOUND, contextOmitted, new Usage(0, 0, false));
    }

    public record Usage(int llmCalls, int inputChars, boolean cached, String provider) {

        public Usage(int llmCalls, int inputChars, boolean cached) {
            this(llmCalls, inputChars, cached, null);
        }
    }
}
