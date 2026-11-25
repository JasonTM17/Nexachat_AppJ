package com.nexachat.summary;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class SummaryProvenanceTest {

    @Test
    public void localFallbackExplainsWhyTheExternalModelDidNotAnswer() {
        assertEquals(SummaryProvenance.Note.NO_CONSENT,
                SummaryProvenance.note("LOCAL", null, "NO_CONSENT", false, false));
        assertEquals(SummaryProvenance.Note.LLM_DISABLED,
                SummaryProvenance.note("LOCAL", null, "LLM_DISABLED", true, false));
        assertEquals(SummaryProvenance.Note.NO_EXTERNAL_CONTEXT,
                SummaryProvenance.note("LOCAL", null, "NO_EXTERNAL_CONTEXT", false, false));
        assertEquals(SummaryProvenance.Note.LLM_UNAVAILABLE,
                SummaryProvenance.note("LOCAL", null, "LLM_TIMEOUT", false, false));
        assertEquals(SummaryProvenance.Note.LLM_UNAVAILABLE,
                SummaryProvenance.note("LOCAL", null, "LLM_HTTP_ERROR", false, false));
    }

    @Test
    public void externalResultPrefersOmittedContextOverASavedCopy() {
        assertEquals(SummaryProvenance.Note.CONTEXT_OMITTED,
                SummaryProvenance.note("LLM", "primary", null, true, true));
        assertEquals(SummaryProvenance.Note.FALLBACK_MODEL,
                SummaryProvenance.note("LLM", "fallback", null, false, false));
        assertEquals(SummaryProvenance.Note.CACHED,
                SummaryProvenance.note("LLM", "primary", null, true, false));
        assertEquals(SummaryProvenance.Note.CACHED,
                SummaryProvenance.note("LOCAL", null, null, true, false));
        assertEquals(SummaryProvenance.Note.NONE,
                SummaryProvenance.note("LOCAL", null, null, false, false));
        assertEquals(SummaryProvenance.Note.NONE,
                SummaryProvenance.note("LLM", "primary", "  ", false, false));
    }
}
