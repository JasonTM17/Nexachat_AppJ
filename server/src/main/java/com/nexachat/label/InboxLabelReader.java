package com.nexachat.label;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Batch read of the caller's label IDs for a set of inbox rows. */
public interface InboxLabelReader {

    Map<UUID, List<UUID>> findInboxLabelIds(UUID userId, Collection<UUID> conversationIds);
}
