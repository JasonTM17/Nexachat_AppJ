package com.nexachat.summary.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SummaryWindow(List<SummaryMessage> messages) {
    public SummaryWindow {
        messages = messages == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(messages));
    }
}
