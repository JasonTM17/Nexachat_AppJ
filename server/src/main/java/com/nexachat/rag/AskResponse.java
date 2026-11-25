package com.nexachat.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Ask result. {@code contextOmitted} is a neutral caller-facing flag: some
 * eligible messages were left out of the retrieval context because a member
 * opted out of AI processing. It never identifies which member opted out.
 *
 * <p>The reason, retry, provenance, extraction and relevance-aware omission
 * fields are additive metadata. Older clients can ignore them and continue to
 * use only {@code answer}, {@code citations}, {@code mode}, {@code contextOmitted}
 * and {@code usage}.</p>
 */
public record AskResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS) String answer,
        List<RagCitation> citations,
        AskMode mode,
        boolean contextOmitted,
        Usage usage,
        AskReason notFoundReason,
        AskReason degradationReason,
        boolean retryable,
        Integer retryAfterSeconds,
        AskProvenance provenance,
        boolean evidenceOmitted,
        boolean retrievalAffected,
        boolean sensitiveContentRedacted,
        AskExtraction extraction) {

    public AskResponse {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** Compatibility constructor for callers that do not report extended metadata. */
    public AskResponse(String answer, List<RagCitation> citations, AskMode mode, Usage usage) {
        this(answer, citations, mode, false, usage);
    }

    /** Compatibility constructor before reason/provenance metadata existed. */
    public AskResponse(
            String answer,
            List<RagCitation> citations,
            AskMode mode,
            boolean contextOmitted,
            Usage usage) {
        this(answer, citations, mode, contextOmitted, usage,
                mode == AskMode.NOT_FOUND ? AskReason.NO_EVIDENCE : null,
                null, false, null, null, false, false, false, null);
    }

    public static AskResponse notFound(boolean contextOmitted) {
        return notFound(contextOmitted, AskReason.NO_EVIDENCE, false, null, false, false);
    }

    static AskResponse notFound(
            boolean contextOmitted,
            AskReason reason,
            boolean retryable,
            Integer retryAfterSeconds,
            boolean evidenceOmitted,
            boolean llmAttempted) {
        return new AskResponse(
                null,
                List.of(),
                AskMode.NOT_FOUND,
                contextOmitted,
                new Usage(0, 0, false),
                reason,
                null,
                retryable,
                retryAfterSeconds,
                AskProvenance.attemptOnly(llmAttempted),
                evidenceOmitted,
                evidenceOmitted,
                false,
                null);
    }

    AskResponse withEvidenceOmission(boolean omittedEvidence) {
        return new AskResponse(answer, citations, mode, contextOmitted, usage,
                notFoundReason, degradationReason, retryable, retryAfterSeconds, provenance,
                omittedEvidence, omittedEvidence, sensitiveContentRedacted, extraction);
    }

    AskResponse withProvenance(AskProvenance updated) {
        return new AskResponse(answer, citations, mode, contextOmitted, usage,
                notFoundReason, degradationReason, retryable, retryAfterSeconds, updated,
                evidenceOmitted, retrievalAffected, sensitiveContentRedacted, extraction);
    }

    public record Usage(int llmCalls, int inputChars, boolean cached, String provider) {

        public Usage(int llmCalls, int inputChars, boolean cached) {
            this(llmCalls, inputChars, cached, null);
        }
    }
}
