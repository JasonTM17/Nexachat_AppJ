package com.nexachat.rag;

import java.util.List;

public record AskResponse(
        String answer,
        List<RagCitation> citations,
        AskMode mode,
        Usage usage) {

    public AskResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static AskResponse notFound() {
        return new AskResponse(null, List.of(), AskMode.NOT_FOUND, new Usage(0, 0, false));
    }

    public record Usage(int llmCalls, int inputChars, boolean cached) {
    }
}
