package com.nexachat.core.network.dto;

/** {@code PUT /conversations/{id}/disappearing} body; {@code 0} clears the timer. */
public final class DisappearingRequest {
    private final int seconds;

    public DisappearingRequest(int seconds) {
        this.seconds = seconds;
    }

    public int getSeconds() {
        return seconds;
    }
}
