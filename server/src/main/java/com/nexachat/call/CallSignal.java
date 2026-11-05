package com.nexachat.call;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CallSignal(@NotNull Type type, @NotNull UUID callId,
                         @Valid Sdp sdp, @Valid Candidate candidate) {

    public enum Type {
        INVITE,
        ACCEPT,
        REJECT,
        CANCEL,
        END,
        SDP,
        ICE
    }

    public record Sdp(String type, String description) {
    }

    public record Candidate(String sdpMid, Integer sdpMLineIndex, String candidate) {
    }
}
