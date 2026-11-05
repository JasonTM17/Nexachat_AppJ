package com.nexachat.summary;

import java.util.UUID;

public record SummaryParticipant(UUID userId, String displayName, int messageCount) {
}
