package com.nexachat.summary;

/**
 * Chooses the one extra line that explains how a summary was produced.
 * A local result that names a fallback reason explains that reason.
 * An external result that left messages out says so before mentioning
 * a saved copy or a backup model.
 */
public final class SummaryProvenance {

    public enum Note {
        NONE,
        CACHED,
        FALLBACK_MODEL,
        NO_CONSENT,
        LLM_DISABLED,
        LLM_UNAVAILABLE,
        NO_EXTERNAL_CONTEXT,
        CONTEXT_OMITTED
    }

    private SummaryProvenance() {
    }

    public static Note note(
            String generatedBy,
            String provider,
            String fallbackReason,
            boolean cached,
            boolean contextOmitted) {
        if ("LOCAL".equalsIgnoreCase(generatedBy) && hasText(fallbackReason)) {
            if ("NO_CONSENT".equals(fallbackReason)) {
                return Note.NO_CONSENT;
            }
            if ("LLM_DISABLED".equals(fallbackReason)) {
                return Note.LLM_DISABLED;
            }
            if ("NO_EXTERNAL_CONTEXT".equals(fallbackReason)) {
                return Note.NO_EXTERNAL_CONTEXT;
            }
            return Note.LLM_UNAVAILABLE;
        }
        if ("LLM".equalsIgnoreCase(generatedBy) && contextOmitted) {
            return Note.CONTEXT_OMITTED;
        }
        if ("LLM".equalsIgnoreCase(generatedBy) && "fallback".equalsIgnoreCase(provider)) {
            return Note.FALLBACK_MODEL;
        }
        if (cached) {
            return Note.CACHED;
        }
        return Note.NONE;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
