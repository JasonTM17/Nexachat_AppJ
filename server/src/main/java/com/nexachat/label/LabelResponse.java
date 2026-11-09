package com.nexachat.label;

import java.time.Instant;
import java.util.UUID;

/** Caller-owned conversation label returned by the REST API. */
public record LabelResponse(UUID id, String name, String colorId,
                            Instant createdAt, Instant updatedAt) {
}
