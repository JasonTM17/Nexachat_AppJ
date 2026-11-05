package com.nexachat.call;

import java.util.List;

public record CallHistoryPage(List<CallHistoryItem> items, String nextCursor) {
}
