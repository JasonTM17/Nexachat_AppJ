package com.nexachat.summary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SummaryLists {
    private SummaryLists() { }

    static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
