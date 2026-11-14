package com.nexachat.e2ee.dto;

import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/conversations/{id}/e2ee}. */
public record SetE2eeRequest(@NotNull Boolean enabled) {
}
