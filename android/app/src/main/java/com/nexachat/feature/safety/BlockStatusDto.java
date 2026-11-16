package com.nexachat.feature.safety;

import com.squareup.moshi.Json;

/** `{"blocked": bool}` probe response. */
public final class BlockStatusDto {
    @Json(name = "blocked") private final boolean blocked;

    public BlockStatusDto() {
        this(false);
    }

    public BlockStatusDto(boolean blocked) {
        this.blocked = blocked;
    }

    public boolean isBlocked() {
        return blocked;
    }
}
