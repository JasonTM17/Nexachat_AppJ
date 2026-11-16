package com.nexachat.report.dto;

import java.util.List;

/**
 * Keyset page of the reporter's own filings, newest first.
 * {@code nextCursor} is opaque; {@code null} ends the list.
 */
public record ReportPageResponse(List<ReportResponse> items, String nextCursor) {
}
