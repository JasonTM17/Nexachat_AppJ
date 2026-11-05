package com.nexachat.call;

import java.util.UUID;

public record CallDelivery(UUID recipientId, CallEvent event) {
}
