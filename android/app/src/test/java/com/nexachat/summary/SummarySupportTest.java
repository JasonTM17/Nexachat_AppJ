package com.nexachat.summary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SummarySupportTest {

    @Test
    public void httpCodesMapToStableSummaryErrors() {
        assertEquals(SummaryError.RATE_LIMITED, SummaryErrorMapper.fromHttp(429, null));
        assertEquals(SummaryError.RATE_LIMITED, SummaryErrorMapper.fromHttp(200, "{\"error\":{\"code\":\"RATE_LIMITED\"}}"));
        assertEquals(SummaryError.NOT_MEMBER, SummaryErrorMapper.fromHttp(403, "{\"error\":{\"code\":\"NOT_MEMBER\"}}"));
        assertEquals(SummaryError.TOO_FEW, SummaryErrorMapper.fromHttp(400, "{\"error\":{\"code\":\"TOO_FEW_MESSAGES\"}}"));
        assertEquals(SummaryError.TOO_FEW, SummaryErrorMapper.fromHttp(400, "{\"error\":{\"code\":\"TOO_FEW\"}}"));
        assertEquals(SummaryError.UNKNOWN, SummaryErrorMapper.fromHttp(500, "not json"));
    }

    @Test
    public void transportFailuresAreNetworkAndOtherThrowablesAreUnknown() {
        assertEquals(SummaryError.NETWORK, SummaryErrorMapper.fromFailure(new IOException("offline")));
        assertEquals(SummaryError.UNKNOWN, SummaryErrorMapper.fromFailure(new IllegalStateException("boom")));
    }

    @Test
    public void immutableCopyKeepsVietnameseItemsAndIgnoresLaterSourceEdits() {
        assertTrue(SummaryLists.immutableCopy(null).isEmpty());
        assertTrue(SummaryLists.immutableCopy(List.of()).isEmpty());

        List<String> source = new ArrayList<>();
        source.add("chào buổi sáng");
        List<String> copy = SummaryLists.immutableCopy(source);
        source.add("thêm");

        assertEquals(List.of("chào buổi sáng"), copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.add("nope"));
    }
}
