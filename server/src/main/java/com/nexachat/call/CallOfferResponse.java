package com.nexachat.call;

import java.util.UUID;

public record CallOfferResponse(
        UUID callId,
        CallSignal.Sdp sdp,
        CallIceConfig iceConfig
) {
}
